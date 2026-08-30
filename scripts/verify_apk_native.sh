#!/usr/bin/env bash
#
# verify_apk_native.sh — verify the native dependency closure of a built APK.
#
# For every .so packaged in the APK, read its DT_NEEDED entries and confirm
# each one resolves: either to another library shipped in the same APK, or to
# an Android platform system library (which the OS provides at runtime).
#
# This is the check that Gradle will never perform: assembleDebug happily
# packages a libproot.so whose DT_NEEDED lists libandroid-shmem.so even when
# that library is absent from the APK, producing a silent runtime failure
# ("CANNOT LINK ... library not found", proot exit=1).
#
# Usage:  scripts/verify_apk_native.sh <path-to.apk>
# Exit:   0 = closure complete, 1 = one or more unresolved dependencies
#
# Requires: unzip, readelf (binutils or NDK llvm-readelf)
set -euo pipefail

APK="${1:?usage: verify_apk_native.sh <path-to.apk>}"
if [ ! -f "$APK" ]; then
    echo "::error::APK not found: $APK" >&2
    exit 2
fi

READELF="$(command -v readelf || command -v llvm-readelf || true)"
if [ -z "$READELF" ]; then
    echo "::error::readelf not found — install binutils or provide NDK llvm-readelf" >&2
    exit 2
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Extract all native libs from the APK (arm64-v8a + any other ABIs present).
unzip -q "$APK" "lib/*/*.so" -d "$WORK" 2>/dev/null || true

# ---------------------------------------------------------------------------
# Index every library present in the APK by its basename.
# ---------------------------------------------------------------------------
declare -A APK_LIBS
while IFS= read -r so; do
    [ -n "$so" ] && [ -f "$so" ] || continue
    APK_LIBS["$(basename "$so")"]="$so"
done < <(find "$WORK/lib" -name '*.so' -type f 2>/dev/null)

# Android platform system libraries — provided by the OS, correct to omit
# from the APK. (If the same name exists inside the APK, the in-APK copy
# wins because of the lookup order above.)
SYSTEM_LIBS="libc.so libm.so libdl.so liblog.so libz.so libandroid.so \
libjnigraphics.so libEGL.so libGLESv1_CM.so libGLESv2.so libGLESv3.so \
libOpenSLES.so libOpenMAXAL.so libvulkan.so libaaudio.so libmediandk.so \
libcamera2ndk.so libbinder_ndk.so libnativewindow.so libsync.so libamidi.so \
libicuuc.so libicui18n.so libcrypto.so libssl.so libsqlite.so libexpat.so \
libutils.so libcutils.so liblog.so libhardware.so libc++.so"

FAILED=0

echo "== Native libraries packaged in APK =="
for name in "${!APK_LIBS[@]}"; do
    echo "  ✓ $name ($(wc -c < "${APK_LIBS[$name]}") bytes)"
done
[ "${#APK_LIBS[@]}" -eq 0 ] && echo "  (none)"

echo
echo "== DT_NEEDED dependency closure check =="

# Collect SONAME of every lib in the APK: Android's linker resolves a
# DT_NEEDED name against a library's SONAME too (e.g. the APK ships
# libtalloc.so whose SONAME is libtalloc.so.2, which is what libproot.so
# asks for). A strict filename match would false-positive on that.
declare -A SONAMES
for so in $(find "$WORK/lib" -name '*.so' -type f 2>/dev/null); do
    sn=$("$READELF" -d "$so" 2>/dev/null | sed -n 's/.*(SONAME).*\[\([^]]*\)\].*/\1/p')
    [ -n "$sn" ] && SONAMES["$sn"]="$so"
done

# Iterate over each extracted .so. Process substitution (not a pipe) so
# FAILED accumulates in the main shell instead of a subshell.
for so in $(find "$WORK/lib" -name '*.so' -type f 2>/dev/null); do
    name="$(basename "$so")"
    while IFS= read -r dep; do
        [ -n "$dep" ] || continue

        # Resolved by a library inside the APK (exact filename)?
        if [ -n "${APK_LIBS[$dep]+x}" ]; then
            continue
        fi
        # Resolved by a library inside the APK (SONAME match)?
        if [ -n "${SONAMES[$dep]+x}" ]; then
            continue
        fi
        # Resolved by a platform system library?
        if echo " $SYSTEM_LIBS " | grep -q " $dep "; then
            continue
        fi

        echo "::error::$name requires '$dep' — neither packaged in APK, nor a matching SONAME, nor a known system library"
        FAILED=1
    done < <("$READELF" -d "$so" 2>/dev/null | sed -n 's/.*(NEEDED).*\[\([^]]*\)\].*/\1/p')
done

echo
if [ "$FAILED" -ne 0 ]; then
    echo "::error::Native dependency closure INCOMPLETE. See unresolved DT_NEEDED above."
    exit 1
fi
echo "OK: every DT_NEEDED dependency resolves within the APK or to a system library."
exit 0
