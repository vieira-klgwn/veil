# ML Kit face detection loads model classes reflectively.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_face.** { *; }
-dontwarn com.google.mlkit.**

# CameraX camera2 implementation is resolved at runtime.
-keep class androidx.camera.camera2.** { *; }

# Strip logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
