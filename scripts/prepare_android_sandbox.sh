#!/usr/bin/env bash
#
# Prepare Android sandbox assets:
#   1. Download Alpine Linux aarch64 minirootfs
#   2. Download PRoot aarch64 static binary from Termux packages
#   3. Place both into src/android/app/src/main/assets/
#
# Usage: ./scripts/prepare_android_sandbox.sh
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ASSETS_DIR="$PROJECT_ROOT/src/android/app/src/main/assets"
# [T-fix-libproot] The app loads libproot.so from the native lib dir
# (RootfsManager.prootBinary = nativeLibraryDir/libproot.so). jniLibs/*.so
# is gitignored (built locally by deps/build_proot.sh), so CI must place
# the Termux proot binary here too — otherwise PRoot never boots and the
# sandbox shell is dead.
JNILIBS_DIR="$PROJECT_ROOT/src/android/app/src/main/jniLibs/arm64-v8a"

ALPINE_VERSION="3.21"
ALPINE_RELEASE="3.21.3"
ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_VERSION}/releases/aarch64/alpine-minirootfs-${ALPINE_RELEASE}-aarch64.tar.gz"

# Termux proot package — aarch64 static binary
# [T-ci-proot-resolve] Resolve the CURRENT version dynamically from the
# Termux package index; the pinned version below rots as Termux bumps.
PROOT_VERSION="5.1.107-70"
PROOT_REPO="https://packages.termux.dev/apt/termux-main"
# Non-fatal: pipefail+curl SIGPIPE (exit 23) must never abort the script —
# the pinned fallback URL below covers resolution failures.
RESOLVED="$( { curl -fsSL -m 15 "$PROOT_REPO/dists/stable/main/binary-aarch64/Packages" 2>/dev/null || true; } \
    | { awk '/^Package: proot$/{f=1} f&&/^Filename: /{print $2; exit}' || true; })"
if [ -n "$RESOLVED" ]; then
    PROOT_DEB_URL="$PROOT_REPO/$RESOLVED"
else
    PROOT_DEB_URL="$PROOT_REPO/pool/main/p/proot/proot_${PROOT_VERSION}_aarch64.deb"
fi

mkdir -p "$ASSETS_DIR" "$JNILIBS_DIR"

ROOTFS_FILE="$ASSETS_DIR/alpine-minirootfs.tar.gz"
PROOT_FILE="$ASSETS_DIR/proot-aarch64"

# --- Alpine rootfs ---
if [ -f "$ROOTFS_FILE" ]; then
    echo "✓ Alpine rootfs already exists: $ROOTFS_FILE"
else
    echo "Downloading Alpine Linux ${ALPINE_RELEASE} aarch64 minirootfs..."
    curl -fSL -o "$ROOTFS_FILE" "$ALPINE_URL"
    echo "✓ Downloaded: $ROOTFS_FILE ($(du -h "$ROOTFS_FILE" | cut -f1))"
fi

# --- PRoot binary ---
if [ -f "$PROOT_FILE" ]; then
    echo "✓ PRoot binary already exists: $PROOT_FILE"
else
    echo "Downloading PRoot ${PROOT_VERSION} aarch64 from Termux..."

    TMPDIR="$(mktemp -d)"
    trap 'rm -rf "$TMPDIR"' EXIT

    DEB_FILE="$TMPDIR/proot.deb"
    curl -fSL -o "$DEB_FILE" "$PROOT_DEB_URL"

    # Extract .deb (it's an ar archive containing data.tar.xz)
    cd "$TMPDIR"
    ar x "$DEB_FILE"

    # Extract data archive
    if [ -f "data.tar.xz" ]; then
        tar xf data.tar.xz
    elif [ -f "data.tar.gz" ]; then
        tar xzf data.tar.gz
    elif [ -f "data.tar.zst" ]; then
        zstd -d data.tar.zst -o data.tar
        tar xf data.tar
    else
        echo "Error: Could not find data archive in .deb"
        ls -la "$TMPDIR"
        exit 1
    fi

    # Find the proot binary
    PROOT_BIN=$(find "$TMPDIR" -name "proot" -type f | head -1)
    if [ -z "$PROOT_BIN" ]; then
        echo "Error: Could not find proot binary in extracted .deb"
        find "$TMPDIR" -type f
        exit 1
    fi

    cp "$PROOT_BIN" "$PROOT_FILE"
    chmod +x "$PROOT_FILE"
    cd "$PROJECT_ROOT"

    echo "✓ Extracted PRoot binary: $PROOT_FILE ($(du -h "$PROOT_FILE" | cut -f1))"
fi

# [T-fix-libproot] Ensure the app-loadable native lib exists. The Termux
# proot matches the vendored Termux loaders (libproot-loader*.so), so this
# is the correct pairing — the fork-built proot would SEGV with them.
if [ ! -f "$JNILIBS_DIR/libproot.so" ]; then
    cp "$PROOT_FILE" "$JNILIBS_DIR/libproot.so"
    chmod 755 "$JNILIBS_DIR/libproot.so"
    echo "✓ Installed libproot.so → jniLibs ($(du -h "$JNILIBS_DIR/libproot.so" | cut -f1))"
else
    echo "✓ libproot.so already present in jniLibs"
fi

# [T-fix-libtalloc] The Termux proot binary is DYNAMICALLY linked against
# libtalloc.so.2 (the repo's build_proot.sh builds a static one, but the CI
# uses the Termux package). Without libtalloc.so.2 next to it, proot fails
# with: CANNOT LINK EXECUTABLE ".../libproot.so": library "libtalloc.so.2"
# not found. Download the Termux libtalloc package and install the .so.
TALLOC_VERSION="2.4.3"
TALLOC_DEB_URL="$PROOT_REPO/pool/main/libt/libtalloc/libtalloc_${TALLOC_VERSION}_aarch64.deb"
TALLOC_LIB="$JNILIBS_DIR/libtalloc.so.2"
if [ ! -f "$TALLOC_LIB" ]; then
    echo "Downloading libtalloc ${TALLOC_VERSION} aarch64 from Termux..."
    TMPT="$(mktemp -d)"
    if curl -fsSL -m 60 -o "$TMPT/talloc.deb" "$TALLOC_DEB_URL" 2>/dev/null; then
        cd "$TMPT"
        ar x talloc.deb 2>/dev/null
        # Extract whichever data archive variant is present.
        if [ -f data.tar.xz ]; then tar xf data.tar.xz
        elif [ -f data.tar.gz ]; then tar xzf data.tar.gz
        elif [ -f data.tar.zst ]; then zstd -d data.tar.zst -o data.tar && tar xf data.tar
        fi
        FOUND=$(find "$TMPT" -name 'libtalloc.so.2*' -type f | head -1)
        if [ -n "$FOUND" ]; then
            cp "$FOUND" "$TALLOC_LIB"
            chmod 755 "$TALLOC_LIB"
            echo "✓ Installed libtalloc.so.2 → jniLibs ($(du -h "$TALLOC_LIB" | cut -f1))"
        else
            echo "::warning::libtalloc.so.2 not found in package — proot may fail to link"
        fi
        cd "$PROJECT_ROOT"
    else
        echo "::warning::Could not download libtalloc — proot may fail to link"
    fi
    rm -rf "$TMPT"
else
    echo "✓ libtalloc.so.2 already present in jniLibs"
fi

# [F-A1 packaging fix] libandroid-shmem.so — the OTHER non-system DT_NEEDED
# dependency of the Termux libproot.so (SysV shm emulation on Android).
# Missing from the APK = "proot exit=1" on every shell command. Mirrors the
# libtalloc step above; the vendored copy in jniLibs is tracked so CI only
# downloads when it is absent (fresh checkout).
SHMEM_VERSION="0.7"
SHMEM_DEB_URL="$PROOT_REPO/pool/main/liba/libandroid-shmem/libandroid-shmem_${SHMEM_VERSION}_aarch64.deb"
SHMEM_LIB="$JNILIBS_DIR/libandroid-shmem.so"
if [ ! -f "$SHMEM_LIB" ]; then
    echo "Downloading libandroid-shmem ${SHMEM_VERSION} aarch64 from Termux..."
    TMPT="$(mktemp -d)"
    if curl -fsSL -m 60 -o "$TMPT/shmem.deb" "$SHMEM_DEB_URL" 2>/dev/null; then
        cd "$TMPT"
        ar x shmem.deb 2>/dev/null
        if [ -f data.tar.xz ]; then tar xf data.tar.xz
        elif [ -f data.tar.gz ]; then tar xzf data.tar.gz
        elif [ -f data.tar.zst ]; then zstd -d data.tar.zst -o data.tar && tar xf data.tar
        fi
        FOUND=$(find "$TMPT" -name 'libandroid-shmem.so*' -type f | head -1)
        if [ -n "$FOUND" ]; then
            cp "$FOUND" "$SHMEM_LIB"
            chmod 755 "$SHMEM_LIB"
            echo "✓ Installed libandroid-shmem.so → jniLibs ($(du -h "$SHMEM_LIB" | cut -f1))"
        else
            echo "::warning::libandroid-shmem.so not found in package — proot may fail to link"
        fi
        cd "$PROJECT_ROOT"
    else
        echo "::warning::Could not download libandroid-shmem — proot may fail to link"
    fi
    rm -rf "$TMPT"
else
    echo "✓ libandroid-shmem.so already present in jniLibs"
fi

echo ""
echo "Assets ready in: $ASSETS_DIR"
ls -lh "$ASSETS_DIR"
