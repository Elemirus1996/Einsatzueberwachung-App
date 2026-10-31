package io.github.elemirus1996.einsatzspur.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.elemirus1996.einsatzspur.kern.GeraeteHinweis
import io.github.elemirus1996.einsatzspur.kern.GeraeteHinweise
import io.github.elemirus1996.einsatzspur.kern.TeamAuswahl
import io.github.elemirus1996.einsatzspur.kern.TeamQRCode
import io.github.elemirus1996.einsatzspur.kern.TeamSitzung
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.elemirus1996.einsatzspur.EinsatzSpurApp

class MainActivity : ComponentActivity() {
    private val modell get() = (application as EinsatzSpurApp).modell

    /** „Beim Verwenden“ genügt: Der Vordergrunddienst ortet damit auch bei gesperrtem Handy. */
    private val berechtigungen = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        modell.dienstSicherstellen()
        hinweise.value = GeraeteHinweise.pruefen(this)
    }

    /** Was die Ortung auf diesem Gerät verhindern kann (Berechtigungen, Akku-Optimierung); bei jedem Zurückkehren neu. */
    private val hinweise = mutableStateOf<List<GeraeteHinweis>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dunkel = isSystemInDarkTheme()
            val orange = Color(0xFFF07A2A)
            MaterialTheme(colorScheme = if (dunkel) darkColorScheme(primary = orange) else lightColorScheme(primary = orange)) {
                val team by modell.team.collectAsState()
                val sitzung by modell.sitzung.collectAsState()
                val ansicht by modell.ansicht.collectAsState()
                val einsatzZugang by modell.einsatzZugang.collectAsState()
                val teamWahl by modell.teamWahl.collectAsState()
                val elOrtung by modell.elOrtung.collectAsState()
                val elAnsicht by modell.elAnsicht.collectAsState()
                val elVerlaengerung by modell.elVerlaengerung.collectAsState()
                val elBetrieb by modell.elBetrieb.collectAsState()
                val elHinweis by modell.elHinweis.collectAsState()
                var elAnmeldungOffen by remember { mutableStateOf(elHinweis != null) }
                var einstellungenOffen by remember { mutableStateOf(false) }
                val datenschutzFrage by modell.datenschutzFrage.collectAsState()
                val heimatFrage by modell.heimatFrage.collectAsState()
                val ortung = team
                val zugang = sitzung
                val lage = ansicht
                val elO = elOrtung
                val elA = elAnsicht
                val elB = elBetrieb
                if (einstellungenOffen) {
                    EinstellungenScreen(modell.heimat, modell.datenschutz.alle(), version(), zurueck = { einstellungenOffen = false })
                } else if (elO != null && elA != null && elB != null) {
                    ELUebersichtScreen(elA, elO, elB, elVerlaengerung, abmelden = {
                        modell.elAbmelden()
                        elAnmeldungOffen = false
                    })
                } else if (elAnmeldungOffen || elHinweis != null) {
                    ELAnmeldungScreen(
                        modell.elServerAdresse, elHinweis,
                        anmelden = { server, passwort, geraet ->
                            berechtigungenAnfragen()
                            modell.elAnmelden(server, passwort, geraet)
                        },
                        zurueck = {
                            elAnmeldungOffen = false
                            modell.elHinweisGelesen()
                        },
                    )
                } else if (ortung != null && zugang != null && lage != null) {
                    TeamAnsichtScreen(
                        ortung, lage, zugang, hinweise.value,
                        einstellungOeffnen = { runCatching { startActivity(it) } },
                        kannWechseln = einsatzZugang != null,
                        teamWechseln = modell::teamWechseln,
                        vomEinsatzAbmelden = modell::vomEinsatzAbmelden,
                        schliessen = modell::schliessen,
                    )
                } else {
                    val anmelden = { sitzung: TeamSitzung, zugang: TeamQRCode, auswahl: TeamAuswahl ->
                        berechtigungenAnfragen()
                        modell.anmelden(sitzung, zugang, auswahl)
                    }
                    // #148: vor dem Token die Erklärung der Staffel
                    val vorAuswahl = { zugang: TeamQRCode, auswahl: TeamAuswahl, abbrechen: () -> Unit ->
                        modell.datenschutzFragen(auswahl.server?.kennung ?: zugang.server.name, auswahl.datenschutz, abbrechen)
                        Unit
                    }
                    val wahl = teamWahl
                    if (wahl != null) {
                        Surface(Modifier.fillMaxSize()) {
                            AnmeldungScreen(wahl, anmelden, zurueck = modell::teamWahlAbbrechen, zugangUngueltig = modell::zugangUngueltig,
                                vorAuswahl = vorAuswahl, datenschutzOffen = datenschutzFrage != null)
                        }
                    } else {
                        StartScreen(
                            einsatzZugang, anmelden, zugangUngueltig = modell::zugangUngueltig, einsatzleitung = { elAnmeldungOffen = true },
                            vorAuswahl = vorAuswahl, datenschutzOffen = datenschutzFrage != null,
                            heimat = modell.heimat, einstellungen = { einstellungenOffen = true },
                        )
                    }
                }
                // Zuerst Datenschutz, dann Heimatstaffel
                datenschutzFrage?.let { frage ->
                    DatenschutzAbfrage(frage, modell.datenschutzAbbrechenMoeglich,
                        bestaetigen = modell::datenschutzBestaetigen, abbrechen = modell::datenschutzAbbrechen)
                }
                if (datenschutzFrage == null) heimatFrage?.let { (_, name) -> HeimatFrage(name, modell::heimatBeantworten) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        modell.vordergrund(true)
    }

    override fun onStop() {
        modell.vordergrund(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        hinweise.value = GeraeteHinweise.pruefen(this)
        modell.aktiviert()
    }

    private fun version(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "–"

    private fun berechtigungenAnfragen() {
        val liste = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) liste += Manifest.permission.POST_NOTIFICATIONS
        berechtigungen.launch(liste.toTypedArray())
    }
}
