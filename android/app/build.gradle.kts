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
        versionCode = 1
        versionName = "0.1.0"
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    // org.json gibt es auf dem Gerät; für Unit-Tests auf der JVM die echte Bibliothek
    testImplementation("org.json:json:20260814")
}
