plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("dagger.hilt.android.plugin")
    id("kotlin-kapt")
    id("app.cash.licensee")
}

// Full allowlist is in rust/deny.toml. Only licenses actually present in this
// module's dependencies are listed so licensee flags any new license type.
licensee {
    allow("Apache-2.0")
    allow("BSD-3-Clause")

    // Some dependencies use license URLs instead of SPDX identifiers in their POM
    // metadata. Each is compatible with our license allowlist (permissive and
    // file-copyleft licenses like MPL-2.0 are permitted):

    // Android SDK Terms -- Google Play Services, ML Kit, and related libraries.
    // Free-to-use binaries with no copyleft or source-disclosure obligations.
    allowUrl("https://developer.android.com/studio/terms.html")
    allowUrl("https://developers.google.com/ml-kit/terms")
    allowUrl("https://developer.android.com/guide/playcore/license")
    allowUrl("https://developer.android.com/google/play/integrity/overview#tos")

    // Rive Android -- MIT-licensed (see repo LICENSE file); POM points to the
    // GitHub URL instead of an SPDX identifier.
    allowUrl("https://github.com/rive-app/rive-android/blob/master/LICENSE")

    // slf4j -- MIT-licensed; uses a non-standard URL for the MIT license text.
    allowUrl("https://opensource.org/license/mit")
}

android {
    namespace = "com.spruceid.mobilesdkexample"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spruceid.mobilesdkexample"
        minSdk = 26
        targetSdk = 35
        versionCode = 60
        versionName = "1.18.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }
    lint {
        disable += "ObsoleteLintCustomCheck"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    ndkVersion = "29.0.13599879 rc2"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val roomVersion = "2.7.2"
    val hiltVersion = "2.56.2"

    implementation("androidx.room:room-runtime:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    annotationProcessor("androidx.room:room-compiler:$roomVersion")
    implementation("com.google.dagger:hilt-android:$hiltVersion")
    kapt("com.google.dagger:hilt-compiler:$hiltVersion")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("com.google.accompanist:accompanist-permissions:0.37.3")
    implementation("com.google.accompanist:accompanist-swiperefresh:0.36.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("app.rive:rive-android:10.2.1")
    implementation("androidx.startup:startup-runtime:1.2.0")
    implementation(project(mapOf("path" to ":MobileSdk")))
    implementation("com.google.zxing:core:3.5.3")
    implementation("io.ktor:ktor-client-core:3.2.2")
    implementation("io.ktor:ktor-client-cio:3.2.2")
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // DC-API dependencies
    implementation("androidx.credentials.registry:registry-provider:1.0.0-alpha01")
}
