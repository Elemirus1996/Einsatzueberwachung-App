package io.github.elemirus1996.einsatzspur

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.elemirus1996.einsatzspur.dienst.OrtungsDienst
import io.github.elemirus1996.einsatzspur.kern.Ablage
import io.github.elemirus1996.einsatzspur.kern.ELAnmeldung
import io.github.elemirus1996.einsatzspur.kern.ELAnsicht
import io.github.elemirus1996.einsatzspur.kern.ELClient
import io.github.elemirus1996.einsatzspur.kern.ELSitzung
import io.github.elemirus1996.einsatzspur.kern.ELVerlaengerung
import io.github.elemirus1996.einsatzspur.kern.ELZugang
import io.github.elemirus1996.einsatzspur.kern.ServerAdresse
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
import io.github.elemirus1996.einsatzspur.kern.TeamQRCode
import io.github.elemirus1996.einsatzspur.kern.TeamSitzung
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Hält die angemeldete Rolle und verbindet Schlüsselbund, Schnittstelle, Ortung und Vordergrunddienst. */
class AppModell(private val context: Context) {
    private val bereich = MainScope()
    private val schluesselbund = Schluesselbund(context)
    private val quelle = LocationManagerQuelle(context)
    private val puffer by lazy { SqlitePuffer(context) }
    private val elPuffer by lazy { SqlitePuffer(context, "el-positionen.db") }

    private val _team = MutableStateFlow<TeamOrtung?>(null)
    val team: StateFlow<TeamOrtung?> = _team
    private val _sitzung = MutableStateFlow<TeamSitzung?>(null)
    val sitzung: StateFlow<TeamSitzung?> = _sitzung
    private val _ansicht = MutableStateFlow<TeamAnsicht?>(null)
    val ansicht: StateFlow<TeamAnsicht?> = _ansicht

    private var imVordergrund = false
    private val mitteilungen = LokaleMitteilungen(context) { imVordergrund }
    /** Wie eine Ortung endet: entscheidet über Mitteilung und darüber, ob der Anmelde-Code bleibt. */
    enum class Abmeldung(val meldetEinsatzende: Boolean, val behaeltZugang: Boolean) {
        /** Server: Einsatzende, Team gelöscht, Token abgelaufen (410/stop) */
        SERVER(meldetEinsatzende = true, behaeltZugang = false),
        /** Nutzer wechselt das Team im selben Einsatz */
        TEAM_WECHSEL(meldetEinsatzende = false, behaeltZugang = true),
        /** Nutzer meldet sich vom Einsatz ab */
        EINSATZ(meldetEinsatzende = false, behaeltZugang = false),
    }

    private var abmeldung = Abmeldung.SERVER

    /** Server und Anmelde-Code des laufenden Einsatzes (Keystore), solange der Einsatz läuft. */
    private val _einsatzZugang = MutableStateFlow(schluesselbund.zugangLesen())
    val einsatzZugang: StateFlow<TeamQRCode?> = _einsatzZugang

    /** Teamauswahl desselben Einsatzes nach „Team wechseln“ (gespeicherter Code, kein Scan). */
    private val _teamWahl = MutableStateFlow<TeamQRCode?>(null)
    val teamWahl: StateFlow<TeamQRCode?> = _teamWahl
    private val ablage = object : Ablage {
        private val prefs = context.getSharedPreferences("ansicht", Context.MODE_PRIVATE)
        override fun lesen(schluessel: String) = prefs.getString(schluessel, null)
        override fun schreiben(schluessel: String, wert: String?) = prefs.edit().putString(schluessel, wert).apply()
    }

    // Rolle Einsatzleitung (nur im VPN). Immer nur eine Rolle aktiv, beide teilen sich die Ortung.
    private val _elSitzung = MutableStateFlow<ELSitzung?>(null)
    val elSitzung: StateFlow<ELSitzung?> = _elSitzung
    private val _elOrtung = MutableStateFlow<TeamOrtung?>(null)
    val elOrtung: StateFlow<TeamOrtung?> = _elOrtung
    private val _elAnsicht = MutableStateFlow<ELAnsicht?>(null)
    val elAnsicht: StateFlow<ELAnsicht?> = _elAnsicht
    private val _elVerlaengerung = MutableStateFlow<ELVerlaengerung?>(null)
    val elVerlaengerung: StateFlow<ELVerlaengerung?> = _elVerlaengerung
    /** Hinweis nach Ende der EL-Sitzung durch den Server (Token abgelaufen, Passwort geändert). */
    private val _elHinweis = MutableStateFlow<String?>(null)
    val elHinweis: StateFlow<String?> = _elHinweis
    private var elAbmeldungDurchNutzer = false
    private var elTakt: Job? = null

    /** Zuletzt verwendete interne Adresse (nicht geheim) */
    private val einstellungen = context.getSharedPreferences("einstellungen", Context.MODE_PRIVATE)
    var elServerAdresse: String
        get() = einstellungen.getString("el.server", "") ?: ""
        set(wert) = einstellungen.edit().putString("el.server", wert).apply()

    /** Die Ortung, die der Vordergrunddienst am Leben hält: Team oder Einsatzleitung. */
    val aktiveOrtung: StateFlow<TeamOrtung?> = combine(_team, _elOrtung) { t, e -> t ?: e }
        .stateIn(bereich, SharingStarted.Eagerly, null)

    init {
        schluesselbund.elSitzungLesen()?.let { elStarten(it) }
        schluesselbund.sitzungLesen()?.let {
            teamStarten(it)
            konfigurieren()
        }
    }

    fun anmelden(sitzung: TeamSitzung, zugang: TeamQRCode) {
        schluesselbund.zugangSpeichern(zugang)
        _einsatzZugang.value = zugang
        _teamWahl.value = null
        abmeldung = Abmeldung.TEAM_WECHSEL
        _team.value?.anwenden(SteuerAntwort.STOP)
        schluesselbund.sitzungSpeichern(sitzung)
        teamStarten(sitzung)
        dienstSicherstellen()
        konfigurieren()
    }

    /**
     * „Team wechseln“: Puffer hochladen, Ortung des alten Teams beenden, Token löschen, dann Teamauswahl
     * desselben Einsatzes. `false`, wenn noch Positionen im Puffer liegen und `trotzdem` nicht gesetzt ist.
     */
    suspend fun teamWechseln(trotzdem: Boolean = false): Boolean {
        val team = _team.value ?: return true
        val zugang = _einsatzZugang.value ?: return true
        if (team.gepuffert.value > 0) team.hochladen()
        if (!trotzdem && team.gepuffert.value > 0) return false
        abmeldung = Abmeldung.TEAM_WECHSEL
        team.anwenden(SteuerAntwort.STOP)
        teamEntfernen()
        _teamWahl.value = zugang
        return true
    }

    /** „Vom Einsatz abmelden“: dauerhaft, löscht Token und Anmelde-Code, zurück zum Start. */
    fun vomEinsatzAbmelden() {
        abmeldung = Abmeldung.EINSATZ
        val team = _team.value
        if (team != null && team.zustand.value != TeamOrtung.Zustand.BEENDET) team.anwenden(SteuerAntwort.STOP) else zugangLoeschen()
        teamEntfernen()
        _teamWahl.value = null
    }

    /** Gespeicherter Code gilt nicht mehr (login/teams → 401): neu scannen. */
    fun zugangUngueltig() = zugangLoeschen()

    fun teamWahlAbbrechen() {
        _teamWahl.value = null
    }

    /** Activity sichtbar: Live-Verbindung; sonst nur die Lage jede Minute (für Mitteilungen). */
    fun vordergrund(sichtbar: Boolean) {
        imVordergrund = sichtbar
        if (sichtbar) _ansicht.value?.starten() else _ansicht.value?.hintergrund()
        if (sichtbar) _elAnsicht.value?.starten() else _elAnsicht.value?.anhalten()
    }

    // MARK: Einsatzleitung

    /** Anmeldung mit dem Staffel-Passwort an der internen Adresse (nur im VPN erreichbar). */
    suspend fun elAnmelden(server: ServerAdresse, passwort: String, geraet: String?) {
        val (sitzung, _) = ELAnmeldung(server).anmelden(passwort, geraet)
        schluesselbund.elSitzungSpeichern(sitzung)
        elServerAdresse = server.name
        _elHinweis.value = null
        elStarten(sitzung)
    }

    /** Abmelden: Token auf dem Server ungültig machen, Ortung aus, Token löschen. */
    suspend fun elAbmelden() {
        val sitzung = _elSitzung.value ?: return
        elAbmeldungDurchNutzer = true
        ELClient(ELZugang(sitzung)).abmelden()
        _elOrtung.value?.anwenden(SteuerAntwort.STOP)
    }

    fun elHinweisGelesen() {
        _elHinweis.value = null
    }

    private fun elStarten(sitzung: ELSitzung) {
        _elSitzung.value = sitzung
        val zugang = ELZugang(sitzung)
        val client = ELClient(zugang)
        val ortung = TeamOrtung(
            schnittstelle = client, quelle = quelle, puffer = elPuffer, ablaufMs = sitzung.expiresAtMs,
            bereich = bereich, pauseOhneOrtung = true, beimBeenden = ::elBeendet,
        )
        val ansicht = ELAnsicht(client, bereich) { modus ->
            _elOrtung.value?.anwenden(SteuerAntwort(modus), melden = false)
            // Einsatz läuft und die App ist offen: Vordergrunddienst für die Ortung starten
            if (modus == io.github.elemirus1996.einsatzspur.kern.Modus.TRACK && imVordergrund) dienstSicherstellen()
        }
        // #141: neues Token in den Keystore, Ablauf an die Ortung; alle Teile lesen es aus `zugang`
        val verlaengerung = ELVerlaengerung(client, zugang) { neu ->
            schluesselbund.elSitzungSpeichern(neu)
            _elSitzung.value = neu
            _elOrtung.value?.ablaufSetzen(neu.expiresAtMs)
        }
        ansicht.nachLage = { lage -> verlaengerung.pruefen(lage.einsatz.aktiv) }
        _elVerlaengerung.value = verlaengerung
        _elOrtung.value = ortung
        _elAnsicht.value = ansicht
        ortung.ablaufPruefen()
        if (imVordergrund) ansicht.starten()
        // Auch ohne sichtbare Übersicht (Ortung im Hintergrund) regelmäßig prüfen
        elTakt?.cancel()
        elTakt = bereich.launch {
            while (true) {
                delay(10 * 60 * 1000L)
                elVerlaengernPruefen()
            }
        }
    }

    /** Einsatz läuft, wenn die Lage es sagt oder die Ortung der Einsatzleitung im Modus `track` ist. */
    private suspend fun elVerlaengernPruefen() {
        val laeuft = _elAnsicht.value?.lage?.value?.einsatz?.aktiv == true || _elOrtung.value?.zustand?.value == TeamOrtung.Zustand.ORTET
        _elVerlaengerung.value?.pruefen(laeuft)
    }

    private fun elBeendet() {
        _elAnsicht.value?.anhalten()
        schluesselbund.elSitzungLoeschen()
        if (!elAbmeldungDurchNutzer) {
            _elHinweis.value = "Die Anmeldung der Einsatzleitung ist abgelaufen oder das Staffel-Passwort wurde geändert. Bitte neu anmelden."
        }
        elAbmeldungDurchNutzer = false
        elTakt?.cancel()
        elTakt = null
        _elSitzung.value = null
        _elOrtung.value = null
        _elAnsicht.value = null
        _elVerlaengerung.value = null
    }

    /** Nach dem Ende bleibt „beendet“ stehen, bis der Nutzer zurück zum Start geht. */
    fun schliessen() {
        if (_team.value?.zustand?.value != TeamOrtung.Zustand.BEENDET) return
        teamEntfernen()
    }

    private fun teamEntfernen() {
        _team.value = null
        _ansicht.value = null
        _sitzung.value = null
    }

    private fun zugangLoeschen() {
        schluesselbund.zugangLoeschen()
        _einsatzZugang.value = null
    }

    /** App kommt in den Vordergrund: Ablauf prüfen, Status holen, Dienst sicherstellen. */
    fun aktiviert() {
        _elOrtung.value?.let { el ->
            el.ablaufPruefen()
            bereich.launch {
                el.statusPruefen()
                elVerlaengernPruefen()
            }
        }
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
        val team = _team.value
        val el = _elOrtung.value
        when {
            team != null -> if (team.zustand.value == TeamOrtung.Zustand.BEENDET) return
            // Einsatzleitung: Dienst nur, solange sie ortet (ohne Einsatz ist die Ortung ganz aus)
            el != null -> if (el.zustand.value != TeamOrtung.Zustand.ORTET) return
            else -> return
        }
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
        if (abmeldung.meldetEinsatzende) mitteilungen.senden(LageMeldung.EINSATZ_BEENDET)
        if (!abmeldung.behaeltZugang) zugangLoeschen()
        abmeldung = Abmeldung.SERVER
        _ansicht.value?.vergessen()
        schluesselbund.loeschen(Schluesselbund.KONTO_TEAM)
    }
}
