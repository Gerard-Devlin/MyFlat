<p align="center"><img src="app/src/main/res/drawable-nodpi/myflat_mark.png" width="128" alt="MyFlat"></p>
<h1 align="center">MyFlat</h1>
<p align="center">An offline Android scanner for books and documents.</p>

MyFlat captures, flattens and organizes scanned pages on your device. No account, subscription or Internet connection is required.

## Features

- Single-page and two-page camera capture, autofocus, flash, timers and automatic capture.
- Batch image and PDF import.
- Page flattening, color enhancement, finger removal, text-line straightening and handwriting removal.
- Perspective cropping, rotation, paper ratios, zoom, original comparison and an eraser with undo.
- Folders, multi-page documents, search, page ordering, trash and restore.
- PDF, JPEG and ZIP export through Android's file picker and share sheet.

## Build

Open the repository root in Android Studio, sync Gradle and run the `app` configuration.

| Requirement | Version |
| --- | --- |
| Android | 8.0 / API 26 or newer, ARM64 |
| JDK | 17 or newer |
| Android SDK | Platform 36 and Build Tools 36 |

```sh
./gradlew :app:assembleDebug
```

On Windows, use `gradlew.bat`. The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Use

Capture a page from the camera, or open Library to import images and PDFs. Tap a scanned page to crop, rotate or edit it. Use folders to organize documents, then export or share them as PDF, JPEG or ZIP files.

## Tests

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
```

## Project layout

```text
app/          Android interface, camera, storage and export
scanner/      Image processing
models/       Model manifest
```

## License

Project-owned source code is released under the [MIT License](LICENSE). See [Third-party notices](THIRD_PARTY_NOTICES.md) for other components and assets, and [Privacy](PRIVACY.md) for data handling.
