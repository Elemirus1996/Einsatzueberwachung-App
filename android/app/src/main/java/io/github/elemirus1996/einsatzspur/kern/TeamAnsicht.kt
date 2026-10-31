package io.github.elemirus1996.einsatzspur.kern

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Kleiner Schlüssel-Wert-Speicher (SharedPreferences auf dem Gerät, Map im Test). */
interface Ablage {
    fun lesen(schluessel: String): String?
    fun schreiben(schluessel: String, wert: String?)
}

/**
 * Daten der nativen Team-Ansicht wie iOS `TeamAnsicht.swift`: Lage, Laufwege, Mitteilungen, Statusmeldungen.
 * Vordergrund: Live-Verbindung, Laufwege, Lage alle 60 s. Hintergrund: nur Lage jede Minute (für Mitteilungen).
 * Der Modus aus /lage steuert die Ortung (`beiSteuerung`); der Suchstatus (z. B. Kurzpause) nur die Anzeige.
 */
class TeamAnsicht(
    private val schnittstelle: TeamAnsichtSchnittstelle,
    private val teamId: String,
    private val ablage: Ablage,
    private val bereich: CoroutineScope,
    private val jetzt: () -> Long = System::currentTimeMillis,
    private val lageTaktMs: Long = 60_000,
    private val beiSteuerung: (Modus) -> Unit,
) {
    data class OffeneMeldung(val id: String, val meldung: Statusmeldung, val zeitMs: Long)
    data class OffeneAktion(val id: String, val aktion: Suchaktion, val zeitMs: Long)

    // Vor allen Feldern, die beim Anlegen aus der Ablage lesen
    private val schluessel = "team.$teamId"

    private val _lage = MutableStateFlow<TeamLage?>(null)
    val lage: StateFlow<TeamLage?> = _lage
    private val _tracks = MutableStateFlow<TeamTracks?>(null)
    val tracks: StateFlow<TeamTracks?> = _tracks
    private val _live = MutableStateFlow(false)
    val live: StateFlow<Boolean> = _live
    private val _verbindungsfehler = MutableStateFlow(false)
    val verbindungsfehler: StateFlow<Boolean> = _verbindungsfehler
    private val _offen = MutableStateFlow(ladeOffene())
    val offeneMeldungen: StateFlow<List<OffeneMeldung>> = _offen
    private val _gelesen = MutableStateFlow(ablage.lesen("$schluessel.gelesen")?.split('\n')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet())
    val gelesen: StateFlow<Set<String>> = _gelesen
    private val _gesendet = MutableStateFlow<Pair<Statusmeldung, Long>?>(null)
    val gesendet: StateFlow<Pair<Statusmeldung, Long>?> = _gesendet

    private val _offeneAktionen = MutableStateFlow(ladeAktionen())
    /** Steuerung der Suche, die im Funkloch auf das Netz wartet (Abschnitt 11). */
    val offeneAktionen: StateFlow<List<OffeneAktion>> = _offeneAktionen
    private val _suchHinweis = MutableStateFlow<Pair<String, Long>?>(null)
    /** Kurzer Hinweis nach 409/403 */
    val suchHinweis: StateFlow<Pair<String, Long>?> = _suchHinweis
    private var aktionenLaufen = false

    /** Lokale Mitteilungen bei Änderungen der Lage. */
    var beiMeldungen: ((List<LageMeldung>) -> Unit)? = null
    /** Jede neue Lage (z. B. neue Version der Datenschutzerklärung, #148) */
    var beiLage: ((TeamLage) -> Unit)? = null

    var lageAbgerufenMs: Long? = null
        private set
    private var zeitVersatzMs = 0L
    private var letzteVolleTracks: Long? = null
    private var aufgaben: List<Job> = emptyList()
    private var imHintergrund = false
    private var meldungenLaufen = false
    private var verdraengtWartenMs: Long? = null

    // MARK: Ablauf

    fun starten() {
        if (imHintergrund) anhalten()
        if (aufgaben.isNotEmpty()) return
        aufgaben = listOf(
            bereich.launch { liveSchleife() },
            bereich.launch { schleife(TRACK_TAKT_MS) { tracksLaden() } },
            bereich.launch { schleife(lageTaktMs) { if (_live.value) neuLaden(tracksVoll = false) } },
        )
    }

    fun anhalten() {
        aufgaben.forEach { it.cancel() }
        aufgaben = emptyList()
        _live.value = false
        imHintergrund = false
    }

    /** Hintergrund: keine Live-Verbindung, keine Laufwege, aber jede Minute die Lage. */
    fun hintergrund() {
        anhalten()
        imHintergrund = true
        aufgaben = listOf(bereich.launch { schleife(lageTaktMs) { neuLaden(tracksVoll = false, mitTracks = false) } })
    }

    /** Bei Abmeldung oder Einsatzende: nichts auf dem Gerät zurücklassen. */
    fun vergessen() {
        anhalten()
        ablage.schreiben("$schluessel.gelesen", null)
        ablage.schreiben("$schluessel.meldungen", null)
        ablage.schreiben("$schluessel.aktionen", null)
        _gelesen.value = emptySet()
        _offen.value = emptyList()
        _offeneAktionen.value = emptyList()
    }

    /** Die Ortung hat von /positions oder /status einen neuen Modus erfahren: Anzeige nachziehen. */
    fun modusGeaendert() {
        if (aufgaben.isEmpty()) return
        bereich.launch { neuLaden(tracksVoll = true, mitTracks = !imHintergrund) }
    }

    private suspend fun schleife(taktMs: Long, schritt: suspend () -> Unit) {
        while (true) {
            delay(taktMs)
            schritt()
        }
    }

    private suspend fun liveSchleife() {
        neuLaden()
        while (true) {
            var warVerbunden = false
            try {
                _live.value = true
                _verbindungsfehler.value = false
                warVerbunden = true
                schnittstelle.ereignisse().collect { verarbeiten(it) }
            } catch (e: SchnittstellenFehler.Beendet) {
                _live.value = false
                beiSteuerung(Modus.STOP)
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
            }
            _live.value = false
            delay(verdraengtWartenMs?.also { verdraengtWartenMs = null } ?: if (warVerbunden) NEU_VERBINDEN_MS else POLL_TAKT_MS)
            neuLaden()
        }
    }

    suspend fun verarbeiten(ereignis: TeamEreignis) {
        when (ereignis) {
            is TeamEreignis.HalsbandPosition -> {
                val alt = _lage.value ?: return
                // Das Ereignis enthält nur die Position; Kennung und Name bleiben aus der Lage
                val neu = ereignis.halsband.copy(id = alt.halsband?.id, name = alt.halsband?.name)
                _lage.value = alt.copy(halsband = neu)
                if (alt.suche.status == TeamLage.SucheStatus.LAEUFT) {
                    _tracks.value = _tracks.value?.let { it.copy(hund = it.hund + TeamTracks.Punkt(neu.lat, neu.lng, neu.zeitMs)) }
                }
            }
            is TeamEreignis.NeueMitteilung -> {
                val alt = _lage.value ?: return
                if (alt.mitteilungen.any { it.id == ereignis.mitteilung.id }) return
                lageUebernehmen(alt.copy(mitteilungen = (alt.mitteilungen + ereignis.mitteilung).takeLast(20)))
            }
            TeamEreignis.Lage -> neuLaden(tracksVoll = true)
            // Absicherung: `stop` heißt echtes Ende, ältere Server schickten es auch bei Verdrängung; /lage entscheidet
            TeamEreignis.Stop -> if (lageBestaetigtStop() == false) verdraengtWartenMs = NACH_VERDRAENGUNG_MS
            is TeamEreignis.Verdraengt -> verdraengtWartenMs = ereignis.wiederholenNachSec.coerceIn(5, 600) * 1000L
        }
    }

    /** `true`: 410, Ortung beendet. `false`: Token gilt noch. `null`: kein Netz. */
    private suspend fun lageBestaetigtStop(): Boolean? = try {
        val neu = schnittstelle.lage()
        lageUebernehmen(neu)
        neu.mode?.let(beiSteuerung)
        neu.mode == Modus.STOP
    } catch (e: SchnittstellenFehler.Beendet) {
        beiSteuerung(Modus.STOP)
        true
    } catch (e: Exception) {
        null
    }

    suspend fun neuLaden(tracksVoll: Boolean = true, mitTracks: Boolean = true) {
        try {
            val neu = schnittstelle.lage()
            neu.serverZeitMs?.let { zeitVersatzMs = it - jetzt() }
            lageUebernehmen(neu)
            _verbindungsfehler.value = false
            neu.mode?.let(beiSteuerung)
        } catch (e: SchnittstellenFehler.Beendet) {
            beiSteuerung(Modus.STOP)
            return
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _verbindungsfehler.value = true
        }
        meldungenSenden()
        aktionenSenden()
        if (mitTracks) tracksLaden(voll = tracksVoll)
    }

    /** Neue Lage übernehmen und Änderungen melden. Die erste Lage meldet nichts. */
    private fun lageUebernehmen(neu: TeamLage) {
        val alt = _lage.value
        _lage.value = neu
        lageAbgerufenMs = jetzt()
        beiLage?.invoke(neu)
        if (alt == null) return
        val meldungen = LageMeldung.vergleichen(alt, neu)
        if (meldungen.isNotEmpty()) beiMeldungen?.invoke(meldungen)
    }

    private suspend fun tracksLaden(voll: Boolean = false) {
        val bisher = _tracks.value
        val vollNoetig = voll || bisher == null || letzteVolleTracks?.let { jetzt() - it >= TRACK_VOLL_NACH_MS } ?: true
        try {
            if (vollNoetig) {
                _tracks.value = schnittstelle.tracks(null)
                letzteVolleTracks = jetzt()
            } else if (bisher != null) {
                val neu = schnittstelle.tracks(bisher.letzterZeitpunkt)
                _tracks.value = bisher.copy(hund = bisher.hund + neu.hund, mensch = bisher.mensch + neu.mensch)
            }
        } catch (e: SchnittstellenFehler.Beendet) {
            beiSteuerung(Modus.STOP)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
        }
    }

    // MARK: Statusmeldungen

    /** In die Warteschlange legen und senden; im Funkloch bleibt sie dort, dieselbe `id` erzeugt nur eine Notiz. */
    suspend fun melden(meldung: Statusmeldung) {
        _offen.value = _offen.value + OffeneMeldung(UUID.randomUUID().toString(), meldung, jetzt())
        speichereOffene()
        meldungenSenden()
    }

    suspend fun meldungenSenden() {
        if (meldungenLaufen) return
        meldungenLaufen = true
        try {
            while (true) {
                val m = _offen.value.firstOrNull() ?: break
                try {
                    schnittstelle.statusmeldung(m.id, m.meldung)
                    _gesendet.value = m.meldung to jetzt()
                } catch (e: SchnittstellenFehler.Beendet) {
                    beiSteuerung(Modus.STOP)
                    return
                } catch (e: SchnittstellenFehler.Server) {
                    // Vom Server abgelehnt (4xx außer 429): nicht endlos wiederholen
                    if (e.status !in 400..499 || e.status == 429) return
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return
                }
                _offen.value = _offen.value.filterNot { it.id == m.id }
                speichereOffene()
            }
        } finally {
            meldungenLaufen = false
        }
    }

    private fun speichereOffene() {
        val liste = JSONArray()
        _offen.value.forEach { liste.put(JSONObject().put("id", it.id).put("code", it.meldung.code).put("zeit", it.zeitMs)) }
        ablage.schreiben("$schluessel.meldungen", liste.toString())
    }

    private fun ladeOffene(): List<OffeneMeldung> = runCatching {
        val liste = JSONArray(ablage.lesen("$schluessel.meldungen") ?: return emptyList())
        (0 until liste.length()).mapNotNull { i ->
            val o = liste.getJSONObject(i)
            Statusmeldung.aus(o.getString("code"))?.let { OffeneMeldung(o.getString("id"), it, o.getLong("zeit")) }
        }
    }.getOrDefault(emptyList())

    // MARK: Steuerung der Suche

    val darfSteuern: Boolean get() = _lage.value?.suche?.darfSteuern == true

    /** In die Warteschlange legen und senden; dieselbe `id` führt der Server nur einmal aus. */
    suspend fun steuern(aktion: Suchaktion) {
        _offeneAktionen.value = _offeneAktionen.value + OffeneAktion(UUID.randomUUID().toString(), aktion, jetzt())
        speichereAktionen()
        aktionenSenden()
    }

    suspend fun aktionenSenden() {
        if (aktionenLaufen) return
        aktionenLaufen = true
        try {
            while (true) {
                val offen = _offeneAktionen.value.firstOrNull() ?: break
                try {
                    val antwort = schnittstelle.suche(offen.id, offen.aktion)
                    sucheUebernehmen(antwort.suche)
                    // Modus sofort an die Ortung (z. B. „Suche beendet“ → pause → Grob-Ortung)
                    antwort.mode?.let(beiSteuerung)
                } catch (e: SchnittstellenFehler.Beendet) {
                    beiSteuerung(Modus.STOP)
                    return
                } catch (e: SuchFehler.Abgelehnt) {
                    // Die EL kann inzwischen umgeschaltet haben: Stand übernehmen und kurz anzeigen
                    sucheUebernehmen(e.suche)
                    _suchHinweis.value = (e.text ?: "Nicht möglich, der Stand hat sich geändert.") to jetzt()
                } catch (e: SuchFehler.Verboten) {
                    _lage.value?.let { lageUebernehmen(it.copy(suche = it.suche.copy(darfSteuern = false))) }
                    _suchHinweis.value = (e.text ?: "Die Einsatzleitung steuert die Suche.") to jetzt()
                } catch (e: SchnittstellenFehler.Server) {
                    if (e.status !in 400..499 || e.status == 429) return
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return
                }
                _offeneAktionen.value = _offeneAktionen.value.filterNot { it.id == offen.id }
                speichereAktionen()
            }
        } finally {
            aktionenLaufen = false
        }
    }

    private fun sucheUebernehmen(suche: TeamLage.Suche?) {
        val alt = _lage.value ?: return
        if (suche != null) lageUebernehmen(alt.copy(suche = suche))
    }

    private fun speichereAktionen() {
        val liste = JSONArray()
        _offeneAktionen.value.forEach { liste.put(JSONObject().put("id", it.id).put("aktion", it.aktion.code).put("zeit", it.zeitMs)) }
        ablage.schreiben("$schluessel.aktionen", liste.toString())
    }

    private fun ladeAktionen(): List<OffeneAktion> = runCatching {
        val liste = JSONArray(ablage.lesen("$schluessel.aktionen") ?: return emptyList())
        (0 until liste.length()).mapNotNull { i ->
            val o = liste.getJSONObject(i)
            Suchaktion.aus(o.getString("aktion"))?.let { OffeneAktion(o.getString("id"), it, o.getLong("zeit")) }
        }
    }.getOrDefault(emptyList())

    // MARK: Mitteilungen der EL

    val ungelesen: List<Mitteilung> get() = (_lage.value?.mitteilungen ?: emptyList()).filter { it.id !in _gelesen.value }

    fun alsGelesen(mitteilung: Mitteilung) {
        _gelesen.value = _gelesen.value + mitteilung.id
        ablage.schreiben("$schluessel.gelesen", _gelesen.value.joinToString("\n"))
    }

    // MARK: Anzeige

    /** Alter der Halsband-Position mit der Uhr des Servers (das Handy kann falsch gehen). */
    fun halsbandAlterMs(zeitpunkt: Long = jetzt()): Long? =
        _lage.value?.halsband?.let { (zeitpunkt + zeitVersatzMs - it.zeitMs).coerceAtLeast(0) }

    /** Suchzeit wie im Monitor; läuft die Suche, zählt die App seit dem letzten Abruf weiter. */
    fun suchdauerSec(zeitpunkt: Long = jetzt()): Long? {
        val suche = _lage.value?.suche ?: return null
        val dauer = suche.dauerSec?.toLong() ?: return null
        val abgerufen = lageAbgerufenMs ?: return null
        return if (suche.status == TeamLage.SucheStatus.LAEUFT) dauer + ((zeitpunkt - abgerufen) / 1000).coerceAtLeast(0) else dauer
    }

    companion object {
        const val POLL_TAKT_MS = 15_000L
        const val NEU_VERBINDEN_MS = 5_000L
        const val NACH_VERDRAENGUNG_MS = 60_000L
        const val TRACK_TAKT_MS = 30_000L
        const val TRACK_VOLL_NACH_MS = 300_000L
    }
}
