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
- Records video the same way: every frame is detected and anonymized before it
  reaches the encoder, so the raw camera stream is never written. Saves H.264
  MP4 to `Movies/Veil`.
- Tap a face — in the viewfinder or on a captured photo — to keep it visible,
  e.g. your own. The choice follows the face across video frames through ML
  Kit's per-session tracking id; no identity is computed or stored.

## Modules

| Module | Contents |
| --- | --- |
| `core-privacy` | Kotlin Multiplatform engine (JVM + iOS): geometry, blur, pixelation, mask, audit, pipeline, face tracking |
| `app` | Android: Compose UI, CameraX, ML Kit bridge, MediaStore/share, settings |
| `iosApp` | iOS: SwiftUI, AVFoundation capture, Vision detection, Photos/share |

The privacy engine is one codebase for both platforms. Only what the OS owns
differs: camera, face detector, and the photo library.

| | Android | iOS |
| --- | --- | --- |
| Camera | CameraX | AVFoundation |
| Face detection (on-device) | ML Kit | Vision `VNDetectFaceRectanglesRequest` |
| Face ids for keep-visible | ML Kit tracking ids | shared `FaceTracker` |
| Video | MediaCodec + MediaMuxer | `AVAssetWriter` |
| Saving | MediaStore `Movies/Veil`, `Pictures/Veil` | Photos, "Veil" album |

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

## iOS

Needs a Mac with Xcode 15+ and [XcodeGen](https://github.com/yonaskolb/XcodeGen)
(`brew install xcodegen`). The Xcode project is generated, not committed.

```bash
cd iosApp
xcodegen generate            # writes iosApp/Veil.xcodeproj
open Veil.xcodeproj          # then run on a device or simulator
```

The build's first phase runs `:core-privacy:embedAndSignAppleFrameworkForXcode`,
which compiles the shared Kotlin into the `VeilPrivacy.framework` the Swift
code imports. To build the framework by hand:

```bash
./gradlew :core-privacy:linkDebugFrameworkIosSimulatorArm64   # simulator
./gradlew :core-privacy:linkDebugFrameworkIosArm64            # iPhone
```

Run the shared engine's tests on every target:

```bash
./gradlew :core-privacy:allTests
```

The camera is real hardware, so the simulator shows the UI but detects no
faces; capture behaviour has to be checked on an iPhone.

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

## Known limitations

A face that the frame cuts in half at an edge can be missed: ML Kit's recall
drops sharply once most of a face is out of frame, and on such an input it
reports nothing at full resolution and only finds the face at one particular
downscale. Adding scan passes (mirrored borders, extra scales) did not fix it
reliably and cost seconds per capture, so it is not worked around here. Frame
the subject fully, or use maximum strength, when the shot matters.

The iOS app has not been compiled or run: it was written on Linux, where no
Xcode exists. The shared Kotlin is verified (JVM and Kotlin/Native tests pass
and the iOS frameworks are configured), but the Swift layer awaits its first
build on a Mac.
