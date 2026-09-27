plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("kotlin-kapt")
}

android {
    namespace = "com.edib.openwhispr"
    compileSdk = 35

    signingConfigs {
        getByName("debug") {
            // Checked-in debug key so every build (local or CI) signs with the
            // same certificate. Without this, each machine/CI run generates
            // its own throwaway debug key, and Android refuses to install an
            // "update" whose signature doesn't match what's already there.
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.edib.openwhispr"
        minSdk = 30
        targetSdk = 35
        // v3.15.1: Local transcription no longer falls back to the cloud
        // provider; model activation requires an explicit action; interface
        // glyphs replaced with vector icons; light-mode polish.
        // Transcription behaviour itself is unchanged from v3.14.0.
        versionCode = 31
        versionName = "3.15.1"

        ndk { abiFilters += "arm64-v8a" }
        
        // Hilt
        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf("room.schemaLocation" to "$projectDir/schemas".toString())
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    @Suppress("DEPRECATION")
    kotlinOptions { jvmTarget = "17" }

    testOptions { unitTests { isIncludeAndroidResources = true } }
    
    packaging {
        resources {
            excludes += listOf("META-INF/AL2.0", "META-INF/LICENSE", "META-INF/NOTICE")
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("org.apache.commons:commons-compress:1.27.1")

    // Room
    val room_version = "2.6.1"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    kapt("androidx.room:room-compiler:$room_version")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    
    // WorkManager
    val work_version = "2.9.0"
    implementation("androidx.work:work-runtime-ktx:$work_version")
    
    // PDF text extraction. Android port of Apache PDFBox; coordinates are
    // com.tom-roush (not org.apache.pdfbox) and it needs PDFBoxResourceLoader.init.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    
    // Excel parsing - Apache POI (try with offline-first approach)
    implementation("org.apache.poi:poi:5.2.3")
    implementation("org.apache.poi:poi-ooxml:5.2.3")
    implementation("org.apache.poi:poi-ooxml-lite:5.2.3")
    
    // JSON
    implementation("com.google.code.gson:gson:2.11.0")
    
    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.8.5")
    
    // Coil for images
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.compose.material:material-icons-core:1.7.2")
    
    // Material 3
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material3:material3-window-size-class:1.3.1")
    
    // Activity Compose
    implementation("androidx.activity:activity-compose:1.9.3")
    
    // ViewModel Compose
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    
    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("org.mockito:mockito-core:5.14.1")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
}
