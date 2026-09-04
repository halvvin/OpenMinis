#!/bin/bash
# [F-A1 packaging fix] verify_apk_native.sh — DT_NEEDED closure check against
# the FINAL APK. Gradle does not inspect DT_NEEDED; a missing non-system
# dependency of libproot.so (libtalloc.so.2, libandroid-shmem.so) produces an
# APK that assembles fine but fails at runtime on EVERY shell command with
# "proot exit=1". This script refuses such an APK.
#
# Usage: scripts/verify_apk_native.sh <path-to.apk>
# Exit 0 = every DT_NEEDED dependency resolves (APK lib, SONAME, or Android
# system lib). Exit 1 = missing dependency or unreadable APK.

set -u

APK="${1:?usage: verify_apk_native.sh <path-to.apk>}"
if [ ! -f "$APK" ]; then
    echo "ERROR: APK not found: $APK" >&2
    exit 1
fi
if ! command -v readelf >/dev/null 2>&1; then
    echo "ERROR: readelf not available (install binutils)" >&2
    exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
unzip -q "$APK" 'lib/arm64-v8a/*' -d "$WORK" 2>/dev/null
LIBDIR="$WORK/lib/arm64-v8a"
if [ ! -d "$LIBDIR" ] || [ -z "$(ls "$LIBDIR" 2>/dev/null)" ]; then
    echo "ERROR: no arm64-v8a native libs in APK" >&2
    exit 1
fi

# System libs that Android always provides (bionic + NDK runtime).
is_system_lib() {
    case "$1" in
        libc.so|libdl.so|libm.so|liblog.so|libz.so|libandroid.so|libc++_shared.so|ld-android.so|libbase.so)
            return 0 ;;
        *) return 1 ;;
    esac
}

# Collect SONAMEs shipped inside the APK.
sonames="$(readelf -d "$LIBDIR"/*.so 2>/dev/null | sed -n 's/.*Library soname: \[\([^]]*\)\].*/\1/p' | sort -u)"

fail=0
for lib in "$LIBDIR"/*.so; do
    name="$(basename "$lib")"
    while IFS= read -r dep; do
        [ -z "$dep" ] && continue
        is_system_lib "$dep" && continue
        # resolves by filename in the APK?
        [ -f "$LIBDIR/$dep" ] && continue
        # resolves by SONAME shipped in the APK?
        if printf '%s\n' "$sonames" | grep -qx -- "$dep"; then continue; fi
        echo "MISSING: $name -> $dep"
        fail=1
    done < <(readelf -d "$lib" 2>/dev/null | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')
done

if [ "$fail" -ne 0 ]; then
    echo "FAIL: DT_NEEDED dependency closure broken — building this APK reintroduces 'proot exit=1'." >&2
    exit 1
fi
echo "OK: every DT_NEEDED dependency resolves (APK: $APK)"
exit 0
