# Veil — privacy-first camera

**Kotlin Multiplatform · Android + iOS + web.** One privacy engine, written
once in common Kotlin, compiled to a JVM library for the Android app, to
`VeilPrivacy.framework` for the iOS app and to JavaScript for the browser app.

Take the photograph you wanted. Every visible face is anonymized on-device;
buildings, scenery, signs and sky stay exactly as sharp as they were shot.

```
            ┌──────────────── core-privacy (Kotlin Multiplatform) ────────────────┐
CAMERA  →   │ ELLIPTICAL FACE REGION → BLUR / PIXELATE / MASK → PRIVACY AUDIT     │  →  GALLERY
(on-device  └─────────────────────────────────────────────────────────────────────┘
 detection)   commonMain → jvm (Android)  ·  iosArm64 / iosSimulatorArm64 / iosX64  ·  js (browser)
```

| | Android | iOS | Web |
| --- | --- | --- | --- |
| Shared engine | `core-privacy` jvm target | `core-privacy` iOS targets, as `VeilPrivacy.framework` | `core-privacy` js target |
| UI | Jetpack Compose | SwiftUI | HTML + canvas |
| Camera | CameraX | AVFoundation | `getUserMedia` |
| Face detection (on-device) | ML Kit | Vision `VNDetectFaceRectanglesRequest` | MediaPipe Tasks Vision (WebAssembly, served by the app) |
| Face ids for keep-visible | ML Kit tracking ids | shared `FaceTracker` | shared `FaceTracker` |
| Video | MediaCodec + MediaMuxer | `AVAssetWriter` | `MediaRecorder` on the protected canvas |
| Saving | MediaStore `Pictures/Veil`, `Movies/Veil` | Photos, "Veil" album | browser download + Web Share |

## What it does

The list below describes the Android app; the iOS and web apps do the same
through Vision and MediaPipe respectively.

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
  Kit's per-session tracking id (on iOS, through the shared `FaceTracker`,
  which matches faces by position between frames); no identity is computed or
  stored.

## Modules

| Module | Contents |
| --- | --- |
| `core-privacy` | Kotlin Multiplatform engine (JVM + iOS + JS): geometry, blur, pixelation, mask, audit, pipeline, face tracking |
| `app` | Android: Compose UI, CameraX, ML Kit bridge, MediaStore/share, settings |
| `iosApp` | iOS: SwiftUI, AVFoundation capture, Vision detection, Photos/share |
| `webApp` | Web: Kotlin/JS, `getUserMedia`, MediaPipe WASM detection, canvas pipeline, download/share |

Everything that decides which pixels change, and how, lives in
`core-privacy/src/commonMain` and is compiled for every platform from that one
source — geometry, blur, pixelation, mask, the post-processing audit and
between-frame face tracking. Only what the platform owns is written per
platform: the camera, the face detector and the place pictures are saved.

```kotlin
// core-privacy/build.gradle.kts
kotlin {
    jvm()                                              // Android app
    js(IR) { browser() }                               // web app
    listOf(iosArm64(), iosSimulatorArm64(), iosX64())  // iPhone + simulators
        .forEach { it.binaries.framework { baseName = "VeilPrivacy" } }
}
```

## Android: build, install, run

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

## iOS: build and run

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

## Web: build and run

```bash
./gradlew :webApp:jsBrowserDevelopmentRun   # dev server on http://localhost:8080
./gradlew :webApp:jsBrowserDistribution     # static site in webApp/build/dist/js/productionExecutable
```

The distribution is a plain static directory — serve it with any web server.
Browsers only grant a camera on `https://` or `http://localhost`.

The MediaPipe WebAssembly runtime and the BlazeFace model are served from the
app itself, not a CDN, so detection runs in the browser with no network call
after the page loads. The camera `<video>` element is never shown: the only
visible surface, the only source of a photo and the only source of a recording
is the canvas holding protected pixels.

MediaPipe reads every input frame through a WebGL 2 canvas, so a browser
without WebGL 2 cannot detect faces at all. The app refuses to open the camera
there rather than showing an unprotected preview.

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

The web app was exercised in Chrome against a fake camera device on Linux:
detection, blur/pixelate/mask, keep-visible, photo review and save, and
protected WebM recording all verified. Web Share and mobile browsers are
untested — Chrome for Testing on this Linux box exposes neither.

The iOS app has not been compiled or run: it was written on Linux, where no
Xcode exists. The shared Kotlin is verified (JVM and Kotlin/Native tests pass
and the iOS frameworks are configured), but the Swift layer awaits its first
build on a Mac.
