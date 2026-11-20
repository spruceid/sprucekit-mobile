# JNA (Java Native Access) accesses com.sun.jna.Pointer.peer via reflection.
# R8/ProGuard must not obfuscate or remove these classes/fields.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }

# JNA includes AWT support for desktop — not used on Android, suppress missing-class warnings.
-dontwarn java.awt.**

# Flutter deferred components (Play Store split installs) — not used in this example.
-dontwarn com.google.android.play.core.**
