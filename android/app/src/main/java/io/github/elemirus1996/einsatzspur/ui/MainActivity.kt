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
                val elHinweis by modell.elHinweis.collectAsState()
                var elAnmeldungOffen by remember { mutableStateOf(elHinweis != null) }
                val ortung = team
                val zugang = sitzung
                val lage = ansicht
                val elO = elOrtung
                val elA = elAnsicht
                if (elO != null && elA != null) {
                    ELUebersichtScreen(elA, elO, elVerlaengerung, abmelden = {
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
                    val anmelden = { sitzung: TeamSitzung, zugang: TeamQRCode ->
                        berechtigungenAnfragen()
                        modell.anmelden(sitzung, zugang)
                    }
                    val wahl = teamWahl
                    if (wahl != null) {
                        Surface(Modifier.fillMaxSize()) {
                            AnmeldungScreen(wahl, anmelden, zurueck = modell::teamWahlAbbrechen, zugangUngueltig = modell::zugangUngueltig)
                        }
                    } else {
                        StartScreen(einsatzZugang, anmelden, zugangUngueltig = modell::zugangUngueltig, einsatzleitung = { elAnmeldungOffen = true })
                    }
                }
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

    private fun berechtigungenAnfragen() {
        val liste = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) liste += Manifest.permission.POST_NOTIFICATIONS
        berechtigungen.launch(liste.toTypedArray())
    }
}
