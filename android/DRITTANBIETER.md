# Drittanbieter-Bibliotheken (Android)

Grundsatz (CLAUDE.md): nur AndroidX/Jetpack und Kotlin, keine Analyse- oder Fremd-SDKs, keine Google Play Services.
Ausnahmen werden hier mit fester Version und Lizenz geführt; der Lizenztext steht auch in der App (Start → „Lizenzen“,
`app/src/main/assets/lizenzen.txt`).

| Bibliothek | Version | Lizenz | Zweck | Netz |
|---|---|---|---|---|
| `com.google.zxing:core` | 3.5.4 | Apache 2.0 | QR-Code aus dem Kamerabild lesen (Ausnahme, entschieden 29.10.2026) | keins |

Nicht verwendet: `zxing-android-embedded`, ML Kit, Google Play Services.

AndroidX/Jetpack (Apache 2.0): Compose (BOM 2026.09.00), Activity 1.13.0, Lifecycle 2.11.0, Core 1.19.1, CameraX 1.6.2.
Kotlin/kotlinx.coroutines (Apache 2.0).
