#!/bin/bash
# Build the dumb_phone apps from source (plain Java, no Gradle, no Android Studio).
#
#   ./build.sh            build all four APKs into dist/
#   ./build.sh home radio build only those
#
# Needs: a JDK (17 or 21), aapt, zipalign, apksigner (Debian/Ubuntu: apt install openjdk-21-jdk-headless
# aapt zipalign apksigner), plus android.jar (API 27 or newer) and r8.jar. Point at them with
#   ANDROID_JAR=/path/to/android.jar R8_JAR=/path/to/r8.jar ./build.sh
# If they aren't set, the script looks in $ANDROID_HOME/platforms/*/ and tools/ in this folder, and
# downloads r8 from Google's Maven repository the first time.
#
# Signing: uses $DUMBPHONE_KEYSTORE (+ $DUMBPHONE_KEYPASS) if set, otherwise makes keystore/local.jks
# the first time (keep it: updates must be signed with the same key).
set -euo pipefail
cd "$(dirname "$0")"
TOP=$(pwd)
mkdir -p tools dist

# ---- toolchain ----
JAR=${ANDROID_JAR:-}
if [ -z "$JAR" ]; then
    for c in tools/android.jar "${ANDROID_HOME:-/nonexistent}"/platforms/android-*/android.jar \
             ~/.local/share/android-build/android-*/android.jar /usr/lib/android-sdk/platforms/android-*/android.jar; do
        [ -f "$c" ] && JAR=$c
    done
fi
[ -f "${JAR:-}" ] || { echo "android.jar not found: set ANDROID_JAR (any API 27+ platform from the Android SDK)"; exit 1; }
D8=${R8_JAR:-tools/r8.jar}
if [ ! -f "$D8" ]; then
    for c in ~/.local/share/android-build/r8.jar; do [ -f "$c" ] && D8=$c; done
fi
if [ ! -f "$D8" ]; then
    echo "downloading r8 (the Android dexer)…"
    curl -fsSL -o tools/r8.jar https://dl.google.com/android/maven2/com/android/tools/r8/8.5.35/r8-8.5.35.jar
    D8=tools/r8.jar
fi
for t in javac aapt zipalign apksigner keytool curl unzip; do
    command -v $t >/dev/null || { echo "missing tool: $t"; exit 1; }
done

# ---- speech engine for the home app (Vosk + JNA from Maven Central, Apache 2.0) ----
fetch_libs() {
    local L="$TOP/home/libs"
    [ -f $L/vosk.jar ] && [ -f $L/jna.jar ] && [ -f $L/arm64-v8a/libvosk.so ] && return
    echo "downloading the speech engine libraries…"
    local T; T=$(mktemp -d)
    local M=https://repo1.maven.org/maven2
    curl -fsSL -o "$T/v.aar" $M/com/alphacephei/vosk-android/0.3.47/vosk-android-0.3.47.aar
    curl -fsSL -o "$T/j.aar" $M/net/java/dev/jna/jna/5.13.0/jna-5.13.0.aar
    (cd "$T" && mkdir v j && (cd v && unzip -q ../v.aar) && (cd j && unzip -q ../j.aar))
    mkdir -p $L/armeabi-v7a $L/arm64-v8a
    cp "$T/v/classes.jar" $L/vosk.jar
    cp "$T/j/classes.jar" $L/jna.jar
    for abi in armeabi-v7a arm64-v8a; do
        cp "$T/v/jni/$abi/libvosk.so" "$T/j/jni/$abi/libjnidispatch.so" $L/$abi/
    done
    rm -rf "$T"
}

# ---- signing key ----
KS=${DUMBPHONE_KEYSTORE:-$TOP/keystore/local.jks}
KP=${DUMBPHONE_KEYPASS:-dumb_phone-local}
if [ ! -f "$KS" ]; then
    echo "making a signing key in $KS (keep it for updates)"
    mkdir -p "$(dirname "$KS")"
    keytool -genkeypair -keystore "$KS" -storepass "$KP" -keypass "$KP" -alias dumb_phone -keyalg RSA -keysize 2048 \
        -validity 10000 -dname "CN=dumb_phone local build" >/dev/null 2>&1
fi

build() {
    local app=$1
    echo "== $app"
    cd "$TOP/$app"
    local B=build CP="" EXTRA=""
    rm -rf $B && mkdir -p $B/classes $B/dex assets
    cp "$TOP"/fonts/*.ttf assets/
    if [ "$app" = home ]; then
        fetch_libs
        CP="-cp libs/vosk.jar:libs/jna.jar"
        EXTRA="libs/vosk.jar libs/jna.jar"
    fi
    javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$JAR" $CP -d $B/classes $(find src -name '*.java')
    java -cp "$D8" com.android.tools.r8.D8 --release --min-api 23 --lib "$JAR" --output $B/dex $(find $B/classes -name '*.class') $EXTRA
    local RES=""
    [ -d res ] && RES="-S res"
    aapt package -f -M AndroidManifest.xml $RES -A assets -I "$JAR" -F $B/unsigned.apk
    (cd $B/dex && aapt add ../unsigned.apk classes*.dex >/dev/null)
    if [ "$app" = home ]; then
        mkdir -p $B/native/lib && cp -r libs/armeabi-v7a libs/arm64-v8a $B/native/lib/
        (cd $B/native && aapt add ../unsigned.apk lib/*/*.so >/dev/null)
    fi
    zipalign -f -p 4 $B/unsigned.apk $B/aligned.apk
    apksigner sign --ks "$KS" --ks-pass pass:"$KP" --key-pass pass:"$KP" --out "$TOP/dist/dumb_phone-$app.apk" $B/aligned.apk
    rm -f "$TOP/dist/dumb_phone-$app.apk.idsig"
    cd "$TOP"
}

# The green T9 keyboard: the official Traditional T9 (Apache 2.0) from F-Droid with its dark-theme
# colours swapped by recolor-tt9.py, then signed with our key.
keyboard() {
    echo "== keyboard"
    local v
    v=$(curl -fsS https://f-droid.org/api/v1/packages/io.github.sspanak.tt9 | python3 -c 'import json,sys; print(json.load(sys.stdin)["suggestedVersionCode"])')
    mkdir -p cache
    [ -f cache/tt9_$v.apk ] || curl -fsSL -o cache/tt9_$v.apk https://f-droid.org/repo/io.github.sspanak.tt9_$v.apk
    python3 recolor-tt9.py cache/tt9_$v.apk cache/tt9-green-unsigned.apk "${DUMBPHONE_THEME:-phosphor}"
    zipalign -f -p 4 cache/tt9-green-unsigned.apk cache/tt9-green-aligned.apk
    apksigner sign --ks "$KS" --ks-pass pass:"$KP" --key-pass pass:"$KP" --out dist/dumb_phone-keyboard.apk cache/tt9-green-aligned.apk
    rm -f dist/dumb_phone-keyboard.apk.idsig cache/tt9-green-*.apk
}

APPS=${*:-home phone radio podcasts snake dumbtrains keypad themebridge keyboard}
for a in $APPS; do if [ "$a" = keyboard ]; then keyboard; else build "$a"; fi; done
echo "done: $(ls dist/*.apk | tr '\n' ' ')"
echo "install them with ./setup.sh"
