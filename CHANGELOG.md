# Changelog

Version numbers: `major.minor.fix`. New features bump the middle number (1.1 → 1.2), bug fixes only
the last one (1.1 → 1.1.1).

## 1.8.6 (2026-10-06)
- Alarm/timer: the "ring on silent" / "vibrate on silent" toggles are now just "sound" and "vibrate",
  plain on/off that the alarm obeys whatever the phone's ringer mode is (silent, vibrate or normal).
  The "sound" row that picked the tone is now labelled "tone". (home versionCode 51)

## 1.8.5 (2026-10-06)
- Alarm "vibrate on silent" now covers both silent AND vibrate modes (the user: "silent" means silent or
  vibrate). Off = no vibration whenever the phone is quiet; on normal it always vibrates. (Matches the
  "ring on silent" toggle, which already used != NORMAL.)

## 1.8.4 (2026-10-06)
From the user's note:
- Podcasts: a stream that drops mid-episode no longer stops playback. RangeSource reopens the HTTP
  connection at the same byte (up to 6 tries, backing off), and on an early onCompletion / onError the
  service reopens the episode from where it was (retries reset per episode).
- Podcasts: new "↡ latest episodes" row at the top of the show list — the newest episode from every
  show, soonest first, each tagged with its show; ← / back returns. Loads shows not cached yet as they
  arrive. (podcasts versionCode 6 / 1.8.4)
- Podcasts: the now-playing bar (cover + progress) moved from the top to the bottom of the screen.
- Alarm: it now vibrates when the phone is on silent / vibrate / Do Not Disturb. The vibration is tagged
  USAGE_ALARM (a plain vibrate() is suppressed in those modes on this phone).

## 1.8.3 (2026-10-05)
From the user's note:
- Keyboard: while choosing a mark with 1 or *, the strip shows all of that key's marks with the
  current one as a solid block (KeypadService.plist).
- Weather: a saved forecast is brought up to date before it's shown (Weather.uptoNow, used by the
  home line, the panel and the weather page): days before today are dropped, so after midnight
  "today" / the day list no longer show yesterday until the next fetch; when the saved "current"
  reading is from an earlier hour, now = this hour's forecast. nowIndex falls back to the next hour,
  not the first saved one.
- After an update the keys service can show up as "Home" in dumpsys (label not resolved yet) while
  it works; rebinding fixes the name, and check.sh looks for "dumb_phone keys".

## 1.8.2 (2026-10-05)
From the user's note:
- Keyboard: holding back deletes like the iPhone: one letter, after 0.5 s letters (faster after 1.2 s),
  after ~2.2 s whole words. The keyboard times it itself (handler), also in WhatsApp: the keys service
  sends DELETE hold=down / hold=up instead of repeating (test hook DICTATE cmd keypaddel --es hold down|up).
- Keyboard: suggestions take turns in the theme's accent and text colours (each checked for contrast
  >= 4.5 on the background, else the text colour); the chosen one is a solid accent block.
- Home: messages and web tiles swapped (messages top right, web middle right).

## 1.8.1 (2026-10-05)
- Keyboard (dumb_phone.keypad versionCode 2): OK with no suggestion lit starts a new line (or does a
  one-line box's own action: search, next…), like T9 did. The user's note: "works great except the
  middle button doesn't press enter".

## 1.8 (2026-10-05)
- New: **the dumb_phone keyboard** (dumb_phone.keypad, "dumb_phone keyboard"). Normal multi-tap
  (2 2 2 = c, hold = digit) with up to four word suggestions on one thin line in your theme; ← → light
  one up, OK uses it + a space. 0 space, 1 punctuation, * symbols (hold = 123 mode), # abc/Abc/ABC
  (hold = keyboard list), back deletes (hold repeats; empty box = back as usual), ← → move the cursor
  when there's nothing to pick. 40,000 English (UK) words from Android's own keyboard (Apache 2.0),
  learns the words you finish (files/learned.txt), nothing in passwords. No internet permission, ~18 MB.
  Checked first: TyNi (Play only: licence check fails without Google Play), TT9 (suggestions only in
  predictive mode). TT9 stays installed as a spare.
- WhatsApp takes the back key before any keyboard sees it, so the keys service passes back on to our
  keyboard (broadcast dumb_phone.keypad.DELETE, signature permission dumb_phone.keypad.permission.KEYS;
  test hook DICTATE cmd keypaddel). ← → in WhatsApp now go to the keyboard. With another keyboard the
  old WhatsApp fix still works.
- setup.sh installs it (before home, for the permission) and makes it the keyboard; check.sh checks it.

## 1.7.32 (2026-10-05)
- Trains: "when" can be "leave 18:40" or "arrive by 18:40": OK on the when line flips between them (same
  time; on "now" it starts at arrive by in half an hour), ← → still move the time, 0 = now. Arrive-by
  shows the latest direct trains that get there in time, with "n min early", never one that has left.
- The user's phone is called dumb_phone everywhere: hotspot network name (Settings, same password),
  Bluetooth name (was still "Opel Mobile TouchFlip"; test hook DICTATE cmd btname --es name X),
  Wi-Fi Direct (settings global wifi_p2p_device_name); device name already was.
- Hotspot: switches itself off after 10 minutes with nothing connected (checked every 5 min, only while
  it's on). The phone's own "turn off when idle for 5 min" was already set and didn't work. Switching it
  off from the panel is now silent (no settings screen).

## 1.7.31 (2026-10-05)
- New app: **Trains** (PTV): from, to, when (now or ← → in 10-minute steps, 0 = now), * swaps the two
  stations, # refreshes. Shows the next five direct metro / V/Line trains: leave → arrive, "in n min",
  line, platform, travel time, "live" when PTV has real-time data. Only fetches when you open it (or
  press #; at most once a minute on its own) and remembers your stations. Station picker: type part of
  the name, ↓, OK (metro stations listed first). Journeys that need a change aren't planned (yet).
  The PTV key is read from private/ptv.txt at build time and never published.

## 1.7.30 (2026-10-05)
- WhatsApp: while typing, back deletes the letter before the cursor (hold = keeps deleting), ← → move the
  cursor; with an empty box back leaves the chat as before. Tested in WhatsApp's search box:
  "hello" -> delete -> ← ← -> delete = "hll".

## 1.7.29 (2026-10-05)
- Auxio set to phosphor (light green on black; it can't follow our themes), and its selection ring is
  phosphor green too.
- Checked: Radio, Podcasts and Auxio go quiet for calls (incoming and outgoing) and carry on afterwards.
  Tested with a fake call (test hook DICTATE cmd fakecall = takes audio focus like a ringing call for 8 s):
  radio silent then reconnected; Auxio paused 11:12:57.8, resumed 8 s later (player log).

## 1.7.28 (2026-10-05)
- Our Music app removed again: Auxio is the music player (the user prefers it). Its colour scheme set to
  pink on black to sit with the Dracula theme.
- Auxio works with the keys: an outline in the theme colour shows what's selected (it showed nothing);
  OK opens the row (Auxio's keys land on each row's ⋯ button), hold OK = that ⋯ menu; 5 play / pause,
  4 previous, 6 next (like pocket). Only runs after a key press in Auxio.

## 1.7.27 (2026-10-05)
- New app: **Music**, made to look like pocket's (cmus): a folding tree artist > album > tracks with
  times on the right, two filled now-playing bars (artist - title; ▶ time / length + progress) and the
  key hints on the last line. OK plays (the album from that track on), 5 pause, 4/6 prev/next, ← → fold,
  1 = album cover view (cover.jpg or the picture inside the song), 3 = jump to what's playing, * = shuffle
  all, # = find (T9). Plays in the background, pauses for calls and when headphones come out. Light:
  nothing runs while nothing plays; covers are decoded at screen size.
- All apps: purpose first, the app's name second, e.g. "music (auxio)", "maps (organic maps)".

## 1.7.26 (2026-10-05)
- EMERGENCY CALLS: the green key is never taken over on the phone's own dial / call screens. Before, it
  opened recents there, so green on the 000 dial screen would not have called. Contacts: dialling an
  emergency number opens the phone's dial screen with it typed in and says "press the green key to call"
  (apps may not call emergency numbers themselves).

## 1.7.25 (2026-10-05)
- Screen time: each bar shows its time above it (45m / 2h5), and a list under the chart gives every day
  of the week with its total; the chosen day (← →, or tap a bar or a line) is lit in both and drives
  the "by app" list.

## 1.7.24 (2026-10-05)
- Gallery shows only your own photos: WhatsApp's pictures and videos are hidden from it (a .nomedia at
  the top of /sdcard/WhatsApp/Media, registered in the media index so the 395 already listed went too).
  Files stay; WhatsApp still shows them in chats. setup.sh does this on every phone.
- Messages works with the keys again: ↓ from its search bar now moves onto the first conversation
  (Fossify's layout stopped the focus there). Test hook DICTATE cmd intolist.

## 1.7.23 (2026-10-05)
- Hold the red key (~0.7 s) = the power menu (turn off / restart), everywhere. On home with a photo, the
  red key used to be grabbed on press for photo mode, so the long press never reached Android; photo
  mode now toggles on release of a short press.

## 1.7.22 (2026-10-05)
- Health check on the home screen: if Messages, WhatsApp, Contacts, Radio, Podcasts, Notes or Maps
  can't open, or an app crashed in the last 24 h, an amber line under the phone's name says so
  ("messages can't open · plug into pocket"). Runs only when home shows, at most every 10 minutes
  (a few package lookups); crashes are noted from the "has stopped" screen the key service already sees.
- Home tiles open an app through its main screen when its launcher entry is gone, so you're never locked
  out of texts.
- tools/check.sh: full health check from pocket (crashes, every app opens, texting/call apps, key
  service, home screen, alarms, WhatsApp checks, memory). Run after every install.
- Fixed: Fossify Messages could stop opening (and crash on new texts) after a theme change. The theme
  bridge sent the theme's accent as the app-icon colour; Fossify then switched off all its launcher
  entries because none matched. The bridge now sends the nearest of Fossify's own 19 icon colours.
  (A broken Messages has to be reinstalled once; texts are kept by Android.)

## 1.7.21 (2026-10-04)
- Test hook to set the ringtone from the computer (DICTATE cmd setring, file=<library file>).

## 1.7.20 (2026-10-04)
- The ringtone player also takes over the phone's built-in ringtones (not only Sounds library tones),
  so calls ring even when Android's own ringer would go silent.
- setup.sh: the call-screen fix (stock call screen back, crashing call apps off) now always runs,
  also with --no-extras.

## 1.7.19 (2026-10-04)
- Missed calls: opening Contacts (or tapping the missed call in the panel) now also clears Android's
  own missed-call notice, so the back LED goes off. Uses the notice's own "clear" action (only the
  default phone app may clear it directly). Taking a later call no longer wipes an earlier missed call
  from the panel.

## 1.7.18 (2026-10-04)
- Screen time, improved: pickups today + longest stretch; "by app" for any of the last 7 days (← →,
  or tap the left / right half of its heading); ↑ ↓ scroll with the keys; battery shows "on battery
  since 09:12 · used 34%" (or "charging since"), and the 24 h chart shades when the screen was on;
  "[#] start counting over" at the bottom (press twice).
- Screen time "whatsapp checks": how many background checks ran today, time awake, new messages
  they fetched and battery used (from files/wa.log).

## 1.7.14 (2026-10-04)
- **WhatsApp messages arrive without opening WhatsApp.** These phones have no Google services, so
  nothing wakes WhatsApp when a message comes in. Every 15 minutes while the screen is off (and not on
  a call), dumb_phone opens WhatsApp in the background for 15 seconds so it fetches new messages, then
  goes back home; the screen stays off. 15 min because idle Android 8 lets an app wake the phone only
  about every 9 minutes. Messages can arrive up to ~15 min late.

## 1.7.13 (2026-10-04)
- Fixed: the ringtone kept playing after a missed call hung up. It now checks every second that the
  call is still ringing and stops otherwise (and after 60 s at the latest), instead of trusting only
  Android's "call ended" message.

## 1.7.12 (2026-10-04)
- Tapping a missed call in the panel opens Contacts (recent calls) instead of the stock call log.

## 1.7.11 (2026-10-04)
- **The grey pull-down can't open any more.** An invisible strip (an accessibility overlay, which sits
  above the status bar) catches the swipe from the top and opens our panel straight away, from home or
  any app. The status bar stays visible. Replaces the "close it after it opened" approach, which was
  unreliable and could crash System UI (kept only as a fallback).

## 1.7.10 (2026-10-04)
- Swiping down again (with our panel already open) no longer lets the grey shade through: home now
  also notices the shade by losing focus, since Android doesn't always announce the shade.

## 1.7.9 (2026-10-04)
- Home: the grey shade is folded away with "back" and our panel opens in place (Android's "close
  system dialogs" signal didn't fold it on this phone and made the stock launcher rebuild home).

## 1.7.8 (2026-10-04)
- Swiping down on the home screen opens our panel again instead of the grey one (on home it now opens
  the panel directly; the extra "back" + relaunch was overruled by Android after a home press).

## 1.7.7 (2026-10-04)
- Fixed "System UI has stopped" when pulling down from the top: the grey shade is now swapped for our
  panel once the swipe is over (closing it mid-swipe crashed the phone's own status bar).

## 1.7.6 (2026-10-04)
- Pull-down panel keeps its notifications (missed calls, messages…) when the app restarts or is
  updated: the list is saved privately on the phone. Before, a restart (this phone restarts apps when
  memory is short) emptied it.
- Missed calls are one line that updates ("2 missed calls"), and opening Contacts clears it.
- A notification restored after a restart opens its app (missed calls open Contacts recents).

## 1.7.5 (2026-10-04)
- Calls ring **and vibrate** (Android's "vibrate when ringing" is switched on by setup).
- A second way to start the ringtone: when the call screen appears while a call is ringing, in case
  Android's call-state message is late or missing. Tested with a real call: it rang.

## 1.7.4 (2026-10-04)
- **Calls ring again.** Android's own ringer opens ringtones through the phone's media service, which
  this phone (430 MB) kills when the call screen opens, so calls "rang" in silence. Now the dumb_phone
  keys service plays your Sounds ringtone itself, straight from the file (only when the ringer is
  normal, at ring volume; answering, hanging up or a volume key stops it). Android's ringtone is set to
  none so it never rings twice. A ringing call also frees the voice model's memory for the call screen.
- Calls use the phone's own call screen again (other call apps crash on this phone when a call comes in).

## 1.7.3 (2026-10-04)
- Home no longer slides in like a new screen when you press red / home. (The phone's stock launcher
  rebuilds our home on every press; the opening animation is now off, so it just appears.)

## 1.7.2 (2026-10-04)
- Pull-down panel: the ringer circle now changes right away when the volume keys switch to vibrate or silent.
- An app set to silent in Sounds no longer buzzes when the phone is on vibrate.

## 1.7.1 (2026-10-04)
- New alarm editor, easy by keys and by touch: one row at a time (↑ ↓): time (type 0730 or ← →),
  days as 7 big circles (← → + OK, 1-7 or tap), sound, volume bar, **ring on silent** and **vibrate on
  silent per alarm**, alarm on / delete. Back saves and closes. The alarm list is just the alarms now.
- Fixed: alarms stopped being armed after an app update (Android cancels them on every reinstall);
  they are now re-armed after updates, restarts and whenever the home screen opens.
- Fixed: editing a switched-off alarm left it off; saving now switches it on (unless you switched it
  off in that same edit). Fixed: opening an alarm reset its volume.
- Alarms / timer with no sound of their own use your alarm from Sounds (or "Alarm Gentle"), never the
  phone's stock tone.
- Volume keys: below the last ring step comes vibrate, then silent (and back up the same way).
- Sounds: ringtone and alarm lists show long sounds first, message and apps short ones first.

## 1.7 (2026-10-03)
- **Alarms** and **Timer** are separate apps (and separate circles in the panel: alarms, timer, torch).
  Both vibrate and snooze (alarm: 9 min, timer: 1 more minute), and they take over other apps'
  "set an alarm / set a timer" requests from the stock clock.
- **Sounds per app**: a fourth tab in Sounds gives any app its own notification sound (or silence);
  the rest use the message sound. The key service plays them (following the ringer: sound, buzz or
  nothing) and Android's own notification sound is switched off so nothing plays twice.
- **Jake** theme + background (Jake with the walkman): the night-sky blue, Jake's yellow, cream text.
- The phone's name under the clock wraps onto two lines when it would run into the weather.
- "vibrate on silent" for alarms and for the timer (next to "ring on silent", on by default): on silent they only vibrate if it is ticked.
- Alarms are easier to manage: a tap-able on/off switch on each alarm in the list; the editor shows
  "repeat" days (tap or 1-7), and buttons for on/off (8), delete (9, press twice) and save.
- **Each alarm has its own volume** (in its editor: `*` quieter, `#` louder, or slide the bar); the
  timer has its own too. Sound previews and the test beep now play at that exact volume (they were
  at full alarm volume).
- The pull-down panel shows the next alarm ("alarm 07:00 · in 6 h 58 min") and a live timer
  countdown; tap / OK opens them. (They ring with the app closed: Android's alarm clock wakes them.)
- Each alarm and the timer can have **their own sound** (alarm editor: `0`; timer: `#` or tap the sound
  line), picked from the sound library with full names and a preview; snoozing keeps it.
- The alarm / timer volume is a full-width bar (tap or slide along it, or ← →).
- Alarms and Timer each have their own **volume** (7 steps, with a short beep while you set it) and a
  **"ring on silent"** tick (on by default; off = only vibrate when the phone is on silent/vibrate).
  The alarm volume is put back after ringing.
- New background "krumm (aaahh!!! real monsters)" (full size, cut around Krumm by default).
- New background "san andreas: call from woozie", kept at full width so "move / zoom" can pan across
  it; "move / zoom" on any built-in picture now starts from the whole picture.
- New background "this is fine" (with automatic photo colours: cream text, flame-gold accent).
- The Sounds library has Brian Eno's "The Microsoft Sound" (Windows 95) at the top.

## 1.6 (2026-10-03)
- **Quick panel**: a second row of circles: **alarms** (lit when an alarm or the timer is set; shows
  the next alarm's time; opens Clock) and **torch** (the flashlight).
- **Clock** app: alarms (time, days of the week, on / off, snooze 9 min, rings full screen even when
  locked, re-armed after a restart) and a timer, in the theme. The stock KaiOS clock is hidden.
- **Sounds** app: pick the ringtone, the alarm and the message sound from a sound library of calm,
  composer-made tones (each with its full title, composer and source), previewed as you move.
  Ringtone and alarm can't be the same. The library is copied by setup.sh (sounds-private/ for your
  own phones: it includes copyrighted tones; only the CC-licensed Material and Ocean sounds may be
  shared publicly).
- **91 themes**: 35 more popular terminal schemes (Catppuccin Frappé / Macchiato, Rosé Pine Moon,
  Tokyo Night Moon / Day, Kanagawa Dragon / Lotus, Ayu Mirage / Light, Material Ocean, Snazzy,
  Oceanic Next, Spacegray, Nightfox, Carbonfox, Horizon, Iceberg, Melange, Poimandres, Flexoki,
  Oxocarbon, Sonokai, Doom One, Embark, Moonfly, Sakura, GitHub, Atom One Light, Nord Light…).
- **Fossify Messages and Fossify Phone now follow the theme** (and the background photo's colours)
  by themselves: a tiny "theme bridge" app (package org.fossify.thankyou, no screen) answers the
  Fossify apps' shared-theme question with our colours, and the home app tells them whenever the
  theme changes. setup.sh installs it and reinstalls the Fossify apps over themselves once (their
  data stays) so Android lets them read it.
- Fixed: swap colours did nothing visible with a background photo (the list didn't include the
  photo's colours); now "photo colours" is the first row and `*` swaps them.

## 1.5.2 (2026-10-03)
- Background list ends with "+ new background" (pick a photo from the phone), after "move / zoom".

## 1.5.1 (2026-10-03)
- Background list: "no background" at the top (turns it off and brings the old theme back).
- The phone's grey pull-down is caught more reliably (Android doesn't always name it), and closed
  with the system's own "close" signal before our panel opens.

## 1.5 (2026-10-03)
- **Battery** in Screen time: the level now, a 24-hour graph (charging in the accent colour), how
  fast it drains with the screen on and off, and about how long it will last at today's use. The key
  service only notes level changes the phone announces anyway, so it costs nothing.
- Background photo: off by default; turning it on in Themes picks one of 8 built-in "on the phone"
  pictures at random (Mr Burns, Is this the Krusty Krab?, sick SpongeBob, Hello yes this is dog,
  a baboon on a mobile phone, Moe's Tavern, the Powerpuff hotline, Monsters, Inc.) and goes straight
  to the home screen to show it. Opening it again lists all 8 (the highlighted one previews behind
  the list, OK uses it). "+ add background" picks a photo from the phone; added photos stay in the
  list ("my photo 1", …, up to 12), plus move / zoom and remove.
  Colours follow the picture, like the iPhone: a dark background tinted with the photo's main colour,
  near-white text and a vivid accent from it, in every app (Mr Burns keeps the Dracula colours);
  removing the background brings the old theme back. The red key on the home screen hides / shows the tiles (fixed: it did nothing, because
  this phone rebuilds the home screen on every home press).
- Radio list curated: Soundtrack & Games down to the best 6; Salsa & Tropical and Talk & Culture up
  to 3 each; new hidden gems: dublab (LA), Cashmere Radio (Berlin), Kiosk Radio (Brussels),
  WWOZ (New Orleans), Echoes of Bluemars, ABC Radio National, Monocle Radio, Tropicana Cali,
  Top Salsa Radio.

## 1.4 (2026-10-03)
- **Background photo** (first row in Themes): a photo behind the home screen. Pick one, then place it: drag or the D-pad moves
  it, pinch or 1 / 3 zooms, OK saves. Opened again: new photo, move / zoom, or remove. To stay
  readable on any photo (like the iPhone), the photo is shaded with the theme's background colour,
  more for bright photos that fight the theme, with a darker fade behind the clock and status line;
  the tiles become see-through panels and text gets a soft shadow.
  With a photo, the phone wakes showing just the photo and the clock; any key or a tap brings the
  tiles back, and the red key on the home screen hides / shows them.

## 1.3 (2026-10-03)
- **56 themes** (was 18): 38 terminal colour schemes added, the ones the Termius app uses
  (adventure time, cobalt2, cyberpunk, night owl, synthwave, miami heat, ubuntu, solarized light,
  github light…), converted automatically from iTerm2-Color-Schemes. `tools/themes.py` makes the list
  for every app and the keyboard; `screenshots/themes-all.png` shows them all.
- **Swap colours**: in Themes, `*` (or tapping the hint line) swaps a theme's text and accent colours,
  e.g. phosphor with amber text and green highlights. Saved as part of the theme.
- Light themes are now recognised by their colour (so new light ones get dark status icons).

## 1.2.1 (2026-10-03)
- WhatsApp opens faster (about 9 s → 5-6 s from cold): when WhatsApp or Organic Maps starts, the
  voice-typing model (~100 MB) is let go to give it room; it comes back when the screen turns off.
  Voice typing still works in the meantime: it records at once and the words appear a few seconds
  later the first time.
- Contacts: tabs in the order recents · contacts · favs · dial; `*` stars the highlighted person
  everywhere (also while searching and on their page); the star is a bigger amber icon left of the
  name; a person's page offers call, message and **whatsapp** for each number; OK on a favourite
  opens their page (green key still calls).

## 1.2 (2026-10-03)
- **Quick panel** (slide down anywhere, or ↑ from the top row of tiles), replacing the phone's grey
  pull-down: notifications, today's weather, 4 round buttons (Wi-Fi, hotspot, Bluetooth, ringer:
  tap to switch, hold to open their settings), and a full-width brightness bar (tap or drag).
  The hotspot button really switches the hotspot (Android doesn't let apps do that directly, so
  the phone flips the switch on the hotspot screen and comes back).
- **Notifications**: a bell with the count on the home screen; the list in the panel, OK opens one,
  `*` dismisses it.
- **Weather**: small weather under the clock with a word and the place (`☁ 14° rain MEL`);
  an iPhone-style row of the next 12 hours in the panel (slide or ← →, tap to open); a **Weather**
  app with the next 24 hours and 7 days. Place = automatic, or pick one by name. Open-Meteo, no key,
  fetched at most once an hour.
- **Screen time** app: today, daily average, the last 7 days as bars, and time per app. Counts only
  while the screen is on (like the iPhone), uses Android's own records, costs no battery.
- **All apps**: pin apps to the top (hold OK or `*`), search by typing (the top line shows the
  letters, best matches first), a short description next to each app ("notes", "2fa codes"), and
  built-in duplicates hidden when a better app is installed.
- **Contacts**: a **favs** tab (star someone on their page, or `*` on their name); OK on a favourite
  calls. Search now types letters normally (6 = m, 66 = n, hold = the digit). The keypad's
  **contacts key** opens favourites and the **envelope key** opens WhatsApp.
- **Podcasts**: show pages with cover art, newest 10 episodes + "load 10 more", progress bars and
  played marks, a now-playing bar, `5` = play / pause, all shortcuts on the hint line.
- **8 more themes** (18 in all): everforest, rose pine, kanagawa, monokai, one dark, ayu, crimson,
  latte (light).
- Radio: the D-pad works in favourites, and all genres open there.
- Notes (Notally): the red key saves the note before going home.
- Fixed: Contacts showed nothing for minutes when a contact had hundreds of numbers (e.g. a spam
  blocklist contact from an iPhone).

## 1.1.3 (2026-10-03)
- Unplugging plays a soft "goodbye": the charging chime's notes falling and fading.
- Fixed: on some phones (e.g. Opel TouchFlip) the system bar app played its own loud sound on plug
  and unplug, ignoring vibrate. setup.sh now stops that app from playing audio.

## 1.1.2 (2026-10-03)
- New charging chime: an ambient, Brian Eno-style swell: three rising notes (G C E), each a small
  pad of soft voices, getting louder, with echo and a long reverb (about 6 s). Low battery plays two
  falling notes in the same style. Still silent on vibrate / silent.

## 1.1.1 (2026-10-03)
- Fixed: the phone still made its own loud sound when plugged in, even on vibrate. That came from a
  second system setting ("charging started" sound), now switched off too; only the soft chime is
  left, and only when the ringer is on normal.

## 1.1 (2026-10-03)
- **Themes app**: 10 colour themes (phosphor, amber, nord, gruvbox, dracula, catppuccin, solarized,
  tokyo night, mono, paper) with a live preview while scrolling; OK keeps, Back cancels. Home,
  Contacts, Radio, Podcasts, Snake and the volume / voice bar all follow.
- **Snake**: classic Nokia-style game in the chosen theme.
- **Charging sound**: the stock plug-in / low-battery beeps are switched off; a soft two-note chime
  plays instead, and nothing at all on vibrate or silent.
- **Home**: the phone's name and the version under the clock (e.g. `DUMB_PHONE 1.1`); date with a
  leading zero (`sat 03 oct`).
- **Radio**: `+ add a station` searches stations worldwide; hold a station to remove it.
- Fixed: changing the theme could crash the home screen, after which Android fell back to the
  phone's old launcher.
- Fixed: the phone's old launcher could come back on top when the flip was closed.
- Fixed: Android's own focus outline was drawn over the selected tile in non-green themes.
- Fixed: the first key press in Themes didn't move the highlight.
- Setup: installs the companion apps from F-Droid, makes them the defaults, double-checks the home
  screen setting, and closes the phone's old launcher.

## 1.0 (2026-10-03)
- First version: home screen, Contacts, Radio, Podcasts, offline push-to-talk voice typing, volume
  bar, green-key shortcut, guard that keeps it all switched on.
