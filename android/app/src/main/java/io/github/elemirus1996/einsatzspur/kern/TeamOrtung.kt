package io.github.elemirus1996.einsatzspur.kern

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * `FEIN`: GPS für den Laufweg (Modus `track`).
 * `GROB`: Netzwerk-Ortung ohne GPS (Modus `pause`). Hält die App wach, damit sie eine neue Suche bemerkt;
 * diese Positionen werden weder gepuffert noch gesendet (entschieden 29.10.2026).
 */
enum class OrtungsStufe { FEIN, GROB }

/** Liefert Positionen, solange sie in Stufe `FEIN` läuft. [beenden] schaltet den Ortungsdienst wirklich ab. */
interface OrtungsQuelle {
    var beiPosition: ((Position) -> Unit)?

    /** Startet den Dienst oder schaltet die Stufe um. */
    fun starten(stufe: OrtungsStufe)
    fun beenden()
}

/** Zwischenspeicher für Positionen, damit im Funkloch nichts verloren geht. */
interface PositionsSpeicher {
    fun hinzufuegen(position: Position)

    /** Älteste Einträge zuerst, höchstens [anzahl]. */
    fun aelteste(anzahl: Int): List<Pair<Long, Position>>
    fun loeschenBis(id: Long)
    fun anzahl(): Int
    fun leeren()
}

/**
 * Steuert die Ortung der Rolle Team nach den Antworten des Servers (`mode`), wie die iOS-App (`TeamOrtung.swift`).
 * Bei `stop` wird der Ortungsdienst beendet, bei `pause` läuft nur die Grob-Ortung ohne GPS.
 * Alle Aufrufe auf demselben Thread (Main); die Schnittstelle wechselt selbst auf IO.
 */
class TeamOrtung(
    private val schnittstelle: TeamSchnittstelle,
    private val quelle: OrtungsQuelle,
    private val puffer: PositionsSpeicher,
    ablaufMs: Long,
    private val bereich: CoroutineScope,
    private val jetzt: () -> Long = System::currentTimeMillis,
    private val beimBeenden: () -> Unit,
) {
    enum class Zustand { WARTET, ORTET, PAUSIERT, BEENDET }

    private val _zustand = MutableStateFlow(Zustand.WARTET)
    val zustand: StateFlow<Zustand> = _zustand
    private val _letzterUpload = MutableStateFlow<Long?>(null)
    val letzterUpload: StateFlow<Long?> = _letzterUpload
    private val _gepuffert = MutableStateFlow(puffer.anzahl())
    val gepuffert: StateFlow<Int> = _gepuffert

    var steuerung = SteuerAntwort(Modus.PAUSE, expiresAtMs = ablaufMs)
        private set

    /** Meldet, wenn eine Antwort von /positions oder /status den Modus ändert. */
    var beiModuswechsel: ((Modus) -> Unit)? = null

    private var letzteAufnahme: Long? = null
    private var letzteAnfrage: Long? = null
    private var anfrageLaeuft = false
    private var takt: Job? = null

    init {
        quelle.beiPosition = { positionErhalten(it) }
    }

    val intervallMs: Long get() = (steuerung.intervalSec ?: 20).coerceAtLeast(1) * 1000L
    val uploadTaktMs: Long get() = (steuerung.uploadEverySec ?: 60).coerceAtLeast(5) * 1000L

    /** In der Pause höchstens jede Minute /status fragen (Server-Vorgabe pauseCheckSec, wenn kürzer). */
    val pauseTaktMs: Long get() = (steuerung.pauseCheckSec ?: 60).coerceIn(5, 60) * 1000L

    /** Übernimmt eine Antwort des Servers. `melden = false`, wenn der Modus aus /lage kommt. */
    fun anwenden(antwort: SteuerAntwort, melden: Boolean = true) {
        if (_zustand.value == Zustand.BEENDET) return
        val vorher = steuerung.mode
        steuerung = SteuerAntwort(
            mode = antwort.mode,
            intervalSec = antwort.intervalSec ?: steuerung.intervalSec,
            uploadEverySec = antwort.uploadEverySec ?: steuerung.uploadEverySec,
            pauseCheckSec = antwort.pauseCheckSec ?: steuerung.pauseCheckSec,
            expiresAtMs = antwort.expiresAtMs ?: steuerung.expiresAtMs,
        )
        if (abgelaufen) {
            beenden()
            return
        }
        if (melden && antwort.mode != vorher) beiModuswechsel?.invoke(antwort.mode)
        when (antwort.mode) {
            Modus.TRACK -> {
                quelle.starten(OrtungsStufe.FEIN)
                _zustand.value = Zustand.ORTET
                taktStarten()
            }
            Modus.PAUSE -> {
                // Kein GPS, aber Grob-Ortung hält die App wach, damit der Takt eine neue Suche bemerkt
                quelle.starten(OrtungsStufe.GROB)
                _zustand.value = Zustand.PAUSIERT
                taktStarten()
            }
            Modus.STOP -> beenden()
        }
    }

    /** Sicherheitsnetz: Ist `expiresAt` erreicht, endet die Ortung auch ohne Netz. */
    fun ablaufPruefen() {
        if (_zustand.value != Zustand.BEENDET && abgelaufen) beenden()
    }

    fun positionErhalten(position: Position) {
        ablaufPruefen()
        if (_zustand.value != Zustand.ORTET) return
        letzteAufnahme?.let { if (position.timestamp - it < intervallMs) return }
        letzteAufnahme = position.timestamp
        puffer.hinzufuegen(position)
        _gepuffert.value = puffer.anzahl()
        if (istFaellig) bereich.launch { hochladen() }
    }

    /**
     * Lädt den Puffer hoch (Pakete zu höchstens 500). Ist er leer, wird nur der Status abgefragt,
     * damit die App auch ohne neue Positionen erfährt, wann die Suche endet.
     */
    suspend fun hochladen() {
        if (anfrageLaeuft || _zustand.value == Zustand.BEENDET) return
        anfrageLaeuft = true
        letzteAnfrage = jetzt()
        try {
            var paket = puffer.aelteste(HOECHSTENS_JE_UPLOAD)
            if (paket.isEmpty()) {
                anwenden(schnittstelle.status())
                return
            }
            while (paket.isNotEmpty() && _zustand.value != Zustand.BEENDET) {
                val antwort = schnittstelle.positionenSenden(paket.map { it.second })
                puffer.loeschenBis(paket.last().first)
                _gepuffert.value = puffer.anzahl()
                _letzterUpload.value = jetzt()
                anwenden(antwort)
                paket = puffer.aelteste(HOECHSTENS_JE_UPLOAD)
            }
        } catch (e: SchnittstellenFehler.Beendet) {
            beenden()
        } catch (e: Exception) {
            // Netzfehler oder 5xx: Positionen bleiben im Puffer und gehen beim nächsten Takt mit
        } finally {
            anfrageLaeuft = false
        }
    }

    /** Leichte Abfrage ohne GPS (Pause, App-Start). Liegt noch etwas im Puffer, geht das zuerst raus. */
    suspend fun statusPruefen() {
        ablaufPruefen()
        if (_zustand.value == Zustand.BEENDET) return
        if (puffer.anzahl() > 0) {
            hochladen()
            return
        }
        if (anfrageLaeuft) return
        anfrageLaeuft = true
        letzteAnfrage = jetzt()
        try {
            anwenden(schnittstelle.status())
        } catch (e: SchnittstellenFehler.Beendet) {
            beenden()
        } catch (e: Exception) {
        } finally {
            anfrageLaeuft = false
        }
    }

    /**
     * Bei `track` regelmäßig hochladen bzw. den Status fragen, auch wenn der Hundeführer stillsteht
     * (sonst bliebe das GPS nach Suchende an). Bei `pause` jede Minute /status, damit eine neue Suche
     * auch bei gesperrtem Handy bemerkt wird.
     */
    private fun taktStarten() {
        if (takt?.isActive == true) return
        takt = bereich.launch {
            while (isActive) {
                delay(if (_zustand.value == Zustand.PAUSIERT) pauseTaktMs else uploadTaktMs)
                when (_zustand.value) {
                    Zustand.ORTET -> if (istFaellig) hochladen()
                    Zustand.PAUSIERT -> statusPruefen()
                    Zustand.WARTET -> Unit
                    Zustand.BEENDET -> return@launch
                }
            }
        }
    }

    private val istFaellig: Boolean
        get() = letzteAnfrage?.let { jetzt() - it >= uploadTaktMs } ?: true

    private val abgelaufen: Boolean
        get() = steuerung.expiresAtMs?.let { jetzt() >= it } ?: false

    private fun beenden() {
        takt?.cancel()
        takt = null
        quelle.beenden()
        puffer.leeren()
        _gepuffert.value = 0
        steuerung = steuerung.copy(mode = Modus.STOP)
        _zustand.value = Zustand.BEENDET
        beimBeenden()
    }

    companion object {
        const val HOECHSTENS_JE_UPLOAD = 500
    }
}
