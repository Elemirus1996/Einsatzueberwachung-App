package io.github.elemirus1996.einsatzspur.kern

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Mitteilungen für die Einsatzleitung im Hintergrund ohne Push (#79, APP_API.md native EL Abschnitt 11), wie iOS
 * `ELMitteilungen.swift`. Der Server meldet in /status und /positions einen `mitteilungStand`; nur wenn er sich ändert,
 * holt die App /mitteilungen?seit= und zeigt die eingeschalteten Arten als lokale Mitteilung.
 */
data class ELMitteilung(val id: String, val art: String, val stufe: Int, val teamId: String?, val text: String)

data class ELMitteilungSeite(val stand: String, val mitteilungen: List<ELMitteilung>) {
    companion object {
        fun aus(json: JSONObject): ELMitteilungSeite {
            val liste = json.optJSONArray("mitteilungen")
            val mitteilungen = (0 until (liste?.length() ?: 0)).mapNotNull { i ->
                val m = liste!!.optJSONObject(i) ?: return@mapNotNull null
                ELMitteilung(
                    id = m.optString("id"),
                    art = m.optString("art"),
                    stufe = m.optInt("stufe", 1),
                    teamId = if (m.isNull("teamId")) null else m.optString("teamId"),
                    text = m.optString("text"),
                )
            }
            return ELMitteilungSeite(json.optString("stand"), mitteilungen)
        }
    }
}

interface ELMitteilungsSchnittstelle {
    suspend fun mitteilungen(seit: String?): ELMitteilungSeite
}

/** Einstellbare Arten. Zeitwarnung ist nach Stufe getrennt, damit Stufe 1 aus und Stufe 2 an sein kann. */
enum class ELMitteilungsArt(val titel: String, val standard: Boolean, val tonart: Tonart) {
    ZEITWARNUNG2("Zeitwarnung Stufe 2", true, Tonart.WARNUNG2),
    FUNKSTILLE("Funkstille Handy oder Halsband", true, Tonart.WARNUNG2),
    GEBIET("Hund außerhalb des Suchgebiets", true, Tonart.WARNUNG2),
    FUND("Fund gemeldet", true, Tonart.WARNUNG2),
    ZEITWARNUNG1("Zeitwarnung Stufe 1", false, Tonart.WARNUNG1),
    FUNK("Neuer Funkspruch", false, Tonart.FUNK),
    STATUS("Statusmeldung eines Teams", false, Tonart.WARNUNG1),
    AKKU("Akku niedrig", false, Tonart.WARNUNG1),
    EINSATZENDE("Einsatzende", false, Tonart.WARNUNG1);

    companion object {
        /** Art einer Mitteilung des Servers; unbekannte Arten (neuere Server) werden nicht gezeigt. */
        fun aus(m: ELMitteilung): ELMitteilungsArt? = when (m.art) {
            "zeitwarnung" -> if (m.stufe >= 2) ZEITWARNUNG2 else ZEITWARNUNG1
            "funkstille" -> FUNKSTILLE
            "gebiet" -> GEBIET
            "fund" -> FUND
            "funk" -> FUNK
            "status" -> STATUS
            "akku" -> AKKU
            "einsatzende" -> EINSATZENDE
            else -> null
        }
    }
}

/** Je Art an oder aus, dazu der Hauptschalter (Standard an). Nicht geheim, darum in der normalen Ablage. */
class ELMitteilungsEinstellungen(private val ablage: Ablage) {
    private val _an = MutableStateFlow(ablage.lesen(HAUPT)?.toBooleanStrictOrNull() ?: true)
    val an: StateFlow<Boolean> = _an
    private val _arten = MutableStateFlow(ELMitteilungsArt.entries.associateWith { art ->
        ablage.lesen(schluessel(art))?.toBooleanStrictOrNull() ?: art.standard
    })
    val arten: StateFlow<Map<ELMitteilungsArt, Boolean>> = _arten

    fun ist(art: ELMitteilungsArt): Boolean = _arten.value[art] ?: art.standard

    fun setzenAn(neu: Boolean) {
        _an.value = neu
        ablage.schreiben(HAUPT, neu.toString())
    }

    fun setzen(neu: Boolean, art: ELMitteilungsArt) {
        _arten.value = _arten.value + (art to neu)
        ablage.schreiben(schluessel(art), neu.toString())
    }

    fun zeigen(m: ELMitteilung): Boolean {
        if (!_an.value) return false
        val art = ELMitteilungsArt.aus(m) ?: return false
        return ist(art)
    }

    private companion object {
        const val HAUPT = "el.mitteilungen"
        fun schluessel(art: ELMitteilungsArt) = "el.mitteilung.${art.name.lowercase()}"
    }
}

/**
 * Merkt sich den letzten Stand und holt bei Änderung die neuen Mitteilungen. Der erste Stand nach der Anmeldung gilt nur
 * als Ausgangspunkt (keine Flut alter Mitteilungen). Läuft schon ein Abruf, meldet der nächste Takt den Stand erneut.
 */
class ELMitteilungsAbruf(
    private val schnittstelle: ELMitteilungsSchnittstelle,
    private val einstellungen: ELMitteilungsEinstellungen,
    private val senden: (LageMeldung) -> Unit,
) {
    var letzterStand: String? = null
        private set
    private var laeuft = false

    suspend fun standGemeldet(stand: String) {
        val alt = letzterStand ?: run { letzterStand = stand; return }
        if (stand == alt || laeuft) return
        laeuft = true
        try {
            val seite = schnittstelle.mitteilungen(alt)
            letzterStand = seite.stand
            seite.mitteilungen.filter(einstellungen::zeigen).forEach { senden(meldung(it)) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Netzfehler (oder 410, das merkt die Ortung selbst): Stand bleibt, beim nächsten Takt erneut
        } finally {
            laeuft = false
        }
    }

    companion object {
        fun meldung(m: ELMitteilung) = LageMeldung(m.text, null, ELMitteilungsArt.aus(m)?.tonart ?: Tonart.WARNUNG1)
    }
}
