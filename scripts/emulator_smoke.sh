#!/usr/bin/env bash
# [T-emulator-smoke] Real on-emulator smoke test for the OpenMinis fork.
# Runs INSIDE the android-emulator-runner (boot already complete).
# Produces artifacts/: screenshots, UI dumps, logcat, crash counters.
set -x
mkdir -p artifacts

APK=$(find . -name '*.apk' 2>/dev/null | head -1)
echo "APK=$APK"
if [ -z "$APK" ]; then echo "NO APK FOUND"; exit 1; fi

echo "=== INSTALL ==="
adb install -r "$APK" || exit 1

PKG="com.openminis.app.fork"

echo "=== LAUNCH (main / session list) ==="
adb shell am start -n "$PKG/com.openminis.app.MainActivity"
sleep 25
adb shell "pidof $PKG" > artifacts/pid_main.txt || true
adb exec-out screencap -p > artifacts/01-main.png
adb shell uiautomator dump /sdcard/ui_main.xml >/dev/null 2>&1 || true
adb pull /sdcard/ui_main.xml artifacts/ui_main.xml >/dev/null 2>&1 || true

echo "=== SWITCH APP LANGUAGE TO PERSIAN (RTL reproduction) ==="
cat > /tmp/fa_pref.xml <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="app_language">fa</string>
</map>
XML
adb push /tmp/fa_pref.xml /sdcard/fa_pref.xml >/dev/null
adb shell force-stop "$PKG" 2>/dev/null || adb shell am force-stop "$PKG"
adb shell run-as "$PKG" mkdir -p shared_prefs
adb shell run-as "$PKG" sh -c 'cat /sdcard/fa_pref.xml > shared_prefs/appearance_prefs.xml'
adb shell am start -n "$PKG/com.openminis.app.MainActivity"
sleep 20
adb exec-out screencap -p > artifacts/01b-main-fa.png
adb shell uiautomator dump /sdcard/ui_main_fa.xml >/dev/null 2>&1 || true
adb pull /sdcard/ui_main_fa.xml artifacts/ui_main_fa.xml >/dev/null 2>&1 || true

echo "=== DEEP LINK: smart browser ==="
adb shell am start -a android.intent.action.VIEW -d "minis://settings/smart-browser"
sleep 15
adb shell "pidof $PKG" > artifacts/pid_browser.txt || true
adb exec-out screencap -p > artifacts/02-browser.png
adb shell uiautomator dump /sdcard/ui_browser.xml >/dev/null 2>&1 || true
adb pull /sdcard/ui_browser.xml artifacts/ui_browser.xml >/dev/null 2>&1 || true
# RTL/Persian regression check: omnibox MUST be present in the tree.
if grep -q "جستجو یا آدرس" artifacts/ui_browser.xml 2>/dev/null; then
  echo "PASS: omnibox rendered (fa/RTL)" > artifacts/browser_fa_check.txt
else
  echo "FAIL: omnibox MISSING in fa/RTL — blank-screen bug reproduced" > artifacts/browser_fa_check.txt
fi

echo "=== BROWSER: type URL + go ==="
# Find the omnibox dynamically from the UI dump (tap its real center).
adb shell uiautomator dump /sdcard/ui_b.xml >/dev/null 2>&1 || true
adb pull /sdcard/ui_b.xml ui_b.xml >/dev/null 2>&1 || true
OMNIBOX_NODE=$(grep -o 'text="جستجو یا آدرس[^"]*" [^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' ui_b.xml | head -1)
if [ -n "$OMNIBOX_NODE" ]; then
  X1=$(echo "$OMNIBOX_NODE" | grep -o 'bounds="\[[0-9]*,[0-9]*\]' | head -1 | grep -o '[0-9]*' | sed -n 1p)
  Y1=$(echo "$OMNIBOX_NODE" | grep -o 'bounds="\[[0-9]*,[0-9]*\]' | head -1 | grep -o '[0-9]*' | sed -n 2p)
  X2=$(echo "$OMNIBOX_NODE" | grep -o '\]\[[0-9]*,[0-9]*\]"' | grep -o '[0-9]*' | sed -n 1p)
  Y2=$(echo "$OMNIBOX_NODE" | grep -o '\]\[[0-9]*,[0-9]*\]"' | grep -o '[0-9]*' | sed -n 2p)
  TX=$(( (X1 + X2) / 2 ))
  TY=$(( (Y1 + Y2) / 2 ))
  echo "tapping omnibox at $TX,$TY"
  adb shell input tap $TX $TY
else
  echo "omnibox not found in dump — fallback tap"
  adb shell input tap 160 300
fi
sleep 2
adb shell input text "example.com"
sleep 1
adb shell input keyevent 66
sleep 15
adb exec-out screencap -p > artifacts/03-browser-loaded.png
adb shell uiautomator dump /sdcard/ui_loaded.xml >/dev/null 2>&1 || true
adb pull /sdcard/ui_loaded.xml artifacts/ui_loaded.xml >/dev/null 2>&1 || true
# Navigation proof: example.com's real page title must appear in the UI tree.
if grep -q "Example Domain" artifacts/ui_loaded.xml 2>/dev/null; then
  echo "PASS: page title visible" > artifacts/nav_check.txt
else
  echo "FAIL: page title NOT found in UI dump" > artifacts/nav_check.txt
fi

echo "=== DEEP LINK: keep working settings ==="
adb shell am start -a android.intent.action.VIEW -d "minis://settings/keep-working"
sleep 8
adb exec-out screencap -p > artifacts/04-keepworking.png

echo "=== DEEP LINK: termux settings ==="
adb shell am start -a android.intent.action.VIEW -d "minis://settings/termux"
sleep 8
adb exec-out screencap -p > artifacts/05-termux.png

echo "=== DEEP LINK: user profile ==="
adb shell am start -a android.intent.action.VIEW -d "minis://settings/profile"
sleep 8
adb exec-out screencap -p > artifacts/06-profile.png

echo "=== CRASH CHECK ==="
adb logcat -d > artifacts/logcat.txt || true
grep -c "FATAL EXCEPTION" artifacts/logcat.txt > artifacts/fatal_count.txt || echo 0 > artifacts/fatal_count.txt
grep "FATAL EXCEPTION" artifacts/logcat.txt | head -5 > artifacts/fatal_sample.txt || true

echo "=== DONE ==="
ls -la artifacts/
