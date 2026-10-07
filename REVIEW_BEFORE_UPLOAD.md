# Read this before uploading

This folder is a clean, public-ready copy of the dumb_phone project. Nothing here is uploaded yet.
Go through the checklist, then push it to GitHub (commands at the bottom).

## What was left out (none of it is in this folder)
- `keystore/` — the app signing key
- `sounds-private/` — the copyrighted ringtones (Nokia, Sony, Eno, etc.). The `sounds/` folder here
  ships only the six Creative Commons ones that are legal to share (see `sounds/README.md`).
- `home/assets/backgrounds/` — the built-in wallpapers (Simpsons, GTA, SpongeBob, etc., all copyrighted)
- `cache/`, `dist/`, `build/`, `home/libs/`, downloaded tool jars — build output / downloads
- screenshots, the Reddit draft, internal planning notes

Note: the trains app here is **dumb aus trains**, which uses the free Transitous service and needs no
key, so there's no PTV key to strip. The old key-based PTV (Victoria-only) app is not in this folder.

A `.gitignore` is set up so those never get committed by accident.

## You need to decide / check
1. **License** — done: `LICENSE` is MIT, copyright `jorch-wav`. Change it if you want a different name or a different license. (MIT fits, since the bundled deps (JetBrains Mono, the Nerd Font subset, the AOSP word list, Vosk, TT9) are all permissive.)
2. **Backgrounds** — ships two public-domain medieval wallpapers (whale, crocodile; British Library, public domain). The copyrighted ones (Simpsons, GTA, etc.) are not here. To add more, drop a 480x640 JPEG into `home/assets/backgrounds/` and list it in `Background.BUILT`. The "add your own photo" feature works too.
3. **Radio stations** — `radio/assets/stations.csv` ships your curated station list (public stream URLs, nothing personal). Fine to keep, or trim if you like.
4. **README.md** — reads fine as a public readme; skim it once for anything you'd word differently.
5. **Signing** — `build.sh` makes a fresh local keystore on first run, so each person signs with their own key. Your key was not included (good).

## Verify it's clean (optional, run in this folder)
```
grep -rIn -E "arreola4692|3003925|pockepi|pocketphone|192\.168\.0" . ; echo "^ should print nothing"
```

## Build test (optional)
```
./build.sh        # builds all the APKs into dist/ (no keys needed)
```

## Upload to GitHub
```
cd ~/dumb_phone-public
git init
git add .
git commit -m "dumb_phone: initial public release"
git branch -M main
git remote add origin https://github.com/<your-username>/dumb_phone.git
git push -u origin main
```
(Create the empty repo on github.com first. If git asks for a password on push, use a Personal Access
Token, not your account password.)
