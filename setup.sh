#!/bin/bash
# Install dumb_phone on an Android phone plugged in over USB (with USB debugging on).
#
#   ./setup.sh               install everything: the dumb_phone apps, the green T9 keyboard, the companion
#                            apps from F-Droid (messages, call screen, notes), the voice model, and switch it all on
#   ./setup.sh --no-voice    skip the 40 MB offline speech model
#   ./setup.sh --no-extras   only the dumb_phone apps (no keyboard / companion apps)
#   ./setup.sh --maps        also install Organic Maps (offline maps, 60 MB)
#   ./setup.sh --undo        remove the dumb_phone apps (the phone's own home screen comes back)
#
# Uses the APKs in dist/ (from the release download, or ./build.sh). Needs adb, curl, unzip, python3.
set -euo pipefail
cd "$(dirname "$0")"

VOICE=1 EXTRAS=1 MAPS=0 OLD_HOMES=""
for arg in "$@"; do
    case "$arg" in
        --no-voice) VOICE=0 ;;
        --no-extras) EXTRAS=0 ;;
        --maps) MAPS=1 ;;
        --undo)
            for p in dumb_phone.home dumb_phone.phone dumb_phone.radio dumb_phone.podcasts dumb_phone.snake dumb_phone.dumbtrains dumb_phone.keypad org.fossify.thankyou; do adb uninstall $p >/dev/null 2>&1 && echo "removed $p" || true; done
            echo "done. If the phone asks which home screen to use, pick its own one."
            echo "(The keyboard and the F-Droid apps stay; remove them in Settings > Apps if you like.)"
            exit 0 ;;
        *) echo "unknown option $arg"; exit 1 ;;
    esac
done

say() { printf '\n\033[1;32m» %s\033[0m\n' "$*"; }
a() { timeout 60 adb "$@"; }

command -v adb >/dev/null || { echo "adb is missing. Install it: Debian/Ubuntu 'sudo apt install adb', macOS 'brew install android-platform-tools', Windows: Android SDK Platform Tools."; exit 1; }

say "looking for the phone"
state=$(adb get-state 2>&1 || true)
if [ "$state" != device ]; then
    echo "No phone found ($state)."
    echo "1. On the phone: Settings > About phone > tap 'Build number' 7 times."
    echo "2. Settings > System > Developer options > turn on 'USB debugging'."
    echo "3. Plug it in, unlock it and tap 'Allow' on the USB debugging question. Then run this again."
    exit 1
fi
sdk=$(a shell getprop ro.build.version.sdk | tr -d '\r')
model=$(a shell getprop ro.product.model | tr -d '\r')
abi=$(a shell getprop ro.product.cpu.abi | tr -d '\r')
echo "found: $model (Android API $sdk, $abi)"
[ "$sdk" -ge 23 ] || { echo "dumb_phone needs Android 6.0 or newer."; exit 1; }

say "installing the apps"
for app in keypad home phone radio podcasts snake dumbtrains themebridge; do   # keypad before home (home uses its permission)
    f=dist/dumb_phone-$app.apk
    [ -f "$f" ] || { echo "$f is missing: download the release APKs into dist/ or run ./build.sh"; exit 1; }
    a install -r "$f" | tail -1 | sed "s/^/  $app: /"
done
# The dumb_phone keyboard: multi-tap with word suggestions, no internet. Hold # on it to switch keyboards.
a shell ime enable dumb_phone.keypad/.KeypadService >/dev/null && a shell ime set dumb_phone.keypad/.KeypadService >/dev/null && echo "  keyboard: dumb_phone"

installed() { a shell pm list packages "$1" | tr -d '\r' | grep -q -x "package:$1"; }

# Newest F-Droid build of a package (only installed when missing: your own copy is left alone).
fdroid() {
    local pkg=$1 name=$2 v
    if installed "$pkg"; then echo "  $name: already on the phone"; return; fi
    v=$(curl -fsS "https://f-droid.org/api/v1/packages/$pkg" | python3 -c 'import json,sys; print(json.load(sys.stdin)["suggestedVersionCode"])') \
        || { echo "  $name: couldn't reach F-Droid, skipped"; return; }
    mkdir -p cache
    [ -f "cache/${pkg}_$v.apk" ] || curl -fL --progress-bar -o "cache/${pkg}_$v.apk" "https://f-droid.org/repo/${pkg}_$v.apk"
    timeout 600 adb install -r "cache/${pkg}_$v.apk" | tail -1 | sed "s/^/  $name: /"
}

if [ $EXTRAS = 1 ]; then
    say "companion apps (dark, keypad-friendly, from F-Droid)"
    fdroid org.fossify.messages "Fossify Messages (texts)"
    # NOT Fossify Phone: on the Opel TouchFlip it crashes on every incoming call (needs READ_PRECISE_PHONE_STATE,
    # a system-only permission), so calls go straight to voicemail. The call screen stays the phone's own.
    fdroid com.omgodse.notally "Notally (notes)"
    if [ $MAPS = 1 ]; then fdroid app.organicmaps "Organic Maps (offline maps)"; fi
    T9=io.github.sspanak.tt9
    if a install -r dist/dumb_phone-keyboard.apk >/dev/null 2>&1; then echo "  green T9 keyboard: installed"
    else echo "  T9 keyboard: you already have one from F-Droid/Play (signed differently), kept it"; fi

    say "making them the defaults"
    for p in READ_SMS SEND_SMS RECEIVE_SMS RECEIVE_MMS READ_CONTACTS READ_PHONE_STATE; do a shell pm grant org.fossify.messages android.permission.$p 2>/dev/null || true; done
    if installed org.fossify.messages; then a shell settings put secure sms_default_application org.fossify.messages; echo "  texts: Fossify Messages"; fi
    if installed $T9; then
        a shell ime enable $T9/.ime.TraditionalT9 >/dev/null && echo "  T9 keyboard: kept as a spare (hold # to switch)"
        a shell pm grant $T9 android.permission.RECORD_AUDIO 2>/dev/null || true
    fi
fi

# Some keypad phones (e.g. Opel TouchFlip) have their system bar app play a loud sound on plug /
# unplug (on the music channel, ignoring vibrate). Stop that app from playing audio; dumb_phone plays
# its own soft chime instead, only when the ringer is on normal.
a shell appops set com.android.systemui PLAY_AUDIO ignore 2>/dev/null || true
a shell settings put global charging_sounds_enabled 0 2>/dev/null || true
a shell settings put system vibrate_when_ringing 1 2>/dev/null || true   # calls ring AND vibrate (our CallRing plays the sound)
# The gallery shows only your own photos: hide WhatsApp's pictures / videos from it (files stay, WhatsApp
# still shows them). A .nomedia file at the top of its media folder, registered in the media index so
# Android hides the ones already listed too.
if a shell ls /sdcard/WhatsApp/Media >/dev/null 2>&1; then
    a shell touch /sdcard/WhatsApp/Media/.nomedia
    a shell "content insert --uri content://media/external/file --bind _data:s:/storage/emulated/0/WhatsApp/Media/.nomedia" >/dev/null 2>&1 || true
fi
# ...and album covers in Music folders aren't photos either (marked non-image in the index; songs unaffected)
a shell "content update --uri content://media/external/file --bind media_type:i:0 --where \"_data LIKE '/storage/emulated/0/Music/%' AND media_type=1\"" >/dev/null 2>&1 || true
# Calls MUST use the phone's own call screen: on the Opel TouchFlip every other call app (Fossify Phone,
# Simple Dialer) crashes on incoming calls (READ_PRECISE_PHONE_STATE), and if the stock one is missing
# calls ring unseen and go to voicemail. Put it back if it was removed, and let Android use it.
a shell cmd package install-existing com.android.dialer >/dev/null 2>&1 || true
for d in com.simplemobiletools.dialer org.fossify.phone; do
    if installed $d; then a shell pm disable-user --user 0 $d >/dev/null 2>&1; echo "  calls: $d switched off (it misses calls on this phone)"; fi
done

# Some keypad phones (e.g. Opel TouchFlip) draw text larger when the text size was never set.
if [ "$(a shell settings get system font_scale | tr -d '\r')" = null ]; then a shell settings put system font_scale 1.0; fi

say "giving permissions"
for p in READ_CONTACTS WRITE_CONTACTS READ_CALL_LOG CALL_PHONE; do a shell pm grant dumb_phone.phone android.permission.$p 2>/dev/null || true; done
a shell pm grant dumb_phone.home android.permission.RECORD_AUDIO 2>/dev/null || true
a shell appops set dumb_phone.home GET_USAGE_STATS allow 2>/dev/null || true      # for "Screen time"
a shell cmd notification allow_listener dumb_phone.home/dumb_phone.home.Notifications 2>/dev/null || true   # notification count + panel
a shell settings put secure enabled_notification_listeners dumb_phone.home/dumb_phone.home.Notifications 2>/dev/null || true
a shell appops set dumb_phone.home WRITE_SETTINGS allow 2>/dev/null || true      # panel: brightness
a shell pm grant dumb_phone.home android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || true   # panel: weather
a shell cmd notification allow_dnd dumb_phone.home 2>/dev/null || true           # panel: ringer silent
# lets the home screen switch its key service back on if Android ever drops it
a shell pm grant dumb_phone.home android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || echo "  (couldn't grant WRITE_SECURE_SETTINGS: the guard is off, everything else works)"

if [ $VOICE = 1 ]; then
    say "voice model (offline speech to text, English)"
    M=vosk-model-small-en-us-0.15
    mkdir -p cache
    if [ ! -d cache/$M ]; then
        curl -fL --progress-bar -o cache/$M.zip https://alphacephei.com/vosk/models/$M.zip
        (cd cache && unzip -q $M.zip && rm $M.zip)
    fi
    D=/sdcard/Android/data/dumb_phone.home/files
    a shell "mkdir -p $D && rm -rf $D/vosk-model"
    timeout 600 adb push cache/$M $D/vosk-model >/dev/null
    echo "  sent"
fi

say "making dumb_phone the home screen"
home_ok() { a shell cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN 2>/dev/null | grep -q 'name=dumb_phone.home.HomeActivity'; }
for try in 1 2 3; do
    a shell cmd package set-home-activity dumb_phone.home/.HomeActivity >/dev/null 2>&1 || true
    sleep 1
    home_ok && break
done
home_ok && echo "  done" || echo "  couldn't set it automatically: press the home key and choose 'Home' / 'Always'"

# The phone's own launcher can stay open behind ours and wipe ours away (the Opel TouchFlip's stock
# launcher removes other home screens whenever it comes back, e.g. when the flip closes). Closing it
# once removes its leftover screen for good (it survives restarts); its background parts restart.
for h in $(a shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null \
           | tr -d '\r' | sed -n 's/^ *packageName=//p' | sort -u); do
    case "$h" in dumb_phone.home|com.android.settings) continue ;; esac
    a shell am force-stop "$h"
    OLD_HOMES="$OLD_HOMES $h"
    echo "  closed the old home screen ($h)"
done
# that stock launcher also runs the phone's status bar service: start it again
a shell am startservice -n gwin.com.firefox/.statusbar.StatusBarService >/dev/null 2>&1 || true

say "turning on the key service (volume bar, voice key, green key)"
S=dumb_phone.home/dumb_phone.home.VolumeService
cur=$(a shell settings get secure enabled_accessibility_services | tr -d '\r')
[ "$cur" = null ] && cur=""
rest=$(echo "$cur" | tr ':' '\n' | grep -v -x "$S" | paste -sd: - || true)
# off first, then on, so a reinstall rebinds it
if [ -n "$rest" ]; then a shell settings put secure enabled_accessibility_services "$rest"; else a shell settings delete secure enabled_accessibility_services >/dev/null; fi
sleep 1
a shell settings put secure enabled_accessibility_services "${rest:+$rest:}$S"
a shell settings put secure accessibility_enabled 1
sleep 2
if a shell dumpsys accessibility | grep -q 'dumb_phone keys'; then echo "  on"; else
    echo "  not confirmed: on the phone open Settings > Accessibility > dumb_phone keys and switch it on"; fi

# the sound library for the Sounds app (ringtone / alarm / message): copied once, then scanned by Android
if [ -d sounds-private ] || [ -d sounds ]; then
    d=sounds-private; [ -d "$d" ] || d=sounds
    say "copying the sound library"
    a shell mkdir -p /sdcard/Ringtones/dumb_phone
    a push "$d/." /sdcard/Ringtones/dumb_phone/ | tail -1 | sed 's/^/  /'
    for f in "$d"/*.ogg; do a shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Ringtones/dumb_phone/$(basename "$f")" >/dev/null; done
fi
a shell pm grant dumb_phone.home android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
a shell pm grant dumb_phone.home android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true

# Fossify apps follow our theme through the theme bridge, but Android only grants them its permission
# if they are (re)installed after it: reinstall each one over itself (keeps its data and settings)
say "letting Fossify apps follow the theme"
for p in $(a shell pm list packages org.fossify. | tr -d '\r' | sed 's/package://' | grep -v thankyou); do
    if a shell dumpsys package "$p" | grep -q 'WRITE_GLOBAL_SETTINGS: granted=true'; then echo "  $p: ok"; continue; fi
    ap=$(a shell pm path "$p" | head -1 | sed 's/package://' | tr -d '\r')
    tmp=$(mktemp --suffix=.apk)
    a pull "$ap" "$tmp" >/dev/null && a install -r "$tmp" | tail -1 | sed "s/^/  $p: /"
    rm -f "$tmp"
    a shell am broadcast -a org.fossify.android.GLOBAL_CONFIG_UPDATED -p "$p" -f 0x20 >/dev/null 2>&1 || true
done

# once more at the end: if Home pointed at the old launcher for a moment, it may have reopened
home_ok || a shell cmd package set-home-activity dumb_phone.home/.HomeActivity >/dev/null 2>&1 || true
for h in $OLD_HOMES; do a shell am force-stop "$h"; done
a shell am startservice -n gwin.com.firefox/.statusbar.StatusBarService >/dev/null 2>&1 || true
a shell input keyevent HOME
say "done"
cat <<'EOF'
On the phone:
  - # or menu (on the home screen) = all apps.  "Themes" there changes the colours.
  - Voice typing: in any text box, hold the voice key (F9, the side button), talk, let go.
    No F9 button? On the home screen hold # , then press the button you want.
  - Radio: "+ add a station" at the top searches stations worldwide.  Hold a station to remove it.
  - Podcasts: "+ add a podcast" searches Apple's podcast directory.
EOF
if [ $EXTRAS = 1 ]; then
    echo
    echo "Last step, about 3 minutes on the phone (Android doesn't let a computer change these):"
    echo "README.md > 'Finish on the phone': keyboard options, and the green colours for Messages and Phone."
fi
echo "To undo: ./setup.sh --undo"
