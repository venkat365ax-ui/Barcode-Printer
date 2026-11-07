# Label Printer (标签打印)

Warehouse label printing for the Zebra ZR328 / ZQ320 over Bluetooth.

Two ways to use it:
- **Web app**: https://venkat365ax-ui.github.io/Barcode-Printer/ (needs Chrome on Android for Bluetooth)
- **Android app (APK)**: built automatically by GitHub from this repo, no Chrome needed.
  Latest download: https://github.com/venkat365ax-ui/Barcode-Printer/releases/latest/download/LabelPrinter.apk

## Files
- `index.html` – the app (also bundled inside the APK)
- `manifest.webmanifest`, `sw.js`, `icons/` – web-app install + offline
- `android/` – the Android app project (WebView + native Bluetooth printing)
- `.github/workflows/build-apk.yml` – builds the APK on every change to `index.html` or `android/`

## Using the APK
1. Pair the printer once: phone Settings → Bluetooth → pair the ZR328 (PIN 0000 or 1234 if asked).
2. Open Label Printer → ☰ → Printer setup → Connect printer → choose the ZR328.
3. Fill in the label and tap Print.

Note: `android/app/labelprinter.keystore` is the app's signing key (internal use). Keeping it fixed lets new APKs install over old ones.
