# JNA (Java Native Access) uses reflection to access com.sun.jna.Pointer.peer.
# Without these rules, R8/ProGuard in release builds obfuscates or removes the
# field, causing a runtime UnsatisfiedLinkError.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }

# rustls-platform-verifier: the Rust layer calls its Kotlin classes through JNI,
# which R8 can't see (rule from the crate's README).
-keep, includedescriptorclasses class org.rustls.platformverifier.** { *; }
# The JVM finds RustlsPlatformVerifier.nativeInit in the native library by its
# class and method names.
-keepclasseswithmembernames class com.spruceid.mobile.sdk.rs.RustlsPlatformVerifier {
    native <methods>;
}
