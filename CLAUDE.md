# CLAUDE.md

Guidance for Claude Code when working in this project.

## Overview

Android e-reader for the novel *Pasar Purnama* (Kotlin + Jetpack Compose, Material 3). The home screen is a library ("Perpustakaan") gallery of books; tapping one opens the reader. Single module (`:app`), package `com.dimas.pasarpurnama`, minSdk 26, compileSdk/targetSdk 35. No tests yet.

## Architecture

- **Book format.** Every book is a `NOVEL` JSON object: `{ id, title, author, cover:{image, subtitle}, chapters:[{title, html}] }` — the same object `build_novel.py` injects into its single-file reader HTML (on one line, `const NOVEL = {...};`).
- **Accounts (Supabase).** Email + password login; every account has its own library. `Supabase.kt` is a tiny REST client (HttpURLConnection + org.json, no extra libraries) using `BuildConfig.SUPABASE_URL` / `SUPABASE_KEY` (publishable key, set in `app/build.gradle.kts`). `AuthManager.kt` signs up / in / out and keeps the session (access + refresh token) in SharedPreferences `auth`, refreshing the token when it is about to expire. The server side (table `books`, private bucket `novels`, row-level security so users only reach their own data) is in `supabase/setup.sql`; run it once in the Supabase SQL Editor.
- **`BookRepository.kt`** is per account (`BookRepository(context, userId)`). It lists books from bundled `app/src/main/assets/books/*.json` and the account's imported `filesDir/users/<uid>/books/*.json` (an imported book with the same id overrides a bundled one). The library id is a slug of `title + subtitle` (`pasar-purnama-jilid-1`), so volumes never collide; it overwrites `NOVEL.id` before the reader sees it, which keeps bookmarks separate per book. `import()` accepts a `.json` or a build_novel.py `.html` and extracts the `const NOVEL =` line. Progress, reader state and sync flags live in `filesDir/users/<uid>/library.json`. `sync()` mirrors it to Supabase (row per book in `books`, imported files at `novels/<uid>/<bookId>.json`, last write wins on `updated_at`); it runs whenever the library screen refreshes. `claimLegacyData()` hands pre-account data (`filesDir/books`, prefs `progress`, the reader's old localStorage) to the first account that signs in on the device.
- **`MainActivity.kt`** — Login / sign-up screen until there is a session, then the Compose library grid with sync and account (sign out) buttons. "Tambah buku" opens the system file picker and imports; long-press deletes imported (not bundled) books. Covers use `cover.image` when it is a `data:image` URI, otherwise a generated moon cover.
- **`ReaderActivity.kt`** — hosts `assets/reader/reader.html` (the original HTML reader, with its novel data removed) in a WebView served through `WebViewAssetLoader` at `https://appassets.androidplatform.net/assets/reader/reader.html`. The page talks to the app through the `Android` JS bridge: `getNovel`, `onProgress`, `getState` / `saveState` (reader settings + bookmarks, stored per account instead of localStorage), `useLegacyState`, `onThemeColor`, `close`, `toggleFullscreen` (immersive mode), `saveText` (notes export via SAF), `print` (PDF via PrintManager). The file `<input>` for importing notes is served by `onShowFileChooser`. System back calls `window.PPback()` in the page (closes modals first, then finishes).

## Adding a book

- **For users (no rebuild):** copy the `.html` from build_novel.py (or a `.json`) to the phone, then "Tambah buku". Re-importing the same title + subtitle updates that book.
- **Bundled in the APK:** extract the NOVEL JSON from the HTML into `app/src/main/assets/books/<name>.json` and rebuild.
- **Scene illustrations:** put images named `babNN-adeganM.webp` (or .jpg/.png) in `illustrations/jilid-N/` and run `python tools/add_illustrations.py app/src/main/assets/books/<book>.json illustrations/jilid-N`. Each picture is embedded (data: URI, WebP ≤1000 px) as `<figure class="illus">` right before the M-th `<hr class="scene">` of chapter NN; the last scene has no break after it, so its picture goes at the end of the chapter. Re-run it after regenerating a book from build_novel.py; it is idempotent (old illustrations are removed first).
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

### Release APK (for installing on devices, e.g. the Huawei tablet)

Signing is read from `keystore.properties` in the project root (git-ignored), which points at `D:\DIMAS\android-sdk-tools\keys\pasarpurnama-release.jks`. Keep that keystore and its password backed up: updates only install over an existing copy when signed with the same key. Without `keystore.properties` the release build is unsigned.

```powershell
& "D:\DIMAS\android-sdk-tools\gradle-8.9\bin\gradle.bat" --no-daemon assembleRelease
# APK: app\build\outputs\apk\release\app-release.apk  (bump versionCode/versionName in app/build.gradle.kts for each new release)
```
