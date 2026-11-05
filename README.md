# MisoManager

MisoManager is a lightweight Android file manager. The first version uses Android's Storage Access Framework, so the app can only browse folders that you explicitly grant access to. No broad storage permission or account is required.

## Features

- Browse a folder and its subfolders, with the selected location remembered between launches
- Search the current folder and sort folders/files by name or most recently modified
- Create folders, open files in compatible apps, rename and delete items
- Copy or move files and folders, including recursive folder copies
- Translucent glassmorphism-style surfaces, gradient backdrops, and Android edge-to-edge support
- Five switchable color themes, including a dark Midnight palette; the choice persists between launches

The app supports Android 7.0 (API 24) and newer. Its glass-style interface offers five selectable palettes: Sage Green, Ocean Blue, Lavender, Warm Sunset, and Midnight Glass. Pick one from **More → Glass color theme**; your choice is saved between launches.

## Build locally

Install JDK 17 and Android SDK Platform 35, then run:

```sh
./gradlew assembleDebug
```

The installable debug APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

## GitHub releases

Pushing a version tag such as `v1.0.0` starts the Android APK workflow. It builds and attaches an installable **debug-signed preview APK** to a GitHub prerelease. The debug signing key is suitable for testing only; a production Play Store release must be signed with a private release key. You can also run the workflow manually to produce a downloadable Actions artifact.
