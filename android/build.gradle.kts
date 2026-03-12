// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("com.android.library") version "8.11.1" apply false
    id("com.gradleup.nmcp") version "1.4.4" apply false
    id("com.gradleup.nmcp.aggregation") version "1.4.4"
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
    id("com.github.willir.rust.cargo-ndk-android") version "0.3.4" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("com.google.dagger.hilt.android") version "2.51.1" apply false
}

nmcpAggregation {
    centralPortal {
        username = System.getenv("MAVEN_USERNAME")
        password = System.getenv("MAVEN_PASSWORD")
        publishingType = "AUTOMATIC"
    }
}

dependencies {
    nmcpAggregation(project(":MobileSdkRs"))
    nmcpAggregation(project(":MobileSdk"))
}