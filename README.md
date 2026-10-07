# dumb_phone

Apps that make an old Android flip or keypad phone good to use again. Green on black, built for the
keypad and D-pad (touch works too). Light enough for a 512 MB phone, and no Google needed.

I made these for my own flip phone and use it every day. Here is what you get.

| app | what it does |
|---|---|
| **Home** | A 3x3 tile home screen with a big clock, battery, Wi-Fi, signal and ringer. Number keys start dialling. `#` or the menu key opens a plain list of all your apps. |
| **dumb phone** | Calls and contacts. Recent calls, contacts with T9 search (type `5-2-7` for "Jas…"), a dial pad, and a page per person to call, text or edit. |
| **dumb radio** | Internet radio, sorted by genre. `+ add a station` searches 50,000+ stations worldwide ([radio-browser.info](https://www.radio-browser.info)). `*` favourites, `0` picks a random one, hold a station to remove it. Shows the song title when the station sends it. |
| **dumb podcasts** | Opens in under a second. `+ add a podcast` searches Apple's directory. Remembers where you stopped. `1` skips back 15s, `3` forward 30s. |
| **dumb aus trains** | Train times with no setup and no API key. See the Trains section below. |
| **dumb weather** | Weather anywhere in the world, no key. See the Weather section below. |
| **dumb alarms / timer / sounds / screen time / themes** | A simple alarm clock and timer, a sound picker, a screen-time view, and the theme switcher. |
| **snake** | The old Nokia-style Snake, in your theme. D-pad or `2 4 6 8` to steer, `OK` or `5` to pause. It speeds up as it grows and keeps your best score. |
| **keyboard** | Normal multi-tap typing (press `2` three times for "c") with word suggestions on one thin line. No on-screen buttons, no internet permission, and it learns the words you use. |

`setup.sh` can also install a few other apps from [F-Droid](https://f-droid.org) to round things out:
[Fossify Messages](https://github.com/FossifyOrg/Messages) for texts, [Notally](https://github.com/OmGodse/Notally)
for notes, and [Organic Maps](https://organicmaps.app) with `--maps`.

**Voice typing** is built in. In any text box, hold the voice key, talk, and let go. The words drop
into the box. It runs on the phone itself (the small [Vosk](https://alphacephei.com/vosk/) English
model), so it works with no signal and nothing is sent anywhere.

## Will it work on my phone?

You need **Android 6.0 or newer**. The phone must run Android, not KaiOS. Check in Settings > About
phone. The apps are built for small keypad screens (240x320) but they scale up fine.

| phone | status |
|---|---|
| Opel Mobile TouchFlip (Android 8.1 Go) | tested, daily use |
| Other Android keypad / flip phones (CAT S22 Flip, Sonim XP3plus, Kyocera DuraXV Extreme, Qin F21/F22 Pro, and similar) | should work, not tested yet. Let me know how it goes. |
| Normal touchscreen Android phones | work, but the apps are made for keys |
| KaiOS phones (Nokia 2780 / 2760 Flip, Alcatel Go Flip, etc.) | no. KaiOS can't run Android apps. |

A couple of things differ between phones:
- **The voice key** is `F9` by default, which is the side or back button on a lot of these phones.
  If yours doesn't have one, hold `#` on the home screen and press the button you want to use.
- **The camera, maps, messages and web tiles** open whatever your phone has. For the best result,
  install Fossify Messages, Organic Maps, Notally and the Via browser from F-Droid.

## Install (about 5 minutes)

You need a computer (Linux, macOS, or Windows with Git Bash or WSL) and the phone's USB cable.

### Step 1: Put the phone in developer mode

Android hides a "developer mode" that lets a computer install apps. You turn it on once:

1. On the phone, open **Settings** and find **About phone** (it may be under *System* first, or at
   the bottom of the Settings list).
2. Find the line called **Build number**. Tap it **7 times** in a row. After a few taps it counts
   down ("you are N taps away"), then says **"You are now a developer"**. If it asks for your PIN,
   enter it.
3. Go back to Settings. There is now a **Developer options** menu (under *System*, or near the
   bottom of Settings). Open it.
4. Scroll down and switch on **USB debugging**. Tap **OK** on the warning.

That's it. The phone is ready.

### Step 2: Install adb on the computer

adb is the small tool that talks to the phone over USB.

- **Debian / Ubuntu / Raspberry Pi:** `sudo apt install adb`
- **macOS:** `brew install android-platform-tools`
- **Windows:** download [SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools),
  unzip it, and run the commands from inside that folder (or add it to your PATH).

### Step 3: Install dumb_phone

1. Download the latest release zip from the **Releases** page and unzip it.
2. **Plug the phone into the computer** with the USB cable, and unlock the phone's screen.
3. The phone shows **"Allow USB debugging?"** with a long code. Tick **Always allow from this
   computer**, then tap **OK** or **Allow**. (If you don't see it, unplug and plug back in.)
4. Open a terminal in the unzipped folder and run:

   ```
   ./setup.sh
   ```

   (On Windows, run it from Git Bash.) To check the phone is connected first, run `adb devices`:
   you should see one device listed, not "unauthorized".

The script installs the apps, grants their permissions, downloads the 40 MB voice model and copies it
to the phone, makes dumb_phone the home screen, and switches on the key service. Skip the voice part
with `./setup.sh --no-voice`. Remove everything with `./setup.sh --undo`.

Want to do it by hand? Install the APKs, press home and choose *Home*, then Settings >
Accessibility > *dumb_phone keys* > on. For voice typing, copy the model folder
`vosk-model-small-en-us-0.15` to `Android/data/dumb_phone.home/files/vosk-model`.

## Finish on the phone (about 3 minutes)

`setup.sh` turns on everything it can. A few settings live inside other apps, and a computer isn't
allowed to change those, so do them once by hand.

**The keyboard** needs no setup. Its keys:

| key | does |
|---|---|
| `2`–`9` | letters. Press again within a second for the next one (`2` = a b c 2). Hold for the digit. |
| `0` | space. Hold for 0. |
| `1` | `. , ? ! ' " -` (press again for the next one). Hold for 1. |
| `*` | symbols: `@ / : ; ( ) & + = * # $ % _`. Hold to turn 123 mode on or off (every key types its digit). |
| `#` | abc → Abc → ABC. Hold to switch to another keyboard. |
| `←` `→` | while typing a word, pick a suggestion. `OK` uses it and adds a space. Otherwise they move the cursor and `OK` starts a new line. |
| back | deletes. Hold it to delete faster, then whole words after a couple of seconds. In an empty box it goes back as usual. |

Type `dont` and it suggests `don't`. A full stop right after a picked word lands where you'd want it.
Nothing you type leaves the phone, and the words it learns stay in the app.

**Fossify Messages** (if you install it): tap ⋮ > *Settings* > *Customize appearance*:
1. *Text color*: type `95EE62`, then *OK*. (To type digits, press `#` until the keyboard line shows `123`.)
2. *Background color*: `040605`.
3. *Primary color*: `9CCC65`. Don't pick black, or the Yes / No buttons on its dialogs disappear.
4. Tap ✓ to save.

## Trains

**dumb aus trains** needs no setup and no API key. It uses [Transitous](https://transitous.org), a
free, community-run trip planner that pools open public-transport timetable data from agencies around
the world. The app just asks Transitous for your trains, so there's nothing to sign up for.

It's built for **Australia** and works across the states. Open it, set your **state** at the top
(left/right or OK to change it) so your local stations come up first, then pick your two stations and
it shows the next trains with their times.

Good to know:
- Coverage depends on what timetable data Transitous has. Victoria, New South Wales and Perth
  (Transperth) all work. Some networks are timetable-only, so you get scheduled times but not live
  times or platform numbers.
- If it says "no train found", that usually means Transitous doesn't have that network's data yet,
  not that the app is broken.

Please keep your use light (it's a free community service): the app only fetches when you open it or
refresh.

## Weather

**dumb weather** works anywhere in the world with no setup and no key. It finds your rough location
on its own, or you can open the weather panel and tap **place ›** to type your own town. Forecasts
come from [Open-Meteo](https://open-meteo.com), which is free and keyless.

## Sounds

Ringtones, alarm and message sounds live in the **Sounds** app. The repo ships six that are free to
share (three from Google's Material sound kit, three from KDE's Ocean set, all Creative Commons; see
`sounds/README.md`). To add your own, drop an `.ogg` file in the `sounds/` folder before you run
`setup.sh`, or push it straight to the phone:

    adb push my-ringtone.ogg /sdcard/Ringtones/dumb_phone/

Then pick it in the Sounds app.

## Colour themes

Open all apps (`#`) and choose **dumb themes**. Move the highlight to preview each one live, `OK`
keeps it, back cancels. There are 56 themes: 18 I made (phosphor green is the default, plus amber,
nord, gruvbox, dracula, catppuccin, solarized, tokyo night, mono, everforest, rose pine, kanagawa,
monokai, one dark, ayu, crimson, and the light paper and latte) and 38 more converted from
[iTerm2-Color-Schemes](https://github.com/mbadolato/iTerm2-Color-Schemes) (MIT). They're all shown
side by side in `screenshots/themes-all.png`. To add one, drop a ghostty-format theme file in
`themes/` and run `python3 tools/themes.py`.

Fossify Messages follows the theme on its own (through the small theme bridge `setup.sh` installs).
The keyboard can't follow it live, so to match it build it with the theme baked in:
`DUMBPHONE_THEME=nord ./build.sh keyboard`, then `adb install -r dist/dumb_phone-keyboard.apk` (your
keyboard settings stay).

| theme | text | background | accent |
|---|---|---|---|
| phosphor | `95EE62` | `040605` | `FFB347` |
| amber | `FFB347` | `080502` | `FFE3A3` |
| nord | `88C0D0` | `2E3440` | `EBCB8B` |
| gruvbox | `B8BB26` | `1D2021` | `FE8019` |
| dracula | `BD93F9` | `21222C` | `FF79C6` |
| catppuccin | `89B4FA` | `1E1E2E` | `F9E2AF` |
| solarized | `2AA198` | `002B36` | `B58900` |
| tokyo night | `7AA2F7` | `1A1B26` | `E0AF68` |
| mono | `E6E6E6` | `000000` | `FFFFFF` |
| everforest | `A7C080` | `2D353B` | `DBBC7F` |
| rose pine | `EBBCBA` | `191724` | `F6C177` |
| kanagawa | `DCD7BA` | `1F1F28` | `FF9E3B` |
| monokai | `A6E22E` | `272822` | `F92672` |
| one dark | `61AFEF` | `282C34` | `E5C07B` |
| ayu | `39BAE6` | `0B0E14` | `FFB454` |
| crimson | `FF5555` | `070202` | `FFB3B3` |

## Keys

| where | keys |
|---|---|
| home | D-pad + OK open a tile. `0-9` or `*` dial. green = recent calls. `#` or menu = all apps |
| dumb phone | ← → tabs. type digits to search. OK = person. green = call |
| dumb radio | OK plays/stops or folds a genre. ← → fold/open. `*` favourite. `#` all/favourites. `0` random. hold = remove |
| dumb podcasts | OK open/play/pause. `1` −15s. `3` +30s. `*` played. `#` refresh. hold a show = remove |
| dumb aus trains | ← → change the time or state. OK = leave/arrive. `0` = now. `*` swap from/to. `#` refresh |
| anywhere | hold the voice key in a text box to speak. the volume keys show a themed volume bar. |

## Your own radio list

Radio starts with a set of hand-picked stations in 19 genres. Add more with `+ add a station`, or
load a whole list. The format is a CSV with genre lines (`Jazz,-`) followed by station lines
(`Name · City,https://stream-url`):

    adb push my-stations.csv /sdcard/Android/data/dumb_phone.radio/files/stations.csv

It replaces the list next time the radio opens. Podcasts can import an OPML file the same way
(`/sdcard/Android/data/dumb_phone.podcasts/files/feeds.opml`), used while you have no shows yet.

## Good to know

- **Memory.** The voice model stays loaded so voice typing starts right away. That's about 90 MB of
  RAM. On a 512 MB phone other apps may reload a bit more often. Use `--no-voice` if you'd rather not.
- **Staying on.** If Android ever switches the key service off, the home screen turns it back on next
  time you go home (it needs the permission `setup.sh` grants).
- **Privacy.** No accounts, no tracking. Radio search talks to radio-browser.info, podcast search to
  Apple's public directory, trains to Transitous, weather to Open-Meteo, and the apps fetch the
  streams and feeds you pick. That's all.

## Build it yourself

`./build.sh` builds every APK into `dist/` with plain javac, d8 and aapt (no Gradle). It needs a JDK,
`aapt zipalign apksigner`, and an `android.jar` (API 27 or newer). See the top of `build.sh`.

## Credits

Keyboard suggestions use the English (UK) word list from Android's own keyboard
([AOSP LatinIME](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/), Apache 2.0).
The spare green keyboard (`dist/dumb_phone-keyboard.apk`) is
[Traditional T9](https://github.com/sspanak/tt9) by Dimo Karaivanov (Apache 2.0) with only its
dark-theme colours changed. Fonts are [JetBrains Mono](https://www.jetbrains.com/lp/mono/) (OFL 1.1)
and a subset of [Nerd Fonts](https://www.nerdfonts.com) symbols (MIT). Speech is
[Vosk](https://alphacephei.com/vosk/) (Apache 2.0) via [JNA](https://github.com/java-native-access/jna)
(Apache 2.0). Station search is [radio-browser.info](https://www.radio-browser.info), train times are
[Transitous](https://transitous.org), weather is [Open-Meteo](https://open-meteo.com), all free and
community or openly run. The bundled sounds are credited in `sounds/README.md`. The two wallpapers
are public-domain medieval manuscript images from the British Library (Harley 3244 and Royal 12 F
xiii).
