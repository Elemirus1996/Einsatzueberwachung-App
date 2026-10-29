package io.github.elemirus1996.einsatzspur.kern

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

sealed class SchnittstellenFehler(nachricht: String) : Exception(nachricht) {
    /** HTTP 410: Token ungültig, abgelaufen, Einsatz vorbei oder Team gelöscht. Immer als `stop` behandeln. */
    data object Beendet : SchnittstellenFehler("beendet")
    data class Server(val status: Int) : SchnittstellenFehler("HTTP $status")
    data object AntwortUngueltig : SchnittstellenFehler("Antwort ungültig")
}

data class TeamConfig(val teamName: String, val steuerung: SteuerAntwort)

/** Endpunkte der Rolle Team unter `api/team-mobile/app/v1`. */
interface TeamSchnittstelle {
    suspend fun config(): TeamConfig
    suspend fun positionenSenden(positionen: List<Position>): SteuerAntwort
    suspend fun status(): SteuerAntwort
}

class TeamClient(private val sitzung: TeamSitzung) : TeamSchnittstelle {

    override suspend fun config(): TeamConfig {
        val json = anfrage("config", "GET", null)
        return try {
            TeamConfig(json.getString("teamName"), steuerAntwort(json))
        } catch (e: JSONException) {
            throw SchnittstellenFehler.AntwortUngueltig
        }
    }

    override suspend fun positionenSenden(positionen: List<Position>): SteuerAntwort {
        val liste = JSONArray()
        positionen.forEach { p ->
            liste.put(JSONObject().apply {
                put("lat", p.lat)
                put("lng", p.lng)
                p.accuracy?.let { put("accuracy", it) }
                put("timestamp", p.timestamp)
                p.battery?.let { put("battery", it) }
            })
        }
        return steuerAntwort(anfrage("positions", "POST", JSONObject().put("positions", liste)))
    }

    override suspend fun status(): SteuerAntwort = steuerAntwort(anfrage("status", "GET", null))

    private suspend fun anfrage(endpunkt: String, methode: String, inhalt: JSONObject?): JSONObject =
        http(sitzung.server.pfad("api/team-mobile/app/v1/$endpunkt"), methode, inhalt, sitzung.token).let { (status, text) ->
            if (status == 410) throw SchnittstellenFehler.Beendet
            if (status !in 200..299) throw SchnittstellenFehler.Server(status)
            try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw SchnittstellenFehler.AntwortUngueltig
            }
        }

    companion object {
        /** Antwort von /positions und /status; fehlende Felder bleiben `null`. */
        fun steuerAntwort(json: JSONObject): SteuerAntwort {
            val modus = Modus.aus(json.optString("mode", "")) ?: throw SchnittstellenFehler.AntwortUngueltig
            fun zahl(name: String): Int? = if (json.has(name) && !json.isNull(name)) json.optInt(name) else null
            return SteuerAntwort(
                mode = modus,
                intervalSec = zahl("intervalSec"),
                uploadEverySec = zahl("uploadEverySec"),
                pauseCheckSec = zahl("pauseCheckSec"),
                expiresAtMs = isoZuMs(json.optString("expiresAt", "")),
            )
        }
    }
}

/**
 * Nur HTTPS, ohne Cache und Cookies. Liefert Status und Text; Netzfehler als [IOException].
 * Token und Inhalt werden nirgends protokolliert.
 */
internal suspend fun http(adresse: String, methode: String, inhalt: JSONObject?, token: String?): Pair<Int, String> =
    withContext(Dispatchers.IO) {
        val url = URL(adresse)
        if (url.protocol != "https") throw IOException("nur https")
        val verbindung = url.openConnection() as HttpsURLConnection
        try {
            verbindung.requestMethod = methode
            verbindung.connectTimeout = 20_000
            verbindung.readTimeout = 20_000
            verbindung.useCaches = false
            verbindung.instanceFollowRedirects = false
            verbindung.setRequestProperty("Accept", "application/json")
            token?.let { verbindung.setRequestProperty("Authorization", "Bearer $it") }
            if (inhalt != null) {
                verbindung.doOutput = true
                verbindung.setRequestProperty("Content-Type", "application/json")
                verbindung.outputStream.use { it.write(inhalt.toString().toByteArray()) }
            }
            val status = verbindung.responseCode
            val strom = if (status >= HttpURLConnection.HTTP_BAD_REQUEST) verbindung.errorStream else verbindung.inputStream
            status to (strom?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            verbindung.disconnect()
        }
    }
