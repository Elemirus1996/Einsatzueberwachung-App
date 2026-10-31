package io.github.elemirus1996.einsatzspur.kern

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Eine Aktion der Einsatzleitung (APP_API.md „native App“, Abschnitt 7), wie iOS `ELAktion`. Jede trägt eine `id`;
 * dieselbe `id` führt der Server nur einmal aus, deshalb darf die App im Funkloch puffern und wiederholen.
 */
data class ELAktion(
    val id: String,
    /** Pfad unter `api/el/app/v1`, z. B. `teams/t1/start` */
    val pfad: String,
    /** JSON-Körper ohne `id`, als Text (vergleichbar und speicherbar) */
    val inhalt: String,
    /** Für die Anzeige „… wartet auf Netz“ */
    val beschreibung: String,
    val zeitMs: Long,
) {
    fun koerper(): JSONObject = JSONObject(inhalt).put("id", id)

    fun alsJson(): JSONObject = JSONObject().put("id", id).put("pfad", pfad).put("inhalt", inhalt)
        .put("beschreibung", beschreibung).put("zeitMs", zeitMs)

    companion object {
        fun aus(json: JSONObject) = ELAktion(json.getString("id"), json.getString("pfad"), json.getString("inhalt"),
            json.getString("beschreibung"), json.getLong("zeitMs"))

        fun neu(pfad: String, beschreibung: String, inhalt: JSONObject = JSONObject(), zeitMs: Long = System.currentTimeMillis()) =
            ELAktion(UUID.randomUUID().toString(), pfad, inhalt.toString(), beschreibung, zeitMs)

        private fun oderNull(wert: String?): Any = wert ?: JSONObject.NULL

        fun teamStart(teamId: String, name: String, halsbandId: String?) =
            neu("teams/$teamId/start", "$name: Suche starten", JSONObject().put("halsbandId", oderNull(halsbandId)))
        fun teamPause(teamId: String, name: String) = neu("teams/$teamId/pause", "$name: Pause")
        fun teamWeiter(teamId: String, name: String) = neu("teams/$teamId/weiter", "$name: Weiter")
        fun teamStopp(teamId: String, name: String) = neu("teams/$teamId/stopp", "$name: Suche beenden")
        fun teamZuruecksetzen(teamId: String, name: String) = neu("teams/$teamId/zuruecksetzen", "$name: Zurücksetzen")
        fun mitteilung(teamId: String, name: String, text: String) =
            neu("teams/$teamId/mitteilung", "Mitteilung an $name", JSONObject().put("text", text))
        /** Kartenpunkt als Navigationsziel an das Team (Abschnitt 7, Variante C) */
        fun ziel(teamId: String, name: String, punktId: String, punktName: String) =
            neu("teams/$teamId/ziel", "Ziel „$punktName“ an $name", JSONObject().put("punktId", punktId))
        fun notiz(text: String, teamId: String?) = neu("notizen", "Notiz", JSONObject().put("text", text).put("teamId", oderNull(teamId)))
        fun notizLoeschen(notizId: String) = neu("notizen/$notizId/loeschen", "Notiz löschen")
        fun funk(text: String, teamId: String?, antwortAuf: String?) = neu(
            "funk", if (antwortAuf == null) "Funkspruch" else "Antwort",
            JSONObject().put("text", text).put("teamId", oderNull(teamId)).put("antwortAuf", oderNull(antwortAuf)),
        )
    }
}

/** 409/400/404 mit Text des Servers, z. B. „Team läuft schon“, „Halsband vergeben“. Nicht wiederholen. */
class ELAktionAbgelehnt(val text: String) : Exception(text)

/** Notiz bzw. Funkspruch (Abschnitt 6, `GET /notizen`), neueste zuerst. */
data class ELNotiz(
    val id: String,
    val art: String,
    val text: String,
    val zeitMs: Long,
    val von: String?,
    val teamId: String?,
    val antworten: List<Antwort>,
    /** Nur Notizen der Einsatzleitung dürfen gelöscht werden */
    val loeschbar: Boolean,
) {
    data class Antwort(val id: String, val text: String, val zeitMs: Long, val von: String?)

    companion object {
        fun aus(n: JSONObject) = ELNotiz(
            id = n.getString("id"),
            art = n.optString("art", "notiz"),
            text = n.getString("text"),
            zeitMs = isoZuMs(n.textOderNull("zeit")) ?: 0,
            von = n.textOderNull("von"),
            teamId = n.textOderNull("teamId"),
            antworten = n.optJSONArray("antworten")?.let { a ->
                (0 until a.length()).map { i ->
                    a.getJSONObject(i).let { Antwort(it.getString("id"), it.getString("text"), isoZuMs(it.textOderNull("zeit")) ?: 0, it.textOderNull("von")) }
                }
            } ?: emptyList(),
            loeschbar = n.optBoolean("loeschbar", false),
        )
    }
}

data class ELNotizSeite(val notizen: List<ELNotiz>, val weitere: Boolean) {
    companion object {
        fun aus(json: JSONObject): ELNotizSeite {
            val a = json.getJSONArray("notizen")
            return ELNotizSeite((0 until a.length()).map { ELNotiz.aus(a.getJSONObject(it)) }, json.optBoolean("weitere", false))
        }
    }
}

/** Freie Halsbänder für die Rückfrage beim Teamstart (`GET /halsbaender`). */
data class ELHalsbaender(val frei: List<Halsband>, val zuletzt: Map<String, String>) {
    data class Halsband(val id: String, val name: String?, val zeitMs: Long?, val akku: Int?)

    companion object {
        fun aus(json: JSONObject): ELHalsbaender {
            val frei = json.optJSONArray("frei") ?: JSONArray()
            val zuletzt = json.optJSONArray("zuletzt") ?: JSONArray()
            return ELHalsbaender(
                (0 until frei.length()).map { i ->
                    frei.getJSONObject(i).let { Halsband(it.getString("id"), it.textOderNull("name"), isoZuMs(it.textOderNull("zeit")), it.ganzOderNull("akku")) }
                },
                (0 until zuletzt.length()).mapNotNull { i ->
                    zuletzt.getJSONObject(i).let { z -> z.textOderNull("halsbandId")?.let { z.getString("teamId") to it } }
                }.toMap(),
            )
        }
    }
}

/** Was die Aktionen der Einsatzleitung brauchen (testbar ohne Netz). */
interface ELAktionsSchnittstelle {
    /** Liefert den Körper der Antwort (z. B. `archivId` beim Beenden) */
    suspend fun ausfuehren(aktion: ELAktion): String
    suspend fun notizen(vor: String?): ELNotizSeite
    suspend fun halsbaender(): ELHalsbaender
}

/** Aktionen, Notizen und Funk der Einsatzleitung mit Warteschlange fürs Funkloch, wie iOS `ELBetrieb`. */
class ELBetrieb(
    private val schnittstelle: ELAktionsSchnittstelle,
    private val ablage: Ablage,
    private val jetzt: () -> Long = System::currentTimeMillis,
    /** Auswahllisten für Einsatz starten und beenden */
    private val einsatz: ELEinsatzSchnittstelle? = null,
    private val beiStop: () -> Unit,
) {
    data class Hinweis(val text: String, val fehler: Boolean, val zeitMs: Long)

    private val _offen = MutableStateFlow(laden())
    val offen: StateFlow<List<ELAktion>> = _offen
    private val _notizen = MutableStateFlow<List<ELNotiz>>(emptyList())
    val notizen: StateFlow<List<ELNotiz>> = _notizen
    /** Ablehnung des Servers oder Bestätigung, kurz anzeigen */
    private val _hinweis = MutableStateFlow<Hinweis?>(null)
    val hinweis: StateFlow<Hinweis?> = _hinweis
    private val _weitereNotizen = MutableStateFlow(true)
    val weitereNotizen: StateFlow<Boolean> = _weitereNotizen
    /** Ergebnis von „Einsatz beenden“ (Nummer, PDF erstellt oder Fehler), bis die Einsatzleitung es bestätigt */
    private val _einsatzEnde = MutableStateFlow<ELEinsatzEnde?>(null)
    val einsatzEnde: StateFlow<ELEinsatzEnde?> = _einsatzEnde
    private var laeuft = false

    /** Nach jeder ausgeführten Aktion (Lage neu laden) */
    var nachAktion: (suspend () -> Unit)? = null
    /** Antwort einer erfolgreichen Aktion (z. B. Einsatz beendet) */
    var beiAntwort: ((ELAktion, String) -> Unit)? = null

    /** Bei Abmeldung: nichts zurücklassen */
    fun vergessen() {
        _offen.value = emptyList()
        _notizen.value = emptyList()
        ablage.schreiben(SCHLUESSEL, null)
    }

    suspend fun ausfuehren(aktion: ELAktion) {
        _offen.value = _offen.value + aktion.copy(zeitMs = jetzt())
        speichern()
        senden()
    }

    /** Wartende Aktionen der Reihe nach; im Funkloch bleiben sie und gehen mit derselben `id` erneut raus. */
    suspend fun senden() {
        if (laeuft) return
        laeuft = true
        var etwasGetan = false
        try {
            while (true) {
                val aktion = _offen.value.firstOrNull() ?: break
                try {
                    val antwort = schnittstelle.ausfuehren(aktion)
                    _hinweis.value = Hinweis("${aktion.beschreibung} ✓", false, jetzt())
                    if (aktion.pfad == "einsatz/beenden") _einsatzEnde.value = ELEinsatzEnde.aus(antwort)
                    beiAntwort?.invoke(aktion, antwort)
                    etwasGetan = true
                } catch (e: SchnittstellenFehler.Beendet) {
                    beiStop()
                    return
                } catch (e: ELAktionAbgelehnt) {
                    _hinweis.value = Hinweis(e.text, true, jetzt())
                    etwasGetan = true
                } catch (e: SchnittstellenFehler.Server) {
                    if (e.status !in 400..499 || e.status == 429) break
                    _hinweis.value = Hinweis("${aktion.beschreibung}: vom Server abgelehnt", true, jetzt())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Funkloch: später mit derselben id erneut
                    break
                }
                _offen.value = _offen.value.filterNot { it.id == aktion.id }
                speichern()
            }
        } finally {
            laeuft = false
        }
        if (etwasGetan) nachAktion?.invoke()
    }

    suspend fun notizenLaden() {
        try {
            val seite = schnittstelle.notizen(null)
            _notizen.value = seite.notizen
            _weitereNotizen.value = seite.weitere
        } catch (e: SchnittstellenFehler.Beendet) {
            beiStop()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
        }
    }

    suspend fun aeltereNotizenLaden() {
        val letzte = _notizen.value.lastOrNull() ?: return
        if (!_weitereNotizen.value) return
        val seite = runCatching { schnittstelle.notizen(letzte.id) }.getOrNull() ?: return
        val bekannt = _notizen.value.map { it.id }.toSet()
        _notizen.value = _notizen.value + seite.notizen.filterNot { it.id in bekannt }
        _weitereNotizen.value = seite.weitere
    }

    suspend fun halsbaender(): ELHalsbaender? = runCatching { schnittstelle.halsbaender() }.getOrNull()

    /** `null`, wenn die Liste nicht geladen werden konnte (Eingabe dann von Hand) */
    suspend fun vorlage(): ELVorlage? = einsatz?.let { e -> runCatching { e.vorlage() }.getOrNull() }

    suspend fun ergebnisse(): List<ELErgebnis> = einsatz?.let { e -> runCatching { e.ergebnisse() }.getOrNull() } ?: emptyList()

    fun einsatzEndeGelesen() {
        _einsatzEnde.value = null
    }

    private fun laden(): List<ELAktion> = ablage.lesen(SCHLUESSEL)?.let { text ->
        runCatching { JSONArray(text).let { a -> (0 until a.length()).map { ELAktion.aus(a.getJSONObject(it)) } } }.getOrNull()
    } ?: emptyList()

    private fun speichern() {
        ablage.schreiben(SCHLUESSEL, JSONArray().apply { _offen.value.forEach { put(it.alsJson()) } }.toString())
    }

    companion object {
        private const val SCHLUESSEL = "el.aktionen"
    }
}
