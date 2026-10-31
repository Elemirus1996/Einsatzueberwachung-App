package io.github.elemirus1996.einsatzspur.kern

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class Koordinate(val lat: Double, val lng: Double)

data class Halsband(
    val id: String? = null,
    val name: String? = null,
    val lat: Double,
    val lng: Double,
    val zeitMs: Long,
    val akku: Int? = null,
)

data class Mitteilung(val id: String, val text: String, val zeitMs: Long)

/** Antwort von `GET /lage` (APP_API.md, Abschnitt 6). */
data class TeamLage(
    val mode: Modus?,
    val serverZeitMs: Long?,
    val einsatzText: String?,
    val uebung: Boolean,
    val staffel: String?,
    val teamId: String,
    val teamName: String,
    val dogName: String?,
    val hundefuehrerName: String?,
    val helfer: List<String>,
    val suche: Suche,
    val suchgebiet: Suchgebiet?,
    val halsband: Halsband?,
    val mitteilungen: List<Mitteilung>,
) {
    enum class SucheStatus { BEREIT, LAEUFT, KURZPAUSE, BEENDET }
    /** `darfSteuern`: Das Team darf die Suche selbst steuern (Abschnitt 11); ohne Angabe (älterer Server) nein. */
    data class Suche(val status: SucheStatus, val seitMs: Long? = null, val dauerSec: Int? = null, val darfSteuern: Boolean = false)
    data class Suchgebiet(val id: String?, val name: String?, val farbe: String?, val punkte: List<Koordinate>)

    companion object {
        fun aus(json: JSONObject): TeamLage = try {
            val einsatz = json.getJSONObject("einsatz")
            val team = json.getJSONObject("team")
            val suche = json.getJSONObject("suche")
            TeamLage(
                mode = Modus.aus(json.optString("mode", "")),
                serverZeitMs = isoZuMs(json.optString("serverZeit", "")),
                einsatzText = einsatz.textOderNull("text"),
                uebung = einsatz.optBoolean("uebung", false),
                staffel = einsatz.textOderNull("staffel"),
                teamId = team.getString("teamId"),
                teamName = team.getString("teamName"),
                dogName = team.textOderNull("dogName"),
                hundefuehrerName = team.textOderNull("hundefuehrerName"),
                helfer = team.optJSONArray("helfer")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                suche = suche(suche),
                suchgebiet = json.objektOderNull("suchgebiet")?.let { g ->
                    Suchgebiet(g.textOderNull("id"), g.textOderNull("name"), g.textOderNull("farbe"), punkte(g.getJSONArray("punkte")))
                },
                halsband = json.objektOderNull("halsband")?.let(::halsband),
                mitteilungen = json.optJSONArray("mitteilungen")?.let { a ->
                    (0 until a.length()).map { mitteilung(a.getJSONObject(it)) }
                } ?: emptyList(),
            )
        } catch (e: JSONException) {
            throw SchnittstellenFehler.AntwortUngueltig
        } catch (e: NullPointerException) {
            throw SchnittstellenFehler.AntwortUngueltig
        }

        fun suche(suche: JSONObject) = Suche(
            status = when (suche.getString("status")) {
                "laeuft" -> SucheStatus.LAEUFT
                "kurzpause" -> SucheStatus.KURZPAUSE
                "beendet" -> SucheStatus.BEENDET
                else -> SucheStatus.BEREIT
            },
            seitMs = isoZuMs(suche.textOderNull("seit")),
            dauerSec = if (!suche.has("dauerSec") || suche.isNull("dauerSec")) null else suche.optInt("dauerSec"),
            darfSteuern = suche.optBoolean("darfSteuern", false),
        )

        fun halsband(h: JSONObject) = Halsband(
            id = h.textOderNull("id"),
            name = h.textOderNull("name"),
            lat = h.getDouble("lat"),
            lng = h.getDouble("lng"),
            zeitMs = isoZuMs(h.getString("zeit")) ?: throw SchnittstellenFehler.AntwortUngueltig,
            akku = if (h.has("akku") && !h.isNull("akku")) h.getInt("akku") else null,
        )

        fun mitteilung(m: JSONObject) = Mitteilung(
            id = m.getString("id"),
            text = m.getString("text"),
            zeitMs = isoZuMs(m.getString("zeit")) ?: throw SchnittstellenFehler.AntwortUngueltig,
        )

        private fun punkte(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it).let { p -> Koordinate(p.getDouble("lat"), p.getDouble("lng")) } }
    }
}

/** Antwort von `GET /tracks` (Abschnitt 7). `t` in Unix-Millisekunden. */
data class TeamTracks(val hund: List<Punkt>, val mensch: List<Punkt>, val abgeschlossen: List<Track>) {
    data class Punkt(val lat: Double, val lng: Double, val t: Long?)
    data class Track(val art: String, val farbe: String?, val punkte: List<Punkt>)

    /** Größter Zeitpunkt der laufenden Suche, für `?seit=`. */
    val letzterZeitpunkt: Long? get() = (hund + mensch).mapNotNull { it.t }.maxOrNull()

    companion object {
        fun aus(json: JSONObject): TeamTracks = try {
            val laufend = json.getJSONObject("laufend")
            TeamTracks(
                hund = punkte(laufend.getJSONArray("hund")),
                mensch = punkte(laufend.getJSONArray("mensch")),
                abgeschlossen = json.optJSONArray("abgeschlossen")?.let { a ->
                    (0 until a.length()).flatMap { i ->
                        val tracks = a.getJSONObject(i).getJSONArray("tracks")
                        (0 until tracks.length()).map { j ->
                            val t = tracks.getJSONObject(j)
                            Track(t.getString("art"), t.textOderNull("farbe"), punkte(t.getJSONArray("punkte")))
                        }
                    }
                } ?: emptyList(),
            )
        } catch (e: JSONException) {
            throw SchnittstellenFehler.AntwortUngueltig
        }

        private fun punkte(a: JSONArray) = (0 until a.length()).map {
            val p = a.getJSONObject(it)
            Punkt(p.getDouble("lat"), p.getDouble("lng"), if (p.has("t")) p.getLong("t") else null)
        }
    }
}

/** Steuerung der Suche durch das Team (Abschnitt 11). Kurzpause hält die Ortung an (`mode` bleibt `track`). */
enum class Suchaktion(val code: String, val titel: String) {
    STARTEN("starten", "Suche starten"),
    PAUSE("pause", "Pause"),
    WEITER("weiter", "Weiter"),
    BEENDEN("beenden", "Suche beendet");

    companion object {
        /** Nur die gültigen Übergänge anbieten. */
        fun moeglich(status: TeamLage.SucheStatus): List<Suchaktion> = when (status) {
            TeamLage.SucheStatus.BEREIT, TeamLage.SucheStatus.BEENDET -> listOf(STARTEN)
            TeamLage.SucheStatus.LAEUFT -> listOf(PAUSE, BEENDEN)
            TeamLage.SucheStatus.KURZPAUSE -> listOf(WEITER, BEENDEN)
        }

        fun aus(code: String) = entries.firstOrNull { it.code == code }
    }
}

/** Antwort von `POST /suche` (200). */
data class SuchAntwort(val mode: Modus?, val suche: TeamLage.Suche?)

sealed class SuchFehler(nachricht: String) : Exception(nachricht) {
    /** 409: Übergang nicht erlaubt; Stand des Servers anbei */
    data class Abgelehnt(val text: String?, val suche: TeamLage.Suche?) : SuchFehler("abgelehnt")
    /** 403: Teams dürfen auf diesem Server nicht steuern */
    data class Verboten(val text: String?) : SuchFehler("verboten")
}

/** Die Statusknöpfe der Team-Seite (Abschnitt 8). „Pause“ und „Beendet“ zeigt die App seit #133 nicht mehr. */
enum class Statusmeldung(val code: String, val titel: String) {
    IM_GEBIET("im_gebiet", "Im Gebiet"),
    PAUSE("pause", "Pause"),
    FUNK("funk", "Funk"),
    BEENDET("beendet", "Beendet");

    companion object {
        fun aus(code: String) = entries.firstOrNull { it.code == code }
    }
}

/** Ereignisse aus `GET /ereignisse` (Abschnitt 9). */
sealed interface TeamEreignis {
    data class HalsbandPosition(val halsband: Halsband) : TeamEreignis
    data class NeueMitteilung(val mitteilung: Mitteilung) : TeamEreignis
    data object Lage : TeamEreignis
    data object Stop : TeamEreignis
    data class Verdraengt(val wiederholenNachSec: Int) : TeamEreignis

    companion object {
        fun aus(roh: SseLeser.Ereignis): TeamEreignis? = runCatching {
            when (roh.name) {
                "halsband" -> HalsbandPosition(TeamLage.halsband(JSONObject(roh.daten)))
                "mitteilung" -> NeueMitteilung(TeamLage.mitteilung(JSONObject(roh.daten)))
                "lage" -> Lage
                "stop" -> Stop
                "verdraengt" -> Verdraengt(runCatching { JSONObject(roh.daten).optInt("wiederholenNachSec", 60) }.getOrDefault(60))
                else -> null
            }
        }.getOrNull()
    }
}

/** Zerlegt Server-Sent Events Zeile für Zeile; eine Leerzeile schließt ein Ereignis ab, `:` ist ein Kommentar. */
class SseLeser {
    data class Ereignis(val name: String, val daten: String)

    private var name = ""
    private val daten = mutableListOf<String>()

    fun zeile(zeile: String): Ereignis? {
        if (zeile.isEmpty()) {
            val fertig = if (daten.isEmpty()) null else Ereignis(name.ifEmpty { "message" }, daten.joinToString("\n"))
            name = ""
            daten.clear()
            return fertig
        }
        if (zeile.startsWith(":")) return null
        val trenner = zeile.indexOf(':')
        val feld = if (trenner < 0) zeile else zeile.substring(0, trenner)
        val wert = if (trenner < 0) "" else zeile.substring(trenner + 1).removePrefix(" ")
        when (feld) {
            "event" -> name = wert
            "data" -> daten += wert
        }
        return null
    }
}

/** Eine lokale Mitteilung über eine Änderung der Lage. Texte kurz, ohne Namen von Vermissten und ohne Orte. */
data class LageMeldung(val titel: String, val text: String? = null) {
    companion object {
        val EINSATZ_BEENDET = LageMeldung("Einsatz beendet – Ortung aus")

        /** Was sich geändert hat; je Zustandsänderung höchstens eine Meldung (wie iOS `LageMeldung.vergleichen`). */
        fun vergleichen(alt: TeamLage, neu: TeamLage): List<LageMeldung> = buildList {
            if (alt.suche.status != neu.suche.status) {
                when (neu.suche.status) {
                    TeamLage.SucheStatus.LAEUFT -> add(LageMeldung("Suche gestartet – Ortung läuft"))
                    TeamLage.SucheStatus.KURZPAUSE -> add(LageMeldung("Suche pausiert"))
                    TeamLage.SucheStatus.BEENDET -> add(LageMeldung("Suche beendet"))
                    TeamLage.SucheStatus.BEREIT -> Unit
                }
            }
            neu.suchgebiet?.let { if (it.id != alt.suchgebiet?.id) add(LageMeldung("Neues Suchgebiet: ${it.name ?: "ohne Namen"}")) }
            neu.halsband?.let {
                if (it.id != null && it.id != alt.halsband?.id) add(LageMeldung("Halsband zugewiesen: ${it.name ?: it.id}"))
            }
            val bekannt = alt.mitteilungen.map { it.id }.toSet()
            neu.mitteilungen.filter { it.id !in bekannt }.forEach { add(LageMeldung("Mitteilung der Einsatzleitung", it.text)) }
        }
    }
}

internal fun JSONObject.textOderNull(name: String): String? =
    if (has(name) && !isNull(name)) optString(name).ifEmpty { null } else null

internal fun JSONObject.objektOderNull(name: String): JSONObject? =
    if (has(name) && !isNull(name)) optJSONObject(name) else null
