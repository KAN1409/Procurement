plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("kotlin-kapt")
}

android {
    namespace = "com.edib.procurement"
    compileSdk = 35
    buildToolsVersion = "36.1.0"

    signingConfigs {
        getByName("debug") {
            // Checked-in debug key so every build (local or CI) signs with the
            // same certificate. Without this, each machine generates its own
            // throwaway key and Android refuses to install an "update" whose
            // signature does not match.
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.edib.procurement"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf("room.schemaLocation" to "$projectDir/schemas")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
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
        // PDFBox pulls in BouncyCastle for encrypted-PDF support: 8.3 MB of
        // post-quantum tables for a feature a procurement library never uses.
        // The pack only needs to read text out of ordinary documents, and an
        // encrypted PDF is reported as an extraction error instead.
        exclude("org/bouncycastle/**")
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("test").java.srcDirs("src/test/kotlin")
    }
}

dependencies {
    // Procurement Library dependencies only.
    //
    // The application is fully offline and document-control oriented: it needs
    // a database, coroutines, Compose and SAF. It deliberately does NOT carry
    // speech recognition, ONNX Runtime, Apache POI, PDFBox, BouncyCastle, the
    // AppCompat/Material view system, or any cloud client, because none of them
    // is reachable from any code path here. The original host application's code
    // and dependencies live in the ":legacy" module, which is built for
    // continuity but not shipped.
    implementation("androidx.core:core-ktx:1.13.1")

    val room_version = "2.6.1"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    kapt("androidx.room:room-compiler:$room_version")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.compose.material:material-icons-core:1.7.2")

    // Paging 3: every long list in the app is a database-backed Pager, so the
    // UI never holds the whole 3,600-group / 5,900-entry table in memory and a
    // search still queries the full database rather than a loaded page.
    implementation("androidx.paging:paging-runtime-ktx:3.3.2")
    implementation("androidx.paging:paging-compose:3.3.2")
    implementation("androidx.room:room-paging:$room_version")

    // PDF text extraction for the AI Pack.
    //
    // This is the one heavy library the cleanup removed and Phase 3 puts back,
    // deliberately. Extracting PDF text correctly needs font metrics, CID
    // decoding, ToUnicode CMap handling and content-stream interpretation;
    // anything hand-rolled would emit garbled Arabic, which is worse than
    // emitting nothing. Android's own PdfRenderer cannot help: it rasterises a
    // page to a bitmap and never exposes text, and the pack must not render.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Explicit versions rather than a BOM: these are the exact artifacts already
    // present in the local Gradle cache, which keeps offline builds working.
    // Compose tooling (ui-tooling / ui-tooling-preview) is omitted on purpose:
    // it is a design-time preview aid only and adds nothing to the app.
    implementation("androidx.compose.ui:ui:1.7.2")
    implementation("androidx.compose.ui:ui-graphics:1.7.2")
    implementation("androidx.compose.foundation:foundation:1.7.2")
    implementation("androidx.compose.material3:material3:1.3.1")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Test-only. The procurement unit tests are plain JVM: grouping,
    // normalisation, matching and formatting. No Android runtime, no mocking
    // framework and no Arch test rules are needed.
    testImplementation("junit:junit:4.13.2")
    // The real org.json, so manifest compatibility is asserted by parsing the
    // output rather than by string matching. The android.jar stub throws instead.
    testImplementation("org.json:json:20240303")
    testImplementation("org.xerial:sqlite-jdbc:3.45.3.0")

    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
}
