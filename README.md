# Lite Browser (GeckoView, Android 8.0+)

Own browser engine (Firefox's GeckoView) bundled in the app - does not use the phone's old system WebView.

## Build the APK without Android Studio
1. Create a free GitHub account and a new repository (any name, public or private).
2. Upload ALL files of this folder to the repository (keep the folders; the hidden `.github/workflows/build.yml` must be included).
3. Open the repository > Actions tab > "Build APK" > Run workflow (it also runs automatically on every upload).
4. When it finishes (about 10-15 min) open the run > Artifacts > download `LiteBrowser-apks`.
5. Inside: `app-arm64-release.apk` (most phones) and `app-armv7-release.apk` (older 32-bit phones).
   Try arm64 first; if Android says it is not compatible, use armv7.
6. Copy the APK to the phone, open it, allow "Install unknown apps" for the app you opened it from.

If the build fails, open the failed run, copy the last ~40 lines of the red step and send them back.
