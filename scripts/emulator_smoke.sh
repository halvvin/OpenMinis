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

echo "=== DEEP LINK: smart browser ==="
adb shell am start -a android.intent.action.VIEW -d "minis://settings/smart-browser"
sleep 15
adb shell "pidof $PKG" > artifacts/pid_browser.txt || true
adb exec-out screencap -p > artifacts/02-browser.png
adb shell uiautomator dump /sdcard/ui_browser.xml >/dev/null 2>&1 || true
adb pull /sdcard/ui_browser.xml artifacts/ui_browser.xml >/dev/null 2>&1 || true

echo "=== BROWSER: type URL + go ==="
adb shell input tap 540 420
sleep 2
adb shell input text "example.com"
sleep 1
adb shell input keyevent 66
sleep 12
adb exec-out screencap -p > artifacts/03-browser-loaded.png

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
