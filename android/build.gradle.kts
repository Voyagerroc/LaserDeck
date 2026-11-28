// AGP 9 ships built-in Kotlin support, so the org.jetbrains.kotlin.android plugin
// is no longer applied. The Kotlin Gradle plugin is put on the build classpath
// to override the Kotlin version bundled with AGP.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.5.3" apply false
}
