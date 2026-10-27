import java.util.Properties

// AGP 9 compiles Kotlin itself ("built-in Kotlin"), so the kotlin-android
// plugin is deliberately absent: applying it here is now a hard error.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

// Signing is what makes the APK installable by hand. Android refuses an
// unsigned package outright, and it identifies an installed app by its key, so
// the store lives beside this file and its passwords are read from
// keystore.properties rather than being baked into the build script.
val keystoreFile = file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
}

android {
    namespace = "com.remotectl.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.remotectl.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    flavorDimensions += "disguise"
    productFlavors {
        create("default") { dimension = "disguise"; applicationIdSuffix = "" }
        create("instagram") { dimension = "disguise"; applicationIdSuffix = ".instagram" }
        create("twitter") { dimension = "disguise"; applicationIdSuffix = ".twitter" }
        create("facebook") { dimension = "disguise"; applicationIdSuffix = ".facebook" }
        create("linkedin") { dimension = "disguise"; applicationIdSuffix = ".linkedin" }
        create("messenger") { dimension = "disguise"; applicationIdSuffix = ".messenger" }
        create("youtube") { dimension = "disguise"; applicationIdSuffix = ".youtube" }
        create("snapchat") { dimension = "disguise"; applicationIdSuffix = ".snapchat" }
        create("tiktok") { dimension = "disguise"; applicationIdSuffix = ".tiktok" }
    }

    signingConfigs {
        create("release") {
            // Left unset only if keystore.properties is missing; in that case
            // the release build below stays unsigned, which is what it was
            // before, so a fresh checkout still configures cleanly.
            if (keystoreFile.exists()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Sources live under src/main/kotlin. With built-in Kotlin the only
    // supported way to register extra Kotlin directories is via the source
    // set's kotlin set, so it is declared explicitly here.
    sourceSets.getByName("main") {
        kotlin.directories += "src/main/kotlin"
    }
}

// No kotlin { compilerOptions { jvmTarget } } block: built-in Kotlin already
// defaults jvmTarget to android.compileOptions.targetCompatibility (17).

// Deliberately no AndroidX: this app is plain framework views and services, so
// pulling in appcompat/material/lifecycle would only slow the build down.
dependencies {
    implementation(project(":protocol"))
    implementation(libs.okhttp)
}
