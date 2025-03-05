// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.23" apply false
    id("com.android.library") version "8.7.2" apply false
    id("com.gradleup.nmcp") version "0.0.4" apply true
    id("com.google.devtools.ksp") version "1.9.23-1.0.20" apply false
    id("com.github.willir.rust.cargo-ndk-android") version "0.3.4" apply false
}
