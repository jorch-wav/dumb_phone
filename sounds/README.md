# Sounds

Ringtone, alarm and message sounds for the Sounds app. `setup.sh` copies this folder to
`/sdcard/Ringtones/dumb_phone/` on the phone. These are all free to use and pass on; keep the credit.

| file | name | by | licence |
|---|---|---|---|
| warm-marimba.ogg | Warm Marimba | made for dumb_phone | CC0 |
| lofi-keys.ogg | Lo-Fi Keys | made for dumb_phone | CC0 |
| pulse.ogg | Pulse | made for dumb_phone | CC0 |
| fd-ring.ogg | Classic Ring | freedesktop.org sound theme | CC BY-SA 3.0 |
| fd-alarm.ogg | Alarm Bell | freedesktop.org sound theme | CC BY-SA 3.0 |
| fd-chime.ogg | Chime | freedesktop.org sound theme | CC BY-SA 3.0 |
| fd-blip.ogg | Blip | freedesktop.org sound theme | CC BY-SA 3.0 |
| fd-bell.ogg | Bell | freedesktop.org sound theme | CC BY-SA 3.0 |
| material-ringtone.ogg | Ringtone Minimal | Google Material sound kit | CC BY 4.0 |
| material-alarm.ogg | Alarm Gentle | Google Material sound kit | CC BY 4.0 |
| material-ambient.ogg | Notification Ambient | Google Material sound kit | CC BY 4.0 |
| ocean-call.ogg | Ocean Incoming Call | Guilherme Marçal Silva (KDE Plasma Ocean) | CC BY-SA 4.0 |
| ocean-alarm.ogg | Ocean Alarm | Guilherme Marçal Silva (KDE Plasma Ocean) | CC BY-SA 4.0 |
| ocean-message.ogg | Ocean Message | Guilherme Marçal Silva (KDE Plasma Ocean) | CC BY-SA 4.0 |

The `warm-marimba`, `lofi-keys` and `pulse` tones were made for this project and are CC0, so do
whatever you like with them.

## Add your own

Drop any `.ogg` file in here before you run `setup.sh`, or copy it straight to the phone:

    adb push my-ringtone.ogg /sdcard/Ringtones/dumb_phone/

Then open the Sounds app and pick it. To give it a nicer name in the list, add a line to
`sounds.json`: `{ "file": "my-ringtone.ogg", "title": "My Ringtone", "credit": "", "seconds": 8 }`.

Old Nokia, Sony and other phone tones are not here because they are still owned by those companies
and can't be shared in a public repo. If you own them, you can add them yourself with the step above.
