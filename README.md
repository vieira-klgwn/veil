# Veil — privacy-first camera

Take the photograph you wanted. Every visible face is anonymized on-device;
buildings, scenery, signs and sky stay exactly as sharp as they were shot.

```
CAMERA → ML KIT FACE DETECTION → ELLIPTICAL FACE REGION → BLUR / PIXELATE / MASK
       → PRIVACY AUDIT (escalate if weak) → MEDIASTORE
```

## What it does

- Opens straight to a CameraX preview with an optional live "face shield" overlay.
- Captures at full sensor resolution, applies EXIF orientation, keeps the original
  dimensions, aspect ratio and colour.
- Finds faces with on-device ML Kit (detection only — no recognition, no
  identity, no embeddings, nothing leaves the device).
- Protects each face independently inside a feathered, rotated ellipse. Pixels
  outside that ellipse are byte-for-byte untouched.
- Blur, pixelate or solid mask; balanced or maximum strength.
- Audits every face after processing and escalates to a solid mask if the
  result is still legible.
- Saves JPEG q96 to `Pictures/Veil` through MediaStore, shares via FileProvider.

## Modules

| Module | Contents |
| --- | --- |
| `core-privacy` | Pure-Kotlin, JVM-testable engine: geometry, blur, pixelation, mask, audit, pipeline |
| `app` | Compose UI, CameraX, ML Kit bridge, MediaStore/share, settings |

## Build, install, run

```bash
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew clean
./gradlew test                       # pure-Kotlin privacy engine
./gradlew assembleDebug
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n app.veil.camera.debug/app.veil.camera.MainActivity
```

Release build (`./gradlew assembleRelease`) is minified and shrunk. It is
currently signed with the debug key so it can be smoke tested locally; point
`signingConfigs` at a real keystore (kept outside the repository) before
distributing.

Instrumented tests, which run the real ML Kit + Bitmap + MediaStore path:

```bash
./gradlew :app:connectedDebugAndroidTest
```

## Privacy properties

- Camera is the only runtime permission the app declares. `INTERNET` and
  `ACCESS_NETWORK_STATE` are merged in by the Google Play services library that
  ships ML Kit; the app itself opens no connection, and the capture → detect →
  protect → save flow works with radios off because the face model is bundled
  in the APK (`com.google.mlkit.vision.DEPENDENCIES=face`).
- No analytics, no crash reporting, no network upload of images.
- Detection results are used to locate pixels and are never persisted.
- Only the protected bitmap is written to the gallery or handed to the share
  sheet; the original never touches storage.
