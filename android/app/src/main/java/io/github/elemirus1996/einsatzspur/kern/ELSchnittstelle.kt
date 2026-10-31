package io.github.elemirus1996.einsatzspur.kern

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/** Zugangsdaten der Einsatzleitung (APP_API.md „Rolle Einsatzleitung – native App“). Nur verschlüsselt im Keystore. */
data class ELSitzung(
    val server: ServerAdresse,
    val token: String,
    val expiresAtMs: Long,
    /** Gerätename aus der Anmeldung, geht beim Verlängern wieder mit (nur Audit-Log) */
    val geraet: String? = null,
    /** Bis wann `/verlaengern` höchstens geht (72 h ab Anmeldung mit Passwort), sobald bekannt */
    val verlaengerbarBisMs: Long? = null,
    /** Feste Server-Kennung aus der Anmeldung (Bestätigung der Datenschutzerklärung, Heimatstaffel) */
    val kennung: String? = null,
) {
    fun alsText(): String = JSONObject().apply {
        kennung?.let { put("kennung", it) }
        put("server", server.basis)
        put("token", token)
        put("expiresAtMs", expiresAtMs)
        geraet?.let { put("geraet", it) }
        verlaengerbarBisMs?.let { put("verlaengerbarBisMs", it) }
    }.toString()

    companion object {
        fun ausText(text: String): ELSitzung? = runCatching {
            val json = JSONObject(text)
            ELSitzung(
                server = ServerAdresse.aus(json.getString("server")) ?: return null,
                token = json.getString("token"),
                expiresAtMs = json.getLong("expiresAtMs"),
                geraet = json.textOderNull("geraet"),
                verlaengerbarBisMs = if (json.has("verlaengerbarBisMs")) json.getLong("verlaengerbarBisMs") else null,
                kennung = json.textOderNull("kennung"),
            )
        }.getOrNull()
    }
}

/**
 * Hält das aktuelle Token der Einsatzleitung für alle Teile (Lage, Live, Aktionen, Ortung).
 * Nach `/verlaengern` (#141) gilt nur das neue Token; Anfragen mit dem alten bekommen 410 bzw. `stop`.
 * Das ist dann keine Abmeldung: Der Client wiederholt mit dem neuen Token.
 */
class ELZugang(sitzung: ELSitzung) {
    private val aktuell = AtomicReference(sitzung)
    private val verlaengert = AtomicBoolean(false)

    val sitzung: ELSitzung get() = aktuell.get()
    val verlaengerungLaeuft: Boolean get() = verlaengert.get()

    fun ersetzen(neu: ELSitzung) = aktuell.set(neu)
    fun verlaengerungBeginnt() = verlaengert.set(true)
    fun verlaengerungEndet() = verlaengert.set(false)

    /**
     * Gibt es schon ein anderes Token als [token]? Läuft gerade eine Verlängerung, wird kurz auf ihr Ende gewartet,
     * weil der Server das alte Token sperrt, bevor die Antwort mit dem neuen ankommt.
     */
    suspend fun neueresToken(token: String, wartenMs: Long = 20_000): Boolean {
        var rest = wartenMs
        while (verlaengerungLaeuft && rest > 0) {
            delay(100)
            rest -= 100
        }
        return sitzung.token != token
    }
}

/** Antwort von `GET /lage` der Einsatzleitung (Abschnitt 5). */
data class ELLage(
    val mode: Modus?,
    val serverZeitMs: Long?,
    val einsatz: Einsatz,
    val teams: List<Team>,
    val wetter: Wetter?,
    /** Nur die Version der Datenschutzerklärung (#148) */
    val datenschutzVersion: String? = null,
) {
    data class Einsatz(
        val aktiv: Boolean,
        val text: String? = null,
        val stichwort: String? = null,
        val ort: String? = null,
        val szenario: String? = null,
        val uebung: Boolean = false,
        val beginnMs: Long? = null,
        val einsatzleiter: String? = null,
        val fuehrungsassistent: String? = null,
    )

    data class Geraet(val id: String?, val name: String?, val zeitMs: Long?, val akku: Int?)
    data class Gebiet(val id: String?, val name: String?, val farbe: String?)

    data class Team(
        val teamId: String,
        val teamName: String,
        val dogName: String? = null,
        val hundefuehrerName: String? = null,
        val helfer: List<String> = emptyList(),
        val suche: TeamLage.Suche,
        val warnstufe: Int = 0,
        val warnung1Min: Int? = null,
        val warnung2Min: Int? = null,
        val suchgebiet: Gebiet? = null,
        val halsband: Geraet? = null,
        val handy: Geraet? = null,
        val pauseErforderlichBisMs: Long? = null,
    )

    data class Wetter(val temperatur: Double?, val wind: Double?, val beschreibung: String?, val zeitMs: Long?)

    companion object {
        fun aus(json: JSONObject): ELLage = try {
            val e = json.getJSONObject("einsatz")
            ELLage(
                mode = Modus.aus(json.optString("mode", "")),
                serverZeitMs = isoZuMs(json.textOderNull("serverZeit")),
                einsatz = Einsatz(
                    aktiv = e.getBoolean("aktiv"),
                    text = e.textOderNull("text"),
                    stichwort = e.textOderNull("stichwort"),
                    ort = e.textOderNull("ort"),
                    szenario = e.textOderNull("szenario"),
                    uebung = e.optBoolean("uebung", false),
                    beginnMs = isoZuMs(e.textOderNull("beginn")),
                    einsatzleiter = e.textOderNull("einsatzleiter"),
                    fuehrungsassistent = e.textOderNull("fuehrungsassistent"),
                ),
                teams = json.optJSONArray("teams")?.let { a -> (0 until a.length()).map { team(a.getJSONObject(it)) } } ?: emptyList(),
                wetter = json.objektOderNull("wetter")?.let { w ->
                    Wetter(w.zahlOderNull("temperatur"), w.zahlOderNull("wind"), w.textOderNull("beschreibung"), isoZuMs(w.textOderNull("zeit")))
                },
                datenschutzVersion = Datenschutz.version(json),
            )
        } catch (e: JSONException) {
            throw SchnittstellenFehler.AntwortUngueltig
        }

        private fun team(t: JSONObject) = Team(
            teamId = t.getString("teamId"),
            teamName = t.getString("teamName"),
            dogName = t.textOderNull("dogName"),
            hundefuehrerName = t.textOderNull("hundefuehrerName"),
            helfer = t.optJSONArray("helfer")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            suche = TeamLage.suche(t.getJSONObject("suche")),
            warnstufe = t.optInt("warnstufe", 0),
            warnung1Min = t.ganzOderNull("warnung1Min"),
            warnung2Min = t.ganzOderNull("warnung2Min"),
            suchgebiet = t.objektOderNull("suchgebiet")?.let { Gebiet(it.textOderNull("id"), it.textOderNull("name"), it.textOderNull("farbe")) },
            halsband = t.objektOderNull("halsband")?.let(::geraet),
            handy = t.objektOderNull("handy")?.let(::geraet),
            pauseErforderlichBisMs = t.objektOderNull("pause")?.let { isoZuMs(it.textOderNull("erforderlichBis")) },
        )

        private fun geraet(g: JSONObject) = Geraet(g.textOderNull("id"), g.textOderNull("name"), isoZuMs(g.textOderNull("zeit")), g.ganzOderNull("akku"))
    }
}

/** Live-Position für die Karte (`position` auf `/ereignisse`). */
data class ELPosition(val art: String, val id: String?, val teamId: String?, val lat: Double, val lng: Double, val zeitMs: Long?, val akku: Int?)

/** Live-Ereignisse der Einsatzleitung (Abschnitt 8). */
sealed class ELEreignis {
    data class Team(val teamId: String?) : ELEreignis()
    data class Position(val position: ELPosition) : ELEreignis()
    data object Notiz : ELEreignis()
    data object Funk : ELEreignis()
    data object Vermisste : ELEreignis()
    data class Einsatz(val aktiv: Boolean) : ELEreignis()
    data class Warnung(val teamId: String?, val stufe: Int, val text: String) : ELEreignis()
    data object Stop : ELEreignis()
    data class Verdraengt(val wiederholenNachSec: Int) : ELEreignis()

    companion object {
        fun aus(roh: SseLeser.Ereignis): ELEreignis? {
            val json = runCatching { JSONObject(roh.daten) }.getOrElse { JSONObject() }
            return when (roh.name) {
                "team" -> Team(json.textOderNull("teamId"))
                "position" -> runCatching {
                    Position(ELPosition(json.getString("art"), json.textOderNull("id"), json.textOderNull("teamId"),
                        json.getDouble("lat"), json.getDouble("lng"), isoZuMs(json.textOderNull("zeit")), json.ganzOderNull("akku")))
                }.getOrNull()
                "notiz" -> Notiz
                "funk" -> Funk
                "vermisste" -> Vermisste
                "einsatz" -> Einsatz(json.optBoolean("aktiv", false))
                "warnung" -> Warnung(json.textOderNull("teamId"), json.optInt("stufe", 1), json.textOderNull("text") ?: "Warnung")
                "stop" -> Stop
                "verdraengt" -> Verdraengt(json.optInt("wiederholenNachSec", 60))
                else -> null
            }
        }
    }
}

sealed class ELAnmeldeFehler(nachricht: String) : Exception(nachricht) {
    /** 401 */
    data object PasswortFalsch : ELAnmeldeFehler("Passwort falsch")
    /** 429: gesperrt nach Fehlversuchen */
    data class Gesperrt(val sekunden: Int) : ELAnmeldeFehler("gesperrt")
    /** 409: auf dem Server noch kein Staffel-Passwort eingerichtet */
    data object KeinPasswort : ELAnmeldeFehler("kein Passwort")
    /** Netz nicht erreichbar oder öffentlicher Zugang (404): meist ist das VPN aus */
    data object NichtErreichbar : ELAnmeldeFehler("nicht erreichbar")
    data class Server(val status: Int) : ELAnmeldeFehler("HTTP $status")
    data object AntwortUngueltig : ELAnmeldeFehler("Antwort ungültig")
}

/** 409 auf `/verlaengern`: kein Einsatz läuft oder 72 h erreicht – nicht wiederholen, nach Ablauf neu anmelden. */
class ELVerlaengerAbgelehnt(text: String) : Exception(text)

/** HTTP-Zugang, im Test ersetzbar: (Adresse, Methode, Inhalt, Token) → (Status, Text). */
typealias Transport = suspend (String, String, JSONObject?, String?) -> Pair<Int, String>

/** Offene Verbindung für Server-Sent Events. */
class SseVerbindung(val status: Int, val zeilen: BufferedReader?, val schliessen: () -> Unit)

/** Öffnet `/ereignisse` (blockierend, auf IO aufrufen), im Test ersetzbar. */
typealias SseTransport = (String, String) -> SseVerbindung

/** Ergebnis der Anmeldung: Sitzung, Server-Info und Datenschutzerklärung der Staffel (#148). */
data class ELAngemeldet(val sitzung: ELSitzung, val server: ServerInfo?, val datenschutz: DatenschutzAngabe)

/** Anmeldung der Einsatzleitung (`POST /login`), nur an der internen Adresse im VPN. */
class ELAnmeldung(private val server: ServerAdresse, private val transport: Transport = ::http) {
    suspend fun anmelden(passwort: String, geraet: String?): ELAngemeldet {
        val name = geraet?.trim()?.take(40)?.ifEmpty { null }
        val inhalt = JSONObject().put("passwort", passwort).apply { name?.let { put("geraet", it) } }
        val (status, text) = try {
            transport(server.pfad("api/el/app/v1/login"), "POST", inhalt, null)
        } catch (e: IOException) {
            throw ELAnmeldeFehler.NichtErreichbar
        }
        val json = runCatching { JSONObject(text) }.getOrNull()
        return when (status) {
            in 200..299 -> {
                val token = json?.textOderNull("token") ?: throw ELAnmeldeFehler.AntwortUngueltig
                val ablauf = isoZuMs(json.textOderNull("expiresAt")) ?: throw ELAnmeldeFehler.AntwortUngueltig
                val info = ServerInfo.aus(json.objektOderNull("server"))
                ELAngemeldet(ELSitzung(server, token, ablauf, geraet = name, kennung = info?.kennung), info, DatenschutzAngabe.aus(json))
            }
            401 -> throw ELAnmeldeFehler.PasswortFalsch
            429 -> throw ELAnmeldeFehler.Gesperrt(json?.optInt("wiederholenNachSec", 300) ?: 300)
            409 -> throw ELAnmeldeFehler.KeinPasswort
            // Öffentlicher Zugang: alle EL-Pfade 404 → nicht im VPN
            404 -> throw ELAnmeldeFehler.NichtErreichbar
            else -> throw ELAnmeldeFehler.Server(status)
        }
    }
}

/** Was die Übersicht der Einsatzleitung braucht (testbar ohne Netz). */
interface ELLageSchnittstelle {
    suspend fun lage(): ELLage
    /** Server-Sent Events; endet, wenn die Verbindung abbricht. 410 beim Verbinden → [SchnittstellenFehler.Beendet]. */
    fun ereignisse(): Flow<ELEreignis>
}

/** Was die Verlängerung braucht (testbar ohne Netz). */
interface ELVerlaengerSchnittstelle {
    suspend fun verlaengern(geraet: String?): ELSitzung
}

/**
 * Endpunkte der Einsatzleitung mit Token. Erfüllt [TeamSchnittstelle], damit dieselbe Ortungssteuerung ([TeamOrtung])
 * den Standort der Einsatzleitung sendet (`POST /positions`, `GET /status`).
 */
class ELClient(
    val zugang: ELZugang,
    private val transport: Transport = ::http,
    private val sse: SseTransport = ::sseOeffnen,
) : TeamSchnittstelle, ELLageSchnittstelle, ELVerlaengerSchnittstelle, ELAktionsSchnittstelle {

    val sitzung: ELSitzung get() = zugang.sitzung

    override suspend fun config(): TeamConfig = throw SchnittstellenFehler.Server(404)

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
        return TeamClient.steuerAntwort(anfrage("positions", "POST", JSONObject().put("positions", liste)))
    }

    override suspend fun status(): SteuerAntwort = TeamClient.steuerAntwort(anfrage("status", "GET", null))

    override suspend fun lage(): ELLage = ELLage.aus(anfrage("lage", "GET", null))

    /** Ganze Erklärung zum Nachlesen bzw. bei neuer Version; 404 = keine hinterlegt (#148) */
    suspend fun datenschutz(): Datenschutz? = datenschutzAbrufen(url("datenschutz"), sitzung.token, transport)

    /** Logo der Staffel als PNG; 404 = keins */
    suspend fun logo(): ByteArray? = logoAbrufen(url("logo"), sitzung.token)

    /** Neues Token ohne Passwort (#141). Das alte ist danach gesperrt; ersetzt wird erst in [ELVerlaengerung]. */
    override suspend fun verlaengern(geraet: String?): ELSitzung {
        val alt = sitzung
        val inhalt = JSONObject().apply { geraet?.let { put("geraet", it.take(40)) } }
        val (status, text) = transport(url("verlaengern"), "POST", inhalt, alt.token)
        val json = runCatching { JSONObject(text) }.getOrNull()
        return when (status) {
            in 200..299 -> {
                val token = json?.textOderNull("token") ?: throw SchnittstellenFehler.AntwortUngueltig
                val ablauf = isoZuMs(json.textOderNull("expiresAt")) ?: throw SchnittstellenFehler.AntwortUngueltig
                alt.copy(token = token, expiresAtMs = ablauf, verlaengerbarBisMs = isoZuMs(json.textOderNull("verlaengerbarBis")))
            }
            409 -> throw ELVerlaengerAbgelehnt(json?.textOderNull("error") ?: "Verlängern nicht möglich.")
            410 -> throw SchnittstellenFehler.Beendet
            else -> throw SchnittstellenFehler.Server(status)
        }
    }

    override suspend fun ausfuehren(aktion: ELAktion): String {
        val (status, text) = roh(aktion.pfad, "POST", aktion.koerper())
        return when (status) {
            in 200..299 -> text
            410 -> throw SchnittstellenFehler.Beendet
            // 400 falsche Eingabe, 404 unbekanntes Team bzw. Notiz, 409 nicht erlaubt (auch: kein Einsatz läuft)
            400, 404, 409 -> throw ELAktionAbgelehnt(
                runCatching { JSONObject(text).textOderNull("error") }.getOrNull() ?: if (status == 404) "Nicht gefunden." else "Nicht möglich.",
            )
            else -> throw SchnittstellenFehler.Server(status)
        }
    }

    override suspend fun notizen(vor: String?): ELNotizSeite =
        ELNotizSeite.aus(anfrage(if (vor != null) "notizen?vor=${java.net.URLEncoder.encode(vor, "UTF-8")}&anzahl=50" else "notizen?anzahl=50", "GET", null))

    override suspend fun halsbaender(): ELHalsbaender = ELHalsbaender.aus(anfrage("halsbaender", "GET", null))

    /** Token auf dem Server ungültig machen (best effort). */
    suspend fun abmelden() {
        runCatching { transport(url("logout"), "POST", null, sitzung.token) }
    }

    /**
     * Server-Sent Events wie in der Team-Rolle. Gibt es inzwischen ein neueres Token (#141), ist 410 bzw. `stop`
     * keine Abmeldung: Der Strom endet, die Ansicht verbindet sich mit dem neuen Token neu.
     */
    override fun ereignisse(): Flow<ELEreignis> = callbackFlow {
        var verbindung: SseVerbindung? = null
        launch(Dispatchers.IO) {
            try {
                var token = sitzung.token
                var v = sse(url("ereignisse"), token).also { verbindung = it }
                if (v.status == 410 && zugang.neueresToken(token)) {
                    v.schliessen()
                    token = sitzung.token
                    v = sse(url("ereignisse"), token).also { verbindung = it }
                }
                if (v.status == 410) throw SchnittstellenFehler.Beendet
                if (v.status != 200) throw SchnittstellenFehler.Server(v.status)
                val leser = SseLeser()
                v.zeilen?.use { eingabe ->
                    while (true) {
                        val zeile = eingabe.readLine() ?: break
                        val ereignis = leser.zeile(zeile)?.let(ELEreignis::aus) ?: continue
                        // Altes Token nach /verlaengern: Verbindung endet ohne stop
                        if (ereignis == ELEreignis.Stop && zugang.neueresToken(token)) break
                        send(ereignis)
                    }
                }
                close()
            } catch (e: Exception) {
                close(e)
            }
        }
        awaitClose { verbindung?.schliessen?.invoke() }
    }.flowOn(Dispatchers.IO)

    private fun url(endpunkt: String) = sitzung.server.pfad("api/el/app/v1/$endpunkt")

    /** 410 mit einem inzwischen ersetzten Token (#141) wird einmal mit dem neuen wiederholt. */
    private suspend fun roh(endpunkt: String, methode: String, inhalt: JSONObject?): Pair<Int, String> {
        val token = sitzung.token
        val ergebnis = transport(url(endpunkt), methode, inhalt, token)
        if (ergebnis.first != 410 || !zugang.neueresToken(token)) return ergebnis
        return transport(url(endpunkt), methode, inhalt, sitzung.token)
    }

    private suspend fun anfrage(endpunkt: String, methode: String, inhalt: JSONObject?): JSONObject {
        val (status, text) = roh(endpunkt, methode, inhalt)
        if (status == 410) throw SchnittstellenFehler.Beendet
        if (status !in 200..299) throw SchnittstellenFehler.Server(status)
        return try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw SchnittstellenFehler.AntwortUngueltig
        }
    }
}

/** Öffnet eine SSE-Verbindung (nur HTTPS, Token nur im Header). Blockierend, auf IO aufrufen. */
fun sseOeffnen(adresse: String, token: String): SseVerbindung {
    val url = URL(adresse)
    if (url.protocol != "https") throw IOException("nur https")
    val verbindung = url.openConnection() as HttpsURLConnection
    verbindung.connectTimeout = 20_000
    // Der Server schickt alle 20 s „: ping“; nach 60 s Stille gilt die Verbindung als tot
    verbindung.readTimeout = 60_000
    verbindung.useCaches = false
    verbindung.instanceFollowRedirects = false
    verbindung.setRequestProperty("Accept", "text/event-stream")
    verbindung.setRequestProperty("Authorization", "Bearer $token")
    val status = verbindung.responseCode
    val zeilen = if (status == 200) verbindung.inputStream.bufferedReader() else null
    return SseVerbindung(status, zeilen) { verbindung.disconnect() }
}

internal fun JSONObject.zahlOderNull(name: String): Double? = if (has(name) && !isNull(name)) optDouble(name) else null
internal fun JSONObject.ganzOderNull(name: String): Int? = if (has(name) && !isNull(name)) optInt(name) else null
