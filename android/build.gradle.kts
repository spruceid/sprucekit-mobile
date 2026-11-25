// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("com.android.library") version "8.11.1" apply false
    id("com.google.devtools.ksp") version "2.3.14" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
    id("app.cash.licensee") version "1.14.1" apply false
}
