package io.github.elemirus1996.einsatzspur.kern

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.DateFormat
import java.util.Date

/**
 * Verlängert das Token der Einsatzleitung bei langen Einsätzen (#141, APP_API.md Einsatzleitung Abschnitt 4),
 * wie iOS `ELVerlaengerung.swift`: Läuft ein Einsatz und sind weniger als 6 h übrig, holt die App mit
 * `POST /verlaengern` ein neues Token, ohne das Staffel-Passwort erneut abzufragen. Lehnt der Server ab
 * (409: kein Einsatz mehr oder 72 h erreicht), fragt die App für dieses Token nicht noch einmal und zeigt
 * „Anmeldung läuft um HH:mm ab“.
 */
class ELVerlaengerung(
    private val schnittstelle: ELVerlaengerSchnittstelle,
    private val zugang: ELZugang,
    private val jetzt: () -> Long = System::currentTimeMillis,
    private val uhrzeit: (Long) -> String = { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) },
    /** Neue Sitzung in den Keystore und Ablauf an die Ortung */
    private val beiNeuerSitzung: (ELSitzung) -> Unit,
) {
    private val _hinweis = MutableStateFlow<String?>(null)
    /** Hinweis nach einer Ablehnung, bis ein neues Token da ist */
    val hinweis: StateFlow<String?> = _hinweis

    private var laeuft = false
    /** Für dieses Token hat der Server abgelehnt (409): nicht wiederholen */
    private var abgelehntFuer: String? = null

    /** Soll jetzt verlängert werden? */
    fun faellig(einsatzLaeuft: Boolean): Boolean {
        val sitzung = zugang.sitzung
        if (!einsatzLaeuft || laeuft || abgelehntFuer == sitzung.token) return false
        sitzung.verlaengerbarBisMs?.let { if (it <= sitzung.expiresAtMs) return false }
        val rest = sitzung.expiresAtMs - jetzt()
        return rest in 1 until SCHWELLE_MS
    }

    /** Nach jeder Lage, beim Öffnen der App und im Takt aufrufen. */
    suspend fun pruefen(einsatzLaeuft: Boolean) {
        if (!faellig(einsatzLaeuft)) return
        val alt = zugang.sitzung
        laeuft = true
        zugang.verlaengerungBeginnt()
        try {
            val neu = schnittstelle.verlaengern(alt.geraet)
            zugang.ersetzen(neu)
            _hinweis.value = null
            beiNeuerSitzung(neu)
        } catch (e: ELVerlaengerAbgelehnt) {
            abgelehntFuer = alt.token
            _hinweis.value = "Anmeldung läuft um ${uhrzeit(alt.expiresAtMs)} ab."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Funkloch oder 410: beim nächsten Mal erneut; ein ungültiges Token meldet /lage bzw. /status
        } finally {
            zugang.verlaengerungEndet()
            laeuft = false
        }
    }

    companion object {
        const val SCHWELLE_MS = 6 * 3600 * 1000L
    }
}
