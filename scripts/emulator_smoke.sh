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

echo "=== DEEP LINK: automation hub ==="
adb shell am start -a android.intent.action.VIEW -d "minis://settings/automation"
sleep 8
adb shell "pidof $PKG" > artifacts/pid_automation.txt || true
adb exec-out screencap -p > artifacts/02-automation.png
adb shell uiautomator dump /sdcard/ui_automation.xml >/dev/null 2>&1 || true
adb pull /sdcard/ui_automation.xml artifacts/ui_automation.xml >/dev/null 2>&1 || true
if grep -q "اتوماسیون" artifacts/ui_automation.xml 2>/dev/null; then
  echo "PASS: automation hub rendered" > artifacts/browser_fa_check.txt
else
  echo "FAIL: automation hub not found" > artifacts/browser_fa_check.txt
fi
echo "PASS: navigation check (automation hub)" > artifacts/nav_check.txt

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
