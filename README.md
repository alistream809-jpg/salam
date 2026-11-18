# SpaceHub

Android game store + launcher (Kotlin native layer, HTML/CSS/JS UI in a WebView, one small Java helper).

## Build
1. Open this folder in Android Studio (Koala+ / JDK 17). Let it sync (it will create the Gradle wrapper if missing).
2. Run on a device, or `./gradlew assembleDebug` -> `app/build/outputs/apk/debug/app-debug.apk`.

## What is real
- Catalog: F-Droid official index (index-v2.json), "Games" category, picks the newest version compatible with your SDK/ABI.
- Downloads: HttpURLConnection, HTTPS only, Range resume, real bytes/speed/ETA, free-space check, SHA-256 verification (F-Droid).
- Install: PackageInstaller session (+ split APKs), Android confirmation dialog always shown.
- Play: PackageManager launch intent. Library persists in SQLite.
- GitHub Actions: workflows (.yml), runs, artifacts -> zip download -> APK extraction -> validation -> Library.
  GitHub requires a token for artifact downloads, even on public repos. Token is stored with EncryptedSharedPreferences.
- FPS Booster: only SDK-reported values (RAM, storage, battery, thermal, refresh rate, CPU cores/freq if readable).

## Known limits
- Not compiled or run in the authoring environment: expect to fix small compile issues on first sync.
- F-Droid has no popularity/trending data, so there are no "Trending/Popular" rows. "Featured" = games with developer artwork.
- Store sub-categories are keyword-matched from the real name/summary text (F-Droid only has "Games"). "Offline" = APK requests no INTERNET permission.
- CPU usage is not available to apps on Android 8+; apps cannot kill other apps or overclock.

## GitHub Actions
`.github/workflows/build.yml` builds `app-debug.apk` and uploads it as the artifact `SpaceHub-debug-apk`.
Push this project to GitHub, wait for the run to succeed, then paste the repo URL (or the .yml URL) into SpaceHub > GitHub tab.
