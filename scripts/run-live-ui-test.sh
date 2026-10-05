#!/usr/bin/env bash
# Runs the Live Solar instrumented test on a connected device/emulator and collects its output
# (CSV of every observed second, screenshots, logcat) into ./live-test-output.
set -u
SECONDS_TO_WATCH="${LIVE_SECONDS:-180}"
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.liveSeconds="$SECONDS_TO_WATCH"
status=$?
mkdir -p live-test-output
adb pull /sdcard/Android/data/com.solartracker.pro/files/live-test live-test-output/ || true
adb logcat -d -s LiveSolarTest:I > live-test-output/logcat.txt || true
exit $status
