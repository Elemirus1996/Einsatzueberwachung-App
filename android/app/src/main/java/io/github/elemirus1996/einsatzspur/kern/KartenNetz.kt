package io.github.elemirus1996.einsatzspur.kern

import android.util.Log
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicInteger

/**
 * Netz der Karte: MapLibre darf nur die Kachelserver erreichen (wie die Karte auf dem Server).
 * Jede andere Adresse (Telemetrie, Mapbox, Schriften …) wird blockiert, bevor etwas das Gerät verlässt.
 * Eigener User-Agent nach den Nutzungsregeln von OpenStreetMap.
 */
object KartenNetz {
    val ERLAUBTE_HOSTS = setOf(
        "tile.openstreetmap.org",
        "a.tile.opentopomap.org",
        "b.tile.opentopomap.org",
        "c.tile.opentopomap.org",
    )

    /** Für den Beleg „keine anderen Verbindungen“ (Debug-Ausgabe nur mit Hostnamen, nie Positionen). */
    val erlaubt = AtomicInteger()
    val blockiert = AtomicInteger()

    fun istErlaubt(scheme: String, host: String): Boolean = scheme == "https" && host.lowercase() in ERLAUBTE_HOSTS

    fun client(version: String, protokollieren: Boolean): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { kette ->
            val anfrage = kette.request()
            if (!istErlaubt(anfrage.url.scheme, anfrage.url.host)) {
                blockiert.incrementAndGet()
                if (protokollieren) Log.w(TAG, "blockiert: ${anfrage.url.host}")
                return@Interceptor Response.Builder()
                    .request(anfrage).protocol(Protocol.HTTP_1_1).code(403).message("blockiert")
                    .body("".toResponseBody(null)).build()
            }
            erlaubt.incrementAndGet()
            if (protokollieren && erlaubt.get() % 50 == 1) Log.i(TAG, "Kacheln von ${anfrage.url.host}, bisher ${erlaubt.get()}")
            kette.proceed(anfrage.newBuilder().header("User-Agent", "EinsatzSpur/$version").build())
        })
        .build()

    private const val TAG = "EinsatzSpurKarte"
}
