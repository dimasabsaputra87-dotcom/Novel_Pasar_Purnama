# CLAUDE.md

Guidance for Claude Code when working in this project.

## Overview

Android e-reader for the novel *Pasar Purnama* (Kotlin + Jetpack Compose, Material 3). The home screen is a library ("Perpustakaan") gallery of books; tapping one opens the reader. Single module (`:app`), package `com.dimas.pasarpurnama`, minSdk 26, compileSdk/targetSdk 35. No tests yet.

## Architecture

- **Book format.** Every book is a `NOVEL` JSON object: `{ id, title, author, cover:{image, subtitle}, chapters:[{title, html}] }` — the same object `build_novel.py` injects into its single-file reader HTML (on one line, `const NOVEL = {...};`).
- **`BookRepository.kt`** lists books from two places: bundled `app/src/main/assets/books/*.json` and user-imported `filesDir/books/*.json` (an imported book with the same id overrides a bundled one). The library id is a slug of `title + subtitle` (`pasar-purnama-jilid-1`), so volumes never collide; it overwrites `NOVEL.id` before the reader sees it, which keeps bookmarks/localStorage separate per book. `import()` accepts a `.json` or a build_novel.py `.html` and extracts the `const NOVEL =` line. Reading progress (last chapter index) lives in SharedPreferences `progress`.
- **`MainActivity.kt`** — Compose library grid. "Tambah buku" opens the system file picker and imports; long-press deletes imported (not bundled) books. Covers use `cover.image` when it is a `data:image` URI, otherwise a generated moon cover.
- **`ReaderActivity.kt`** — hosts `assets/reader/reader.html` (the original HTML reader, with its novel data removed) in a WebView served through `WebViewAssetLoader` at `https://appassets.androidplatform.net/assets/reader/reader.html`. The page talks to the app through the `Android` JS bridge: `getNovel`, `onProgress`, `onThemeColor`, `close`, `toggleFullscreen` (immersive mode), `saveText` (notes export via SAF), `print` (PDF via PrintManager). The file `<input>` for importing notes is served by `onShowFileChooser`. System back calls `window.PPback()` in the page (closes modals first, then finishes).

## Adding a book

- **For users (no rebuild):** copy the `.html` from build_novel.py (or a `.json`) to the phone, then "Tambah buku". Re-importing the same title + subtitle updates that book.
- **Bundled in the APK:** extract the NOVEL JSON from the HTML into `app/src/main/assets/books/<name>.json` and rebuild.
- When updating the reader itself from a newer build_novel.py HTML, keep the Android edits in `reader.html` (search for `Android.` and `PPback`).

## Build environment (Windows, this machine)

Same toolchain as `../wallpaper app - android` (see its CLAUDE.md): JDK 17 at `D:\DIMAS\android-sdk-tools\jdk-17.0.20.1+1`, Gradle 8.9 at `D:\DIMAS\android-sdk-tools\gradle-8.9`, SDK at `D:\DIMAS\android-sdk-tools\sdk`, AVD `wall` in `D:\DIMAS\android-sdk-tools\avd`. Use the installed Gradle, not `gradlew` (wrapper download fails TLS here).

```powershell
$env:JAVA_HOME="D:\DIMAS\android-sdk-tools\jdk-17.0.20.1+1"
$env:ANDROID_HOME="D:\DIMAS\android-sdk-tools\sdk"
$env:JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"
& "D:\DIMAS\android-sdk-tools\gradle-8.9\bin\gradle.bat" --no-daemon assembleDebug
# APK: app\build\outputs\apk\debug\app-debug.apk
$adb="$env:ANDROID_HOME\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb shell am start -n com.dimas.pasarpurnama/.MainActivity
```
