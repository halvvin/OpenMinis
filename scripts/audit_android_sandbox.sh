#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="$ROOT/src/android/app"
J="$APP/src/main/jniLibs/arm64-v8a"
fail=0

echo "== OpenMinis Android sandbox audit =="
if [[ ! -d "$ROOT/deps/proot/src" ]]; then
  echo "[FAIL] deps/proot submodule is not initialized"
  echo "       git submodule update --init --recursive"
  fail=1
else
  echo "[PASS] deps/proot source present"
fi

for f in "$J/libproot.so" "$J/libproot-loader.so" "$J/libtalloc.so"; do
  if [[ -f "$f" ]]; then echo "[PASS] $(basename "$f")"; else echo "[FAIL] missing $(basename "$f")"; fail=1; fi
done

if command -v readelf >/dev/null 2>&1 && [[ -f "$J/libproot.so" ]]; then
  while IFS= read -r dep; do
    case "$dep" in
      libc.so|liblog.so|libm.so|libdl.so|libc++_shared.so|libandroid.so|libz.so) continue;;
    esac
    if [[ ! -f "$J/$dep" && "$dep" != "libtalloc.so.2" ]]; then
      echo "[FAIL] libproot.so requires missing $dep"
      fail=1
    fi
  done < <(readelf -d "$J/libproot.so" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')
fi

if [[ -f "$J/libproot.so" ]]; then
  echo "-- DT_NEEDED --"
  readelf -d "$J/libproot.so" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/  \1/p'
fi

if (( fail != 0 )); then
  echo "RESULT: FAIL"
  exit 1
fi
echo "RESULT: PASS"
