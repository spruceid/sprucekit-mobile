# JNA (Java Native Access) uses reflection to access com.sun.jna.Pointer.peer.
# Without these rules, R8/ProGuard in release builds obfuscates or removes the
# field, causing a runtime UnsatisfiedLinkError.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
