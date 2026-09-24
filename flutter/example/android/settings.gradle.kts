pluginManagement {
    val flutterSdkPath =
        run {
            val properties = java.util.Properties()
            file("local.properties").inputStream().use { properties.load(it) }
            val flutterSdkPath = properties.getProperty("flutter.sdk")
            require(flutterSdkPath != null) { "flutter.sdk not set in local.properties" }
            flutterSdkPath
        }

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Local-only: build the SpruceKit Android SDK from source instead of consuming
// the published Maven Central artifact. Lets you iterate on `android/MobileSdk`
// (and `android/MobileSdkRs`) and have `flutter run` pick up the changes
// without `publishToMavenLocal`. Set SPRUCEKIT_SDK_FROM_SOURCE=false to use
// the published artifact instead. CI does, to check the plugin against what
// consumers get.
if (System.getenv("SPRUCEKIT_SDK_FROM_SOURCE") != "false") {
    includeBuild("../../../android") {
        dependencySubstitution {
            substitute(module("com.spruceid.mobile.sdk:mobilesdk"))
                .using(project(":MobileSdk"))
            substitute(module("com.spruceid.mobile.sdk.rs:mobilesdkrs"))
                .using(project(":MobileSdkRs"))
        }
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}

include(":app")
