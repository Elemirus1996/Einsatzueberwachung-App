package io.github.elemirus1996.einsatzspur.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.elemirus1996.einsatzspur.R
import io.github.elemirus1996.einsatzspur.kern.AnmeldeFehler
import io.github.elemirus1996.einsatzspur.kern.ServerAdresse
import io.github.elemirus1996.einsatzspur.kern.TeamAnmeldung
import io.github.elemirus1996.einsatzspur.kern.TeamAuswahl
import io.github.elemirus1996.einsatzspur.kern.TeamEintrag
import io.github.elemirus1996.einsatzspur.kern.TeamOrtung
import io.github.elemirus1996.einsatzspur.kern.TeamQRCode
import io.github.elemirus1996.einsatzspur.kern.TeamSitzung
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.DateFormat
import java.util.Date

/**
 * Startseite: Rollenwahl, dann Anmeldung des Teams (Server und Anmelde-Code; QR-Scan folgt).
 * Läuft der Einsatz noch (Code gespeichert), führt „Team“ direkt zur Teamauswahl.
 */
@Composable
fun StartScreen(einsatzZugang: TeamQRCode?, anmelden: (TeamSitzung, TeamQRCode) -> Unit, zugangUngueltig: () -> Unit) {
    var anmeldungOffen by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize()) {
        if (anmeldungOffen) {
            AnmeldungScreen(einsatzZugang, fertig = anmelden, zurueck = { anmeldungOffen = false }, zugangUngueltig = zugangUngueltig)
            return@Surface
        }
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Box(
                Modifier.size(96.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF1D2A3A)),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, Modifier.size(140.dp))
            }
            Text("EinsatzSpur", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(32.dp))
            Rollenknopf("Team", "QR-Code aus dem Einsatz scannen", aktiv = true) { anmeldungOffen = true }
            Rollenknopf("Einsatzleitung", "Kommt in einer späteren Version", aktiv = false) {}
            val context = androidx.compose.ui.platform.LocalContext.current
            var lizenzen by remember { mutableStateOf<String?>(null) }
            TextButton(onClick = { lizenzen = context.assets.open("lizenzen.txt").bufferedReader().use { it.readText() } }) {
                Text("Lizenzen", style = MaterialTheme.typography.bodySmall)
            }
            lizenzen?.let { text ->
                AlertDialog(
                    onDismissRequest = { lizenzen = null },
                    title = { Text("Lizenzen") },
                    text = { Text(text, Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
                    confirmButton = { TextButton(onClick = { lizenzen = null }) { Text("OK") } },
                )
            }
        }
    }
}

@Composable
private fun Rollenknopf(titel: String, text: String, aktiv: Boolean, aktion: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = aktiv, onClick = aktion)
            .padding(16.dp)
    ) {
        val farbe = if (aktiv) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline
        Text(titel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = farbe)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = farbe)
    }
}

/** Server und Code eingeben, Server bestätigen, Team wählen. Mit `vorgabe` direkt die Teamauswahl ohne Scan. */
@Composable
fun AnmeldungScreen(
    vorgabe: TeamQRCode?,
    fertig: (TeamSitzung, TeamQRCode) -> Unit,
    zurueck: () -> Unit,
    zugangUngueltig: () -> Unit,
) {
    val bereich = rememberCoroutineScope()
    var server by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var bestaetigen by remember { mutableStateOf<TeamQRCode?>(null) }
    var auswahl by remember { mutableStateOf<Pair<TeamQRCode, TeamAuswahl>?>(null) }
    var laedt by remember { mutableStateOf(false) }
    var fehler by remember { mutableStateOf<String?>(null) }
    var scannerOffen by remember { mutableStateOf(false) }

    fun ausfuehren(block: suspend () -> Unit) {
        bereich.launch {
            laedt = true
            try {
                block()
            } catch (e: Exception) {
                fehler = fehlerText(e)
            } finally {
                laedt = false
            }
        }
    }

    LaunchedEffect(vorgabe) {
        if (vorgabe == null || auswahl != null) return@LaunchedEffect
        laedt = true
        try {
            auswahl = vorgabe to TeamAnmeldung(vorgabe).teams()
        } catch (e: AnmeldeFehler.CodeUngueltig) {
            // Gespeicherter Code gilt nicht mehr: neu eingeben bzw. scannen
            zugangUngueltig()
            fehler = "Der Anmelde-Code gilt nicht mehr, vermutlich wurde ein neuer Einsatz gestartet. Bitte den Code neu eingeben."
        } catch (e: Exception) {
            fehler = fehlerText(e)
        } finally {
            laedt = false
        }
    }

    if (scannerOffen) {
        QrScanner(gefunden = { scannerOffen = false; bestaetigen = it }, abbrechen = { scannerOffen = false })
        return
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = { if (auswahl != null) auswahl = null else zurueck() }) { Text("‹ Zurück") }
        val aktuell = auswahl
        if (aktuell == null) {
            Text("Team anmelden", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = { scannerOffen = true }, Modifier.fillMaxWidth(), enabled = !laedt) { Text("QR-Code scannen") }
            Text("Den QR-Code zeigt die Einsatzleitung im Monitor unter „Team-Handys“.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Oder von Hand", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                server, { server = it }, Modifier.fillMaxWidth(),
                label = { Text("Server, z. B. einsatz.staffel.de") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                code, { code = it }, Modifier.fillMaxWidth(),
                label = { Text("Anmelde-Code") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            )
            Button(
                onClick = {
                    val text = server.trim().let { if (it.contains("://")) it else "https://$it" }
                    val adresse = ServerAdresse.aus(text)
                    val bereinigt = TeamQRCode.bereinigt(code)
                    when {
                        adresse == null -> fehler = "Die Server-Adresse ist ungültig (nur https)."
                        bereinigt == null -> fehler = "Der Anmelde-Code ist ungültig."
                        else -> bestaetigen = TeamQRCode(adresse, bereinigt)
                    }
                },
                enabled = server.isNotBlank() && code.isNotBlank() && !laedt,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Weiter") }
        } else {
            val (zugang, liste) = aktuell
            Text(if (liste.uebung) "Übung" else "Einsatz", style = MaterialTheme.typography.labelLarge)
            Text(liste.einsatz ?: "", style = MaterialTheme.typography.titleMedium)
            Text("${liste.staffel ?: ""} · ${zugang.server.name}", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Welches Team bist du?", style = MaterialTheme.typography.titleMedium)
            if (liste.teams.isEmpty()) Text("Noch keine Teams im Einsatz angelegt.")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(liste.teams, key = TeamEintrag::teamId) { team ->
                    OutlinedButton(
                        onClick = { ausfuehren { fertig(TeamAnmeldung(zugang).anmelden(team.teamId), zugang) } },
                        enabled = !laedt, modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(team.teamName, fontWeight = FontWeight.Bold)
                            Text(listOfNotNull(team.dogName ?: "(ohne Hund)", team.hundefuehrerName).joinToString(" · "))
                        }
                    }
                }
            }
            TextButton(onClick = { ausfuehren { auswahl = zugang to TeamAnmeldung(zugang).teams() } }) { Text("Teamliste aktualisieren") }
        }
        if (laedt) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
    }

    bestaetigen?.let { zugang ->
        AlertDialog(
            onDismissRequest = { bestaetigen = null },
            title = { Text("Am Server ${zugang.server.name} anmelden?") },
            text = { Text("Die App sendet deinen Standort während der Suche nur an diesen Server.") },
            confirmButton = {
                TextButton(onClick = {
                    bestaetigen = null
                    ausfuehren { auswahl = zugang to TeamAnmeldung(zugang).teams() }
                }) { Text("Anmelden") }
            },
            dismissButton = { TextButton(onClick = { bestaetigen = null }) { Text("Abbrechen") } },
        )
    }
    fehler?.let { text ->
        AlertDialog(
            onDismissRequest = { fehler = null },
            title = { Text("Anmeldung nicht möglich") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { fehler = null }) { Text("OK") } },
        )
    }
}

private fun fehlerText(e: Exception): String = when (e) {
    AnmeldeFehler.CodeUngueltig -> "Der Anmelde-Code ist ungültig oder es ist gerade kein Einsatz aktiv."
    AnmeldeFehler.TeamUnbekannt -> "Dieses Team gibt es nicht mehr. Bitte die Teamliste aktualisieren."
    AnmeldeFehler.ZuVieleVersuche -> "Zu viele Versuche, bitte kurz warten (etwa 30 Sekunden)."
    is AnmeldeFehler.Server -> "Der Server meldet einen Fehler. Bitte später erneut versuchen."
    is IOException -> "Keine Verbindung zum Server."
    else -> "Die Antwort des Servers ist unverständlich."
}
