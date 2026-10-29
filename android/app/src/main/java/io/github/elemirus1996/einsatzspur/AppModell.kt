package io.github.elemirus1996.einsatzspur

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.elemirus1996.einsatzspur.dienst.OrtungsDienst
import io.github.elemirus1996.einsatzspur.kern.Ablage
import io.github.elemirus1996.einsatzspur.kern.LageMeldung
import io.github.elemirus1996.einsatzspur.kern.LocationManagerQuelle
import io.github.elemirus1996.einsatzspur.kern.LokaleMitteilungen
import io.github.elemirus1996.einsatzspur.kern.Schluesselbund
import io.github.elemirus1996.einsatzspur.kern.SchnittstellenFehler
import io.github.elemirus1996.einsatzspur.kern.SqlitePuffer
import io.github.elemirus1996.einsatzspur.kern.SteuerAntwort
import io.github.elemirus1996.einsatzspur.kern.TeamAnsicht
import io.github.elemirus1996.einsatzspur.kern.TeamClient
import io.github.elemirus1996.einsatzspur.kern.TeamOrtung
import io.github.elemirus1996.einsatzspur.kern.TeamSitzung
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Hält die angemeldete Rolle und verbindet Schlüsselbund, Schnittstelle, Ortung und Vordergrunddienst. */
class AppModell(private val context: Context) {
    private val bereich = MainScope()
    private val schluesselbund = Schluesselbund(context)
    private val quelle = LocationManagerQuelle(context)
    private val puffer by lazy { SqlitePuffer(context) }

    private val _team = MutableStateFlow<TeamOrtung?>(null)
    val team: StateFlow<TeamOrtung?> = _team
    private val _sitzung = MutableStateFlow<TeamSitzung?>(null)
    val sitzung: StateFlow<TeamSitzung?> = _sitzung
    private val _ansicht = MutableStateFlow<TeamAnsicht?>(null)
    val ansicht: StateFlow<TeamAnsicht?> = _ansicht

    private var imVordergrund = false
    private val mitteilungen = LokaleMitteilungen(context) { imVordergrund }
    /** Abmelden durch den Nutzer meldet kein „Einsatz beendet“. */
    private var abmeldungDurchNutzer = false
    private val ablage = object : Ablage {
        private val prefs = context.getSharedPreferences("ansicht", Context.MODE_PRIVATE)
        override fun lesen(schluessel: String) = prefs.getString(schluessel, null)
        override fun schreiben(schluessel: String, wert: String?) = prefs.edit().putString(schluessel, wert).apply()
    }

    init {
        schluesselbund.sitzungLesen()?.let {
            teamStarten(it)
            konfigurieren()
        }
    }

    fun anmelden(sitzung: TeamSitzung) {
        _team.value?.anwenden(SteuerAntwort.STOP)
        schluesselbund.sitzungSpeichern(sitzung)
        teamStarten(sitzung)
        dienstSicherstellen()
        konfigurieren()
    }

    fun abmelden() {
        abmeldungDurchNutzer = true
        _team.value?.anwenden(SteuerAntwort.STOP)
    }

    /** Activity sichtbar: Live-Verbindung; sonst nur die Lage jede Minute (für Mitteilungen). */
    fun vordergrund(sichtbar: Boolean) {
        imVordergrund = sichtbar
        if (sichtbar) _ansicht.value?.starten() else _ansicht.value?.hintergrund()
    }

    /** Nach dem Ende bleibt „beendet“ stehen, bis der Nutzer zurück zum Start geht. */
    fun schliessen() {
        if (_team.value?.zustand?.value != TeamOrtung.Zustand.BEENDET) return
        _team.value = null
        _ansicht.value = null
        _sitzung.value = null
    }

    /** App kommt in den Vordergrund: Ablauf prüfen, Status holen, Dienst sicherstellen. */
    fun aktiviert() {
        val team = _team.value ?: return
        team.ablaufPruefen()
        dienstSicherstellen()
        bereich.launch { team.statusPruefen() }
    }

    /**
     * Startet den Vordergrunddienst (Typ location), solange eine Ortung läuft. Nur aus dem Vordergrund
     * aufrufen: Android erlaubt den Start sonst nicht, und ohne Standort-Berechtigung auch nicht.
     */
    fun dienstSicherstellen() {
        val team = _team.value ?: return
        if (team.zustand.value == TeamOrtung.Zustand.BEENDET) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        runCatching { ContextCompat.startForegroundService(context, Intent(context, OrtungsDienst::class.java)) }
    }

    private fun konfigurieren() {
        val sitzung = _sitzung.value ?: return
        val team = _team.value ?: return
        bereich.launch {
            try {
                team.anwenden(TeamClient(sitzung).config().steuerung)
            } catch (e: SchnittstellenFehler.Beendet) {
                team.anwenden(SteuerAntwort.STOP)
            } catch (e: Exception) {
                team.statusPruefen()
            }
        }
    }

    private fun teamStarten(sitzung: TeamSitzung) {
        _sitzung.value = sitzung
        val client = TeamClient(sitzung)
        val team = TeamOrtung(
            schnittstelle = client,
            quelle = quelle,
            puffer = puffer,
            ablaufMs = sitzung.expiresAtMs,
            bereich = bereich,
            beimBeenden = ::teamBeendet,
        )
        // Der Modus aus /lage steuert die Ortung; der Suchstatus (Kurzpause) nur die Anzeige
        val ansicht = TeamAnsicht(client, sitzung.teamId ?: sitzung.teamName, ablage, bereich) { modus ->
            _team.value?.anwenden(SteuerAntwort(modus), melden = false)
        }
        team.beiModuswechsel = { ansicht.modusGeaendert() }
        ansicht.beiMeldungen = { liste -> liste.forEach(mitteilungen::senden) }
        _team.value = team
        _ansicht.value = ansicht
        if (imVordergrund) ansicht.starten() else ansicht.hintergrund()
        team.ablaufPruefen()
    }

    private fun teamBeendet() {
        if (!abmeldungDurchNutzer) mitteilungen.senden(LageMeldung.EINSATZ_BEENDET)
        abmeldungDurchNutzer = false
        _ansicht.value?.vergessen()
        schluesselbund.loeschen(Schluesselbund.KONTO_TEAM)
    }
}
