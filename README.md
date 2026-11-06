# Label Printer (标签打印)

Installable web app for printing warehouse labels to a Zebra ZR328 / ZQ320 over Bluetooth.

## Files
- `index.html` – the app (includes the D365 item list)
- `manifest.webmanifest` – app name, icon and colours
- `sw.js` – lets the app open offline and update itself
- `icons/` – app icons

## Install on a phone (Android, Chrome)
1. Open https://venkat365ax-ui.github.io/Barcode-Printer/
2. Tap ☰ → **Install app**, or Chrome ⋮ → **Install app / Add to Home screen**.
3. Open it from the home-screen icon.

## Updating
Replace `index.html` (and bump `APP_VERSION` in it and `VERSION` in `sw.js`).
Open phones show "A new version is available → Update".
