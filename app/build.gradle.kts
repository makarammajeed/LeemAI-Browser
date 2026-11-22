plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The workflow passes the newest stable GeckoView version. Change the fallback if you build locally.
val geckoVersion = (project.findProperty("geckoVersion") as String?) ?: "153.0.+"

android {
    namespace = "app.litebrowser"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.litebrowser"
        minSdk = 26            // Android 8.0 (GeckoView's own minimum)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    flavorDimensions += "abi"
    productFlavors {
        create("arm64") { dimension = "abi"; ndk { abiFilters += "arm64-v8a" } }
        create("armv7") { dimension = "abi"; ndk { abiFilters += "armeabi-v7a" } }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")   // self-signed, fine for personal sideloading
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs { useLegacyPackaging = true } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    "arm64Implementation"("org.mozilla.geckoview:geckoview-arm64-v8a:$geckoVersion")
    "armv7Implementation"("org.mozilla.geckoview:geckoview-armeabi-v7a:$geckoVersion")
}
