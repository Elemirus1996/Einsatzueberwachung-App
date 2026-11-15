package io.github.elemirus1996.einsatzspur.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.elemirus1996.einsatzspur.kern.ELAktion
import io.github.elemirus1996.einsatzspur.kern.ELBetrieb
import io.github.elemirus1996.einsatzspur.kern.ELHalsbaender
import io.github.elemirus1996.einsatzspur.kern.ELLage
import io.github.elemirus1996.einsatzspur.kern.ELNotiz
import io.github.elemirus1996.einsatzspur.kern.HalsbandAkku
import io.github.elemirus1996.einsatzspur.kern.TeamLage
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Aktionen für ein Team wie iOS `ELTeamAktionenView`: Suche starten (mit Halsband-Rückfrage), Pause, Weiter, Stopp,
 * Zurücksetzen, Mitteilung an das Team.
 */
@Composable
fun ELTeamAktionenDialog(team: ELLage.Team, betrieb: ELBetrieb, schliessen: () -> Unit) {
    val bereich = rememberCoroutineScope()
    var mitteilung by remember { mutableStateOf("") }
    var halsbaender by remember { mutableStateOf<ELHalsbaender?>(null) }
    var frage by remember { mutableStateOf<Pair<String, ELAktion>?>(null) }

    fun aktion(a: ELAktion) {
        bereich.launch { betrieb.ausfuehren(a) }
        schliessen()
    }

    fun starten(halsbandId: String?) = aktion(ELAktion.teamStart(team.teamId, team.teamName, halsbandId))

    Dialog(onDismissRequest = schliessen) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(team.teamName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(listOfNotNull(statusText(team.suche.status), team.suchgebiet?.name, team.halsband?.let { it.name ?: it.id }).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                when (team.suche.status) {
                    TeamLage.SucheStatus.BEREIT, TeamLage.SucheStatus.BEENDET -> {
                        Button(onClick = {
                            bereich.launch {
                                val liste = betrieb.halsbaender()
                                // Ohne Liste oder ohne freies Halsband: mit dem zugewiesenen starten
                                if (liste == null || liste.frei.isEmpty()) starten(team.halsband?.id) else halsbaender = liste
                            }
                        }, Modifier.fillMaxWidth()) { Text("Suche starten") }
                        if (team.suche.status == TeamLage.SucheStatus.BEENDET) {
                            OutlinedButton(onClick = {
                                frage = "Suchzeit von ${team.teamName} zurücksetzen?" to ELAktion.teamZuruecksetzen(team.teamId, team.teamName)
                            }, Modifier.fillMaxWidth()) { Text("Suchzeit zurücksetzen") }
                        }
                    }
                    TeamLage.SucheStatus.LAEUFT -> {
                        Button(onClick = { aktion(ELAktion.teamPause(team.teamId, team.teamName)) }, Modifier.fillMaxWidth()) { Text("Pause") }
                        OutlinedButton(onClick = { frage = stoppFrage(team) }, Modifier.fillMaxWidth()) { Text("Suche beenden") }
                    }
                    TeamLage.SucheStatus.KURZPAUSE -> {
                        Button(onClick = { aktion(ELAktion.teamWeiter(team.teamId, team.teamName)) }, Modifier.fillMaxWidth()) { Text("Weiter") }
                        OutlinedButton(onClick = { frage = stoppFrage(team) }, Modifier.fillMaxWidth()) { Text("Suche beenden") }
                    }
                }
                HorizontalDivider()
                OutlinedTextField(mitteilung, { mitteilung = it }, Modifier.fillMaxWidth(), label = { Text("Mitteilung an ${team.teamName}") })
                Text("Erscheint in der Team-App, auch auf dem Sperrbildschirm.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = schliessen) { Text("Schließen") }
                    Button(enabled = mitteilung.isNotBlank(), onClick = { aktion(ELAktion.mitteilung(team.teamId, team.teamName, mitteilung.trim())) }) {
                        Text("Senden")
                    }
                }
            }
        }
    }

    // Halsband-Rückfrage wie im Browser: frei verfügbare Halsbänder, das zuletzt benutzte zuerst, oder ohne
    halsbaender?.let { liste ->
        val zuletzt = liste.zuletzt[team.teamId]
        AlertDialog(
            onDismissRequest = { halsbaender = null },
            title = { Text("Halsband für ${team.teamName}") },
            text = {
                Column {
                    liste.frei.sortedByDescending { it.id == zuletzt }.forEach { h ->
                        Row(Modifier.fillMaxWidth().clickable { halsbaender = null; starten(h.id) }.padding(vertical = 10.dp)) {
                            Text(h.name ?: h.id, Modifier.weight(1f))
                            if (h.id == zuletzt) Text("zuletzt  ", style = MaterialTheme.typography.bodySmall)
                            HalsbandAkku.text(h.akku)?.let { Text(it) }
                        }
                    }
                    TextButton(onClick = { halsbaender = null; starten(null) }) { Text("Ohne Halsband starten") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { halsbaender = null }) { Text("Abbrechen") } },
        )
    }

    frage?.let { (text, a) ->
        AlertDialog(
            onDismissRequest = { frage = null },
            title = { Text(text) },
            text = if (a.pfad.endsWith("/stopp")) ({ Text("Der Track wird gespeichert. Für eine Kurzpause „Pause“ wählen.") }) else null,
            confirmButton = { TextButton(onClick = { frage = null; aktion(a) }) { Text("Ja") } },
            dismissButton = { TextButton(onClick = { frage = null }) { Text("Abbrechen") } },
        )
    }
}

private fun stoppFrage(team: ELLage.Team) = "Suche von ${team.teamName} beenden?" to ELAktion.teamStopp(team.teamId, team.teamName)

private fun statusText(s: TeamLage.SucheStatus) = when (s) {
    TeamLage.SucheStatus.BEREIT -> "Bereit"
    TeamLage.SucheStatus.LAEUFT -> "Sucht"
    TeamLage.SucheStatus.KURZPAUSE -> "Kurzpause"
    TeamLage.SucheStatus.BEENDET -> "Beendet"
}
