plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pockethost.app"
    compileSdk = 34
    // Must match the NDK installed in CI (.github/workflows/release.yml).
    // Without this AGP silently picks the newest local NDK, so local and
    // CI builds could compile native code with different toolchains.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.pockethost.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Keep English + Ukrainian (device locale). Library values-uk
        // no longer stripped, so the Ukrainian UI is not lost.
        resourceConfigurations += listOf("en", "uk")

        // Per-ABI APKs for GitHub Releases:
        // PocketHost-v1.0.0-arm64-v8a.apk, -armeabi-v7a.apk, -x86_64.apk.
        // The in-app updater picks the asset matching Build.SUPPORTED_ABIS
        // (universal `*universal*.apk` also accepted as fallback).
        // In-process JVM launcher (порт Mojo jre_launcher): збірка libpockethost_jvm.so
        externalNativeBuild {
            cmake {
                cFlags += "-std=c11 -Wall"
                cppFlags += "-Wall"
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            // Sideload distribution without a private key yet: sign with the
            // local debug key so the APK is installable. All assets of one
            // release must be built on the SAME machine (same debug key),
            // otherwise updates fail with a signature mismatch.
            // TODO: replace with a real release keystore before Play Store.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":plugins:minecraft"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    implementation("com.google.android.material:material:1.11.0")

    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("androidx.work:work-runtime-ktx:2.9.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.tukaani:xz:1.9")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
