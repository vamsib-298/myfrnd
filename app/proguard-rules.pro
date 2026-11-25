# R8 code shrinking and resource shrinking are enabled for release builds.
# Keep Filament and gltfio native/JNI classes intact in release builds.
-keep class com.google.android.filament.** { *; }
-dontwarn com.google.android.filament.**
