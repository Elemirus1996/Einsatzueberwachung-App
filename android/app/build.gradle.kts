import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Testschlüssel für die Test-APK liegt außerhalb des Repos (~/.einsatzspur/test-signierung.properties)
val testSignierung = Properties().apply {
    val datei = File(System.getProperty("user.home"), ".einsatzspur/test-signierung.properties")
    if (datei.exists()) datei.inputStream().use { load(it) }
}

android {
    namespace = "io.github.elemirus1996.einsatzspur"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.elemirus1996.einsatzspur"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        if (!testSignierung.isEmpty) {
            create("test") {
                storeFile = File(testSignierung.getProperty("storeFile"))
                storePassword = testSignierung.getProperty("storePassword")
                keyAlias = testSignierung.getProperty("keyAlias")
                keyPassword = testSignierung.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Ohne Testschlüssel bleibt die Release-APK unsigniert (nicht installierbar)
            signingConfig = signingConfigs.findByName("test")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Nur AndroidX/Jetpack und Kotlin – keine Google Play Services, keine Fremd-SDKs
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    // Einzige Fremdbibliothek (Ausnahme in CLAUDE.md, 29.10.2026): QR-Auswertung, Apache 2.0, kein Netz
    implementation("com.google.zxing:core:3.5.4")
    // Zweite Ausnahme (CLAUDE.md, 29.10.2026): Karte. BSD-2, Kacheln nur von OSM/OpenTopoMap, keine Telemetrie
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    // Von MapLibre ohnehin genutzt; ausdrücklich eingebunden für den eigenen Client mit Host-Freigabeliste
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    // org.json gibt es auf dem Gerät; für Unit-Tests auf der JVM die echte Bibliothek
    testImplementation("org.json:json:20260814")
}
