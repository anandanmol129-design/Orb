-keepattributes SourceFile,LineNumberTable
-dontobfuscate
-dontoptimize
-keep public class de.mm20.launcher2.ui.launcher.search.common.SearchableItemVM {
    public <init>();
}


# ML Kit subject segmentation (depth wallpaper). Its components are registered via reflection
# from manifest metadata; without these, R8 strips them and getClient() throws an NPE.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_subject_segmentation.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }

# Vosk (wake word) calls its native library through JNA, which looks classes, fields and
# methods up by name at runtime; R8 must keep them as they are.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
