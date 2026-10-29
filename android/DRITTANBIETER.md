# Drittanbieter-Bibliotheken (Android)

Grundsatz (CLAUDE.md): nur AndroidX/Jetpack und Kotlin, keine Analyse- oder Fremd-SDKs, keine Google Play Services.
Ausnahmen werden hier mit fester Version und Lizenz geführt; der Lizenztext steht auch in der App (Start → „Lizenzen“,
`app/src/main/assets/lizenzen.txt`).

| Bibliothek | Version | Lizenz | Zweck | Netz |
|---|---|---|---|---|
| `com.google.zxing:core` | 3.5.4 | Apache 2.0 | QR-Code aus dem Kamerabild lesen (Ausnahme, entschieden 29.10.2026) | keins |
| `org.maplibre.gl:android-sdk` | 13.7.1 | BSD-2-Clause | Karte (Ausnahme, entschieden 29.10.2026) | nur Kachelserver, siehe unten |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | Apache 2.0 | HTTP für die Karte (von MapLibre genutzt), mit eigener Host-Freigabeliste | nur Kachelserver |

Von MapLibre/CameraX mitgebracht (ohne eigenes Netz): `android-sdk-geojson`/`-turf` 6.0.1 und
`maplibre-android-gestures` 0.0.4 (BSD-2), Okio 3.6.0, Gson 2.10.1, Guava 33.3.1, Timber 5.0.1, Dagger 2.59 (alle Apache 2.0).

### Karte: Netz und Telemetrie

- Kacheln nur von `tile.openstreetmap.org` und `a/b/c.tile.opentopomap.org` (wie die Karte auf dem Server), HTTPS.
- Alle Anfragen von MapLibre laufen über `HttpRequestUtil.setOkHttpClient` durch `KartenNetz`: jeder andere Host wird
  **vor dem Senden** mit 403 abgewiesen und gezählt; User-Agent `EinsatzSpur/<Version>`.
- Kein API-Schlüssel, kein Mapbox-Dienst; MapLibre Native enthält kein Telemetriemodul. Im Abhängigkeitsbaum der
  Release-Variante gibt es keine Artefakte mit telemetry/mapbox/gms/firebase/play-services/analytics.
- Kachel-Cache von MapLibre auf 200 MB (Nutzungsregeln OSM); Quellenangabe immer sichtbar auf der Karte.

Nicht verwendet: `zxing-android-embedded`, ML Kit, Google Play Services.

AndroidX/Jetpack (Apache 2.0): Compose (BOM 2026.09.00), Activity 1.13.0, Lifecycle 2.11.0, Core 1.19.1, CameraX 1.6.2.
Kotlin/kotlinx.coroutines (Apache 2.0).
