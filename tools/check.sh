#!/bin/bash
# Health check of a plugged-in dumb_phone, from the computer (costs the phone nothing).
# Run it after every install:  ./tools/check.sh        (several phones: ./tools/check.sh <transport id>)
T=${1:-$(adb devices -l | grep -o 'transport_id:[0-9]*' | head -1 | cut -d: -f2)}
[ -n "$T" ] || { echo "no phone plugged in"; exit 1; }
a() { timeout 30 adb -t "$T" shell "$@" | tr -d '\r'; }
ok=0; bad=0
pass() { echo "  ok    $1"; ok=$((ok+1)); }
fail() { echo "  FIX   $1"; bad=$((bad+1)); }

echo "$(a settings get global device_name) · home $(a dumpsys package dumb_phone.home | grep -m1 versionName | sed 's/.*=//')"

# crashes in the last 24 h (any app)
since=$(date -d '24 hours ago' '+%Y-%m-%d %H:%M')
crashes=$(a dumpsys dropbox --print data_app_crash 2>/dev/null | awk -v s="$since" '/^20[0-9][0-9]-/{t=$1" "$2} /^Process:/{if (t>=s) print $2}' | sort | uniq -c)
[ -z "$crashes" ] && pass "no app crashes in 24 h" || fail "crashes in 24 h: $(echo $crashes)"
anr=$(a dumpsys dropbox --print data_app_anr 2>/dev/null | awk -v s="$since" '/^20[0-9][0-9]-/{t=$1" "$2} /^Process:/{if (t>=s) print $2}' | sort -u)
[ -z "$anr" ] && pass "no frozen apps in 24 h" || fail "froze in 24 h: $(echo $anr)"

# every app the home screen relies on can still open
for p in dumb_phone.phone dumb_phone.radio dumb_phone.podcasts dumb_phone.snake dumb_phone.dumbtrains org.oxycblt.auxio com.whatsapp org.fossify.messages com.omgodse.notally app.organicmaps mark.via; do
    a pm list packages "$p" | grep -qx "package:$p" || continue
    a cmd package resolve-activity -c android.intent.category.LAUNCHER -a android.intent.action.MAIN "$p" | grep -q "name=" \
        && pass "$p opens" || fail "$p can't open (launcher entry gone)"
done

# roles and services
sms=$(a settings get secure sms_default_application)
[ "$sms" = org.fossify.messages ] && pass "texting app = Fossify Messages" || fail "texting app = $sms"
dial=$(a telecom get-default-dialer)
{ [ "$dial" = null ] || [ "$dial" = com.android.dialer ]; } && pass "calls use the phone's own call screen" || fail "default dialer = $dial (other call apps miss calls on this phone)"
a pm list packages com.android.dialer | grep -q . && pass "stock call screen installed" || fail "stock call screen missing (cmd package install-existing com.android.dialer)"
[ "$(a settings get system vibrate_when_ringing)" = 1 ] && pass "calls vibrate" || fail "vibrate when ringing is off"
ime=$(a settings get secure default_input_method)
case "$ime" in dumb_phone.keypad/*) pass "keyboard = dumb_phone" ;; *) fail "keyboard = $ime (ime set dumb_phone.keypad/.KeypadService)" ;; esac
[ "$(a dumpsys accessibility | grep -c 'dumb_phone keys')" -gt 0 ] && pass "keys service running" || fail "keys service not running (rebind it)"
[ "$(a cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN | grep -m1 packageName | sed 's/.*=//')" = dumb_phone.home ] \
    && pass "dumb_phone is the home screen" || fail "home screen is not dumb_phone (set-home-activity)"
a dumpsys alarm | grep -q "dumb_phone.home/.WhatsAppCheck\|WhatsAppCheck" && pass "whatsapp checks armed" || fail "whatsapp checks not armed"
next=$(a dumpsys alarm | grep -A1 'tag=\*walarm\*:alarm$' | grep -m1 -o 'when=[0-9-]* [0-9:]*' | sed 's/when=//')
[ -n "$next" ] && pass "next alarm armed: $next" || echo "  --    no alarm armed (fine if you have none set)"

# Fossify apps: at least one launcher entry enabled (they switch all off if confused)
for p in org.fossify.messages; do
    a pm list packages "$p" | grep -q . || continue
    n=$(a dumpsys package "$p" | sed -n '/disabledComponents:/,/^ *[a-zA-Z]*:$/p' | grep -c 'SplashActivity\.')
    [ "$n" -lt 19 ] && pass "$p has a launcher entry ($n of 19 colours off)" || fail "$p: all launcher entries off"
done

# the gallery = only your own photos (WhatsApp hidden by .nomedia, music covers marked non-image)
other=$(a "content query --uri content://media/external/images/media --projection _data" | grep -c -E "/WhatsApp/|/Music/")
if [ "${other:-0}" -eq 0 ]; then pass "gallery shows only your photos"; else
    a "content update --uri content://media/external/file --bind media_type:i:0 --where \"(_data LIKE '/storage/emulated/0/Music/%' OR _data LIKE '/storage/emulated/0/WhatsApp/%') AND media_type=1\"" >/dev/null 2>&1
    pass "gallery: hid $other WhatsApp / album-cover pictures again"; fi

mem=$(a cat /proc/meminfo | awk '/MemAvailable/{print int($2/1024)}')
[ "${mem:-0}" -gt 100 ] && pass "memory free: ${mem} MB" || fail "memory low: ${mem} MB"
echo; echo "$ok ok, $bad to fix"
[ "$bad" -eq 0 ]
