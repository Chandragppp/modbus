#!/usr/bin/env bash
# Dijalankan di CI saat emulator sudah menyala:
# 1) screenshot layar Koneksi, Baca/Tulis (mode demo=1), Monitor, dan Pro/Paywall (terang/gelap, Inggris & Indonesia)
# 2) tes UI instrumentasi + screenshot per skenario.
set -euo pipefail

OUT=screenshots
PKG=id.controlengineer.modbus
mkdir -p "$OUT"

adb install -r "$(ls app/build/outputs/apk/debug/*.apk | head -1)"
adb install -r "$(ls app/build/outputs/apk/androidTest/debug/*.apk | head -1)"

adb shell svc power stayon true
sleep 30
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard || true

close_dialogs() {
    adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null || true
    sleep 1
}

shot_tabs() { # $1 = akhiran nama file (night_no, night_yes, id)
    # Tab 0 (Koneksi), Tab 1 (Baca/Tulis mode demo), Tab 2 (Monitor mode demo), Tab 3 (Pro terkunci), Tab 4 (Tentang)
    for tab in 0 1 2 3 4; do
        adb shell am force-stop "$PKG"
        adb shell am start -W -n "$PKG/.MainActivity" --ei tab "$tab" --ei demo 1 --es pro off
        sleep 4
        close_dialogs
        adb exec-out screencap -p > "$OUT/tab${tab}-free-$1.png"
    done
    # Tab 3 (Pro terbuka: Subtab 0 Pemindai & Subtab 1 Tren/CSV)
    for subtab in 0 1; do
        adb shell am force-stop "$PKG"
        adb shell am start -W -n "$PKG/.MainActivity" --ei tab 3 --ei subtab "$subtab" --ei demo 1 --es pro on
        sleep 4
        close_dialogs
        adb exec-out screencap -p > "$OUT/tab3-sub${subtab}-pro-$1.png"
    done
    # Paywall di setiap mode (terang/gelap/ID)
    adb shell am force-stop "$PKG"
    adb shell am start -W -n "$PKG/.MainActivity" --ei tab 3 --ei demo 1 --es pro off --ez paywall true
    sleep 4
    close_dialogs
    adb exec-out screencap -p > "$OUT/paywall-$1.png"
}

for night in no yes; do
    adb shell cmd uimode night "$night"
    shot_tabs "night_${night}"
done
adb shell cmd uimode night no

if adb shell cmd locale set-app-locales "$PKG" --locales id-ID; then
    shot_tabs "id"
    adb shell cmd locale set-app-locales "$PKG" --locales en-US || true
else
    echo "set-app-locales gagal; screenshot Bahasa Indonesia dilewati"
fi

# Screenshot tambahan dialog sambutan trial
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity" --ei tab 0 --ei demo 1 --es trial fresh --ez welcome true
sleep 4
close_dialogs
adb exec-out screencap -p > "$OUT/trial-welcome.png"

# Tes UI instrumentasi
adb shell am force-stop "$PKG"
adb shell am instrument -w -r "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee "$OUT/ui-tests.txt"

mkdir -p "$OUT/ui"
adb exec-out run-as "$PKG" tar cf - files/shots | tar xf - -C "$OUT/ui" || echo "tidak ada screenshot tes UI"

adb logcat -d -s AndroidRuntime:E > "$OUT/crash-log.txt" || true
ls -laR "$OUT"

if grep -q "FAILURES!!!" "$OUT/ui-tests.txt" || ! grep -q "^OK (" "$OUT/ui-tests.txt"; then
    echo "Tes UI GAGAL"
    exit 1
fi
