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
adb exec-out run-as com.solartracker.pro tar -cf - -C files live-test > live-test-output/live-test.tar || true
tar -xf live-test-output/live-test.tar -C live-test-output/ || true
adb logcat -d -s LiveSolarTest:I UpdateTest:I EnergyCenterTest:I ToolsTest:I > live-test-output/logcat.txt || true
echo "===== Live Solar test log (first/last lines) ====="
head -n 40 live-test-output/logcat.txt || true
echo "..."
tail -n 60 live-test-output/logcat.txt || true
grep -c "STATE " live-test-output/logcat.txt || true
exit $status
