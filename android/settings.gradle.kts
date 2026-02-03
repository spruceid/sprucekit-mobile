pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // AndroidX Credential snapshots - needed for WASI support in WASM matchers
        maven(url="https://androidx.dev/snapshots/builds/14144115/artifacts/repository")
    }
}

rootProject.name = "SpruceKitMobile"
include(":Showcase")
include(":MobileSdk")
include(":MobileSdkRs")
