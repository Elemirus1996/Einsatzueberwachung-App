package io.github.elemirus1996.einsatzspur.kern

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Übersicht der Einsatzleitung wie iOS `ELAnsicht.swift`: Lage, Live-Ereignisse, Warnungen. Läuft, solange die
 * Ansicht sichtbar ist. Der Modus aus /lage steuert die Ortung der Einsatzleitung (Einsatz läuft → track, sonst pause).
 */
class ELAnsicht(
    private val schnittstelle: ELLageSchnittstelle,
    private val bereich: CoroutineScope,
    private val jetzt: () -> Long = System::currentTimeMillis,
    private val lageTaktMs: Long = 60_000,
    private val beiSteuerung: (Modus) -> Unit,
) {
    data class Warnung(val text: String, val stufe: Int, val zeitMs: Long)

    private val _lage = MutableStateFlow<ELLage?>(null)
    val lage: StateFlow<ELLage?> = _lage
    private val _live = MutableStateFlow(false)
    val live: StateFlow<Boolean> = _live
    private val _verbindungsfehler = MutableStateFlow(false)
    val verbindungsfehler: StateFlow<Boolean> = _verbindungsfehler
    /** Letzte Warnung (Warnstufe 1 oder 2) für Hinweis, Ton und Vibration. */
    private val _warnung = MutableStateFlow<Warnung?>(null)
    val warnung: StateFlow<Warnung?> = _warnung
    private val _teamPunkt = MutableStateFlow<Pair<String, Long>?>(null)
    /** Letzter Punkt, den ein Team per App gesetzt hat (Hinweis): Text, Zeit */
    val teamPunkt: StateFlow<Pair<String, Long>?> = _teamPunkt
    /** Kartenpunkt geändert (Karte neu laden) */
    var beiPunkt: (suspend () -> Unit)? = null
    /** Ton je Art (Punkt vom Team, Funk); Warnungen über `beiWarnung` */
    var beiTon: ((Tonart) -> Unit)? = null

    var lageAbgerufenMs: Long? = null
        private set
    private var zeitVersatzMs = 0L
    private var aufgaben: List<Job> = emptyList()
    private var verdraengtWartenMs: Long? = null

    /** Ton bzw. Vibration bei Eskalation (in der Oberfläche gesetzt) */
    var beiWarnung: ((Int) -> Unit)? = null
    /** Neue Notiz bzw. neuer Funkspruch */
    var beiNotiz: (suspend () -> Unit)? = null
    /** Vermisste geändert */
    var beiVermisste: (suspend () -> Unit)? = null
    /** Live-Position für die Karte */
    var beiPosition: ((ELPosition) -> Unit)? = null
    /** Nach jeder erfolgreich geladenen Lage (wartende Aktionen senden, Token verlängern) */
    var nachLage: (suspend (ELLage) -> Unit)? = null

    fun starten() {
        if (aufgaben.isNotEmpty()) return
        aufgaben = listOf(
            bereich.launch { liveSchleife() },
            bereich.launch {
                while (true) {
                    delay(lageTaktMs)
                    if (_live.value) neuLaden()
                }
            },
        )
    }

    fun anhalten() {
        aufgaben.forEach { it.cancel() }
        aufgaben = emptyList()
        _live.value = false
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
            _live.value = false
            delay(verdraengtWartenMs?.also { verdraengtWartenMs = null } ?: if (warVerbunden) NEU_VERBINDEN_MS else POLL_TAKT_MS)
            neuLaden()
        }
    }

    suspend fun verarbeiten(ereignis: ELEreignis) {
        when (ereignis) {
            is ELEreignis.Team, is ELEreignis.Einsatz -> neuLaden()
            is ELEreignis.Warnung -> {
                _warnung.value = Warnung(ereignis.text, ereignis.stufe, jetzt())
                beiWarnung?.invoke(ereignis.stufe)
            }
            // Nur bei ungültigem Token; /lage bestätigt (410 → stop)
            ELEreignis.Stop -> neuLaden()
            is ELEreignis.Verdraengt -> verdraengtWartenMs = ereignis.wiederholenNachSec.coerceIn(5, 600) * 1000L
            ELEreignis.Notiz -> beiNotiz?.invoke()
            ELEreignis.Funk -> {
                beiTon?.invoke(Tonart.FUNK)
                beiNotiz?.invoke()
            }
            is ELEreignis.Punkt -> {
                if (ereignis.aenderung == "neu" && ereignis.teamId != null) {
                    _teamPunkt.value = (ereignis.text ?: "Neuer Punkt von einem Team") to jetzt()
                    beiTon?.invoke(Tonart.PUNKT_VOM_TEAM)
                }
                beiPunkt?.invoke()
            }
            is ELEreignis.Position -> beiPosition?.invoke(ereignis.position)
            ELEreignis.Vermisste -> beiVermisste?.invoke()
        }
    }

    suspend fun neuLaden() {
        val neu = try {
            schnittstelle.lage()
        } catch (e: SchnittstellenFehler.Beendet) {
            beiSteuerung(Modus.STOP)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _verbindungsfehler.value = true
            return
        }
        neu.serverZeitMs?.let { zeitVersatzMs = it - jetzt() }
        _lage.value = neu
        lageAbgerufenMs = jetzt()
        _verbindungsfehler.value = false
        neu.mode?.let(beiSteuerung)
        nachLage?.invoke(neu)
    }

    /** Suchzeit eines Teams in Sekunden; läuft die Suche, zählt die App seit dem letzten Abruf weiter. */
    fun suchdauerSec(team: ELLage.Team, zeitpunktMs: Long): Long? {
        val dauer = team.suche.dauerSec ?: return null
        val abgerufen = lageAbgerufenMs ?: return null
        return if (team.suche.status == TeamLage.SucheStatus.LAEUFT) dauer + ((zeitpunktMs - abgerufen) / 1000).coerceAtLeast(0) else dauer.toLong()
    }

    /** Alter einer Position in Sekunden, mit der Uhr des Servers. */
    fun alterSec(zeitMs: Long?, zeitpunktMs: Long): Long? =
        zeitMs?.let { ((zeitpunktMs + zeitVersatzMs - it) / 1000).coerceAtLeast(0) }

    companion object {
        const val POLL_TAKT_MS = 15_000L
        const val NEU_VERBINDEN_MS = 5_000L
    }
}
