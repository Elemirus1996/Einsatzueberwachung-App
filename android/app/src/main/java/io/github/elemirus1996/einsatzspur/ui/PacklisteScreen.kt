package io.github.elemirus1996.einsatzspur.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.elemirus1996.einsatzspur.kern.Packliste
import java.text.DateFormat
import java.util.Date

/** Knopf auf der Startseite mit Stand „3 von 7“ */
@Composable
fun PacklisteKnopf(liste: Packliste, oeffnen: () -> Unit) {
    val vorlage by liste.vorlage.collectAsState()
    val eigene by liste.eigene.collectAsState()
    val erledigt by liste.erledigt.collectAsState()
    val anzahl = (vorlage?.punkte?.size ?: 0) + eigene.size
    val fertig = (vorlage?.punkte?.count { Packliste.staffel(it.id) in erledigt } ?: 0) + eigene.count { Packliste.eigen(it.id) in erledigt }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(onClick = oeffnen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Packliste", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (anzahl > 0) Text("$fertig von $anzahl", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Packliste wie iOS `PacklisteView`: Punkte der Heimatstaffel (nur abhakbar), eigene Punkte, Erinnerung. Alles nur auf dem Gerät. */
@Composable
fun PacklisteScreen(liste: Packliste, zurueck: () -> Unit) {
    BackHandler(onBack = zurueck)
    val vorlage by liste.vorlage.collectAsState()
    val eigene by liste.eigene.collectAsState()
    val erledigt by liste.erledigt.collectAsState()
    val tage by liste.erinnerungTage.collectAsState()
    val durchgegangen by liste.durchgegangen.collectAsState()
    var neuerPunkt by remember { mutableStateOf("") }
    var durchgegangenFragen by remember { mutableStateOf(false) }
    var loeschenId by remember { mutableStateOf<String?>(null) }
    val gruppen = vorlage?.punkte.orEmpty().groupBy { it.gruppe }.toList()

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = zurueck) { Text("‹ Zurück") }
                Text("Packliste", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { durchgegangenFragen = true }, enabled = (vorlage?.punkte?.size ?: 0) + eigene.size > 0) { Text("Durchgegangen") }
            }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (vorlage == null) {
                    item {
                        Text("Deine Staffel hat noch keine Packliste geschickt. Sie kommt bei der nächsten Anmeldung an einem Einsatz oder einer Übung deiner Heimatstaffel.",
                            Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                gruppen.forEach { (gruppe, punkte) ->
                    item { Ueberschrift(gruppe ?: "Staffel") }
                    items(punkte, key = { "s" + it.id }) { punkt ->
                        Zeile(punkt.text, Packliste.staffel(punkt.id) in erledigt) { liste.umschalten(Packliste.staffel(punkt.id)) }
                    }
                }
                item { Ueberschrift("Eigene Punkte") }
                items(eigene, key = { "e" + it.id }) { punkt ->
                    Zeile(punkt.text, Packliste.eigen(punkt.id) in erledigt, lang = { loeschenId = punkt.id }) {
                        liste.umschalten(Packliste.eigen(punkt.id))
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(neuerPunkt, { neuerPunkt = it }, Modifier.weight(1f), singleLine = true,
                            placeholder = { Text("Eigenen Punkt hinzufügen") })
                        TextButton(onClick = { liste.eigenenHinzufuegen(neuerPunkt); neuerPunkt = "" }, enabled = neuerPunkt.isNotBlank()) {
                            Text("Hinzufügen")
                        }
                    }
                    Text("Punkte der Staffel ändert nur die Staffel. Eigene Punkte zum Löschen lange drücken.",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item { Ueberschrift("Erinnerung") }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Packliste.TAGE_AUSWAHL.forEach { t ->
                            val gewaehlt = t == tage
                            TextButton(
                                onClick = { liste.erinnerungSetzen(t) },
                                modifier = Modifier.background(
                                    if (gewaehlt) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(50),
                                ),
                            ) { Text(if (t == 0) "Nie" else "$t Tage") }
                        }
                    }
                    durchgegangen?.let {
                        Text("Zuletzt durchgegangen: " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)),
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("Die Erinnerung kommt als Mitteilung auf diesem Gerät, um 18 Uhr. Was du abhakst, bleibt auf dem Handy; deine Staffel erfährt davon nichts.",
                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (durchgegangenFragen) {
        AlertDialog(
            onDismissRequest = { durchgegangenFragen = false },
            title = { Text("Liste durchgegangen?") },
            text = { Text("Die Haken werden für das nächste Mal geleert. Die nächste Erinnerung zählt ab heute.") },
            confirmButton = { TextButton(onClick = { liste.durchgegangenMerken(); durchgegangenFragen = false }) { Text("Ja") } },
            dismissButton = { TextButton(onClick = { durchgegangenFragen = false }) { Text("Abbrechen") } },
        )
    }
    loeschenId?.let { id ->
        AlertDialog(
            onDismissRequest = { loeschenId = null },
            title = { Text("Eigenen Punkt löschen?") },
            confirmButton = { TextButton(onClick = { liste.eigenenLoeschen(id); loeschenId = null }) { Text("Löschen", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { loeschenId = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun Ueberschrift(text: String) {
    Column {
        Spacer(Modifier.width(1.dp))
        Text(text, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Zeile(text: String, fertig: Boolean, lang: (() -> Unit)? = null, umschalten: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (lang != null) Modifier.combinedClickable(onClick = umschalten, onLongClick = lang) else Modifier.clickable(onClick = umschalten))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = fertig, onCheckedChange = { umschalten() })
        Text(text, color = if (fertig) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (fertig) TextDecoration.LineThrough else null)
    }
}
