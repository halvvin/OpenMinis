#!/usr/bin/env bash
#
# Prepare Android sandbox assets:
#   1. Download Alpine Linux aarch64 minirootfs
#   2. Build the OpenMinis/proot fork (native-offload extension) via
#      deps/build_proot.sh and install to assets/ + jniLibs/
#   3. Stage Termux libtalloc + libandroid-shmem runtime deps into jniLibs
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

# --- Termux runtime deps staged FIRST (required by the fork proot's
#     DT_NEEDED closure, checked by build_proot.sh's verify_artifacts) ---

# [T-fix-libtalloc] proot is DYNAMICALLY linked against libtalloc.so.2
# (DT_NEEDED). Without it next to libproot.so, proot fails with: CANNOT
# LINK EXECUTABLE ".../libproot.so": library "libtalloc.so.2" not found.
# Download the Termux libtalloc package and install the .so.
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

# [T-fix-libandroid-shmem] proot is DYNAMICALLY linked against
# libandroid-shmem.so as well (DT_NEEDED; it emulates System V shared
# memory on top of ashmem). Without it next to libproot.so, Android
# refuses to load the library:
#   CANNOT LINK EXECUTABLE ".../libproot.so": library "libandroid-shmem.so"
#   not found
# and every shell command fails with "proot exit=1". Download the Termux
# libandroid-shmem package and install the .so, mirroring the libtalloc step.
SHMEM_VERSION="0.7"
SHMEM_DEB_URL="$PROOT_REPO/pool/main/liba/libandroid-shmem/libandroid-shmem_${SHMEM_VERSION}_aarch64.deb"
SHMEM_LIB="$JNILIBS_DIR/libandroid-shmem.so"
if [ ! -f "$SHMEM_LIB" ]; then
    echo "Downloading libandroid-shmem ${SHMEM_VERSION} aarch64 from Termux..."
    TMPS="$(mktemp -d)"
    if curl -fsSL -m 60 -o "$TMPS/shmem.deb" "$SHMEM_DEB_URL" 2>/dev/null; then
        cd "$TMPS"
        ar x shmem.deb 2>/dev/null
        # Extract whichever data archive variant is present.
        if [ -f data.tar.xz ]; then tar xf data.tar.xz
        elif [ -f data.tar.gz ]; then tar xzf data.tar.gz
        elif [ -f data.tar.zst ]; then zstd -d data.tar.zst -o data.tar && tar xf data.tar
        fi
        FOUND=$(find "$TMPS" -name 'libandroid-shmem.so' -type f | head -1)
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
    rm -rf "$TMPS"
else
    echo "✓ libandroid-shmem.so already present in jniLibs"
fi

# --- PRoot: build the OpenMinis fork (native-offload extension) ---
# The stock Termux PRoot binary does NOT support the --native-offload option
# that the Android app requires (PRootKernel.kt adds it for every shell
# command).  Build the OpenMinis/proot fork via deps/build_proot.sh instead.
# The same binary is installed to BOTH assets/proot-aarch64 (for asset-based
# extraction) and jniLibs/arm64-v8a/libproot.so (for AGP packaging into
# lib/arm64-v8a/).  The vendored Termux loaders are preserved (verified by
# build_proot.sh's install_asset step, sha256-pinned).
FORK_DIR="$PROJECT_ROOT/deps/proot"
FORK_COMMIT="8cf13e997cdc9472997aae19df8050c073c9a86c"
if [ ! -d "$FORK_DIR/.git" ]; then
    echo "Cloning OpenMinis/proot fork (native-offload extension)..."
    git clone https://github.com/OpenMinis/proot.git "$FORK_DIR"
fi
(cd "$FORK_DIR" && git checkout -q "$FORK_COMMIT" 2>/dev/null || true)
chmod +x "$PROJECT_ROOT/deps/build_proot.sh"
"$PROJECT_ROOT/deps/build_proot.sh"

echo ""
echo "Assets ready in: $ASSETS_DIR"
ls -lh "$ASSETS_DIR"
