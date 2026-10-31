package io.github.elemirus1996.einsatzspur.kern

import android.media.ToneGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Arten von Meldungen, für die der Benutzer einen eigenen Ton wählt (APP_API.md, Einsatzleitung Abschnitt 7 „Töne in den Apps“,
 * wie iOS `Toene.swift`). Der Server schickt nur eindeutige Ereignisse; welcher Ton dazu gehört, entscheidet das Gerät.
 */
enum class Tonart(val titel: String, val fuerTeam: Boolean, val standard: TonWahl) {
    MITTEILUNG("Mitteilung der Einsatzleitung", true, TonWahl.Ton(ToneGenerator.TONE_PROP_ACK)),
    NAVIGATIONSZIEL("Navigationsziel", true, TonWahl.Ton(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD)),
    WARNUNG1("Warnung Stufe 1", false, TonWahl.Ton(ToneGenerator.TONE_PROP_BEEP2)),
    WARNUNG2("Warnung Stufe 2", false, TonWahl.Ton(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK)),
    PUNKT_VOM_TEAM("Punkt von einem Team", false, TonWahl.Ton(ToneGenerator.TONE_CDMA_ALERT_INCALL_LITE)),
    FUNK("Funk", false, TonWahl.Ton(ToneGenerator.TONE_SUP_RADIO_ACK));

    /** Warnung Stufe 2 bleibt immer hörbar (Entscheidung 31.10.2026) */
    val darfAus: Boolean get() = this != WARNUNG2
}

/** Ton aus den Tönen von Android ([ToneGenerator]), nur Vibration oder aus. */
sealed interface TonWahl {
    data class Ton(val id: Int) : TonWahl
    data object Vibration : TonWahl
    data object Aus : TonWahl

    val speicherWert: String
        get() = when (this) {
            is Ton -> id.toString()
            Vibration -> "vibration"
            Aus -> "aus"
        }

    val name: String
        get() = when (this) {
            is Ton -> TOENE.firstOrNull { it.second == id }?.first ?: "Ton"
            Vibration -> "Nur Vibration"
            Aus -> "Aus"
        }

    companion object {
        /** Auswahl in den Einstellungen */
        val TOENE: List<Pair<String, Int>> = listOf(
            "Kurz" to ToneGenerator.TONE_PROP_ACK,
            "Doppelt" to ToneGenerator.TONE_PROP_BEEP2,
            "Funk" to ToneGenerator.TONE_SUP_RADIO_ACK,
            "Hinweis" to ToneGenerator.TONE_CDMA_ALERT_INCALL_LITE,
            "Ruf" to ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,
            "Alarm" to ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK,
            "Hoch" to ToneGenerator.TONE_CDMA_HIGH_L,
            "Signal" to ToneGenerator.TONE_SUP_PIP,
        )

        fun aus(wert: String?): TonWahl? = when (wert) {
            null -> null
            "vibration" -> Vibration
            "aus" -> Aus
            else -> wert.toIntOrNull()?.takeIf { id -> TOENE.any { it.second == id } }?.let(::Ton)
        }
    }
}

/** Gewählte Töne je Art, gespeichert auf dem Gerät (nicht je Server). */
class Toene(private val ablage: Ablage, private val abspielen: (TonWahl) -> Unit) {
    private val _wahl = MutableStateFlow(Tonart.entries.associateWith { art ->
        TonWahl.aus(ablage.lesen(schluessel(art)))?.takeUnless { it == TonWahl.Aus && !art.darfAus } ?: art.standard
    })
    val wahl: StateFlow<Map<Tonart, TonWahl>> = _wahl

    fun wahl(art: Tonart): TonWahl = _wahl.value[art] ?: art.standard

    /** Auswahlmöglichkeiten der Art („Aus“ nur, wenn erlaubt) */
    fun moeglich(art: Tonart): List<TonWahl> =
        TonWahl.TOENE.map { TonWahl.Ton(it.second) } + TonWahl.Vibration + (if (art.darfAus) listOf(TonWahl.Aus) else emptyList())

    fun setzen(neu: TonWahl, art: Tonart) {
        if (neu == TonWahl.Aus && !art.darfAus) return
        _wahl.value = _wahl.value + (art to neu)
        ablage.schreiben(schluessel(art), neu.speicherWert)
    }

    fun spielen(art: Tonart) = abspielen(wahl(art))

    fun probe(wahl: TonWahl) = abspielen(wahl)

    /** Für lokale Mitteilungen im Hintergrund: bei „Aus“ ohne Ton */
    fun mitTon(art: Tonart): Boolean = wahl(art) != TonWahl.Aus

    private fun schluessel(art: Tonart) = "ton.${art.name.lowercase()}"
}
