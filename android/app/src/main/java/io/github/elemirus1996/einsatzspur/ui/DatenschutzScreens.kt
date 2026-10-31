package io.github.elemirus1996.einsatzspur.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.elemirus1996.einsatzspur.kern.Datenschutz
import io.github.elemirus1996.einsatzspur.kern.DatenschutzBlock
import io.github.elemirus1996.einsatzspur.kern.DatenschutzFrage
import io.github.elemirus1996.einsatzspur.kern.HeimatStaffel
import java.text.DateFormat
import java.util.Date

/** Datenschutzerklärung der Staffel lesen (#148): einfaches Markdown, Text auswählbar. */
@Composable
fun DatenschutzText(erklaerung: Datenschutz) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            erklaerung.titel?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            erklaerung.standMs?.let {
                Text("Stand: " + DateFormat.getDateInstance(DateFormat.LONG).format(Date(it)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DatenschutzBlock.zerlegen(erklaerung.text ?: "").forEach { block ->
                when (block) {
                    is DatenschutzBlock.Ueberschrift -> Text(
                        fett(block.text), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp),
                        style = if (block.ebene <= 1) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    )
                    is DatenschutzBlock.Absatz -> Text(fett(block.text))
                    is DatenschutzBlock.Punkt -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("•")
                        Text(fett(block.text))
                    }
                }
            }
        }
    }
}

/** **fett** umsetzen; Links bleiben als Text lesbar. */
private fun fett(text: String): AnnotatedString = buildAnnotatedString {
    text.split("**").forEachIndexed { i, teil ->
        if (i % 2 == 1) pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
        append(teil)
        if (i % 2 == 1) pop()
    }
}

/** Abfrage bei der ersten Anmeldung und bei neuer Version: zuerst Datenschutz, dann Heimatstaffel. */
@Composable
fun DatenschutzAbfrage(frage: DatenschutzFrage, abbrechenMoeglich: Boolean, bestaetigen: () -> Unit, abbrechen: () -> Unit) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Text("Datenschutz", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    val erklaerung = frage.erklaerung
                    if (erklaerung != null) DatenschutzText(erklaerung)
                    else Text("Diese Staffel hat noch keine Datenschutzerklärung hinterlegt.")
                }
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = bestaetigen, modifier = Modifier.fillMaxWidth()) {
                        Text(if (frage.erklaerung == null) "OK" else "Zur Kenntnis genommen")
                    }
                    if (abbrechenMoeglich) {
                        OutlinedButton(onClick = abbrechen, modifier = Modifier.fillMaxWidth()) { Text("Nicht anmelden") }
                    }
                }
            }
        }
    }
}

/** „Ist das deine eigene Staffel?“ nach der ersten Anmeldung an einem Server. */
@Composable
fun HeimatFrage(name: String, antworten: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Ist „$name“ deine eigene Staffel?") },
        text = {
            Text("Dann zeigt EinsatzSpur ihren Namen und ihr Logo auf der Startseite. Bei einem gemeinsamen Einsatz mit einer " +
                "anderen Staffel: Nein. Änderbar in den Einstellungen.")
        },
        confirmButton = { TextButton(onClick = { antworten(true) }) { Text("Ja, meine Staffel") } },
        dismissButton = { TextButton(onClick = { antworten(false) }) { Text("Nein") } },
    )
}

/** Einstellungen: Heimatstaffel ansehen und entfernen, Datenschutzerklärungen nachlesen, Version. */
@Composable
fun EinstellungenScreen(
    heimat: HeimatStaffel,
    erklaerungen: Map<String, Datenschutz>,
    version: String,
    zurueck: () -> Unit,
) {
    val eintrag by heimat.eintrag.collectAsState()
    val logo by heimat.logo.collectAsState()
    var lesen by remember { mutableStateOf<Datenschutz?>(null) }
    var entfernenFragen by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler { if (lesen != null) lesen = null else zurueck() }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { if (lesen != null) lesen = null else zurueck() }) { Text("‹ Zurück") }
            val offen = lesen
            if (offen != null) {
                DatenschutzText(offen)
                return@Column
            }
            Text("Einstellungen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            Text("Heimatstaffel", style = MaterialTheme.typography.titleMedium)
            val e = eintrag
            if (e != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    logo?.let { daten ->
                        BitmapFactory.decodeByteArray(daten, 0, daten.size)?.let {
                            Image(it.asImageBitmap(), contentDescription = null, Modifier.size(44.dp))
                        }
                    }
                    Text(e.staffel, style = MaterialTheme.typography.bodyLarge)
                }
                TextButton(onClick = { entfernenFragen = true }) { Text("Heimatstaffel entfernen", color = MaterialTheme.colorScheme.error) }
                Text("Nur die Heimatstaffel erscheint auf der Startseite. Einsätze mit anderen Staffeln ändern daran nichts.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Keine gewählt. Bei der nächsten Anmeldung fragt die App, ob der Server zu deiner Staffel gehört.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()

            Text("Datenschutzerklärungen der Staffeln", style = MaterialTheme.typography.titleMedium)
            if (erklaerungen.isEmpty()) {
                Text("Noch keine. Die Erklärung der Staffel erscheint bei der Anmeldung.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            erklaerungen.entries.sortedBy { it.value.titel ?: it.key }.forEach { (_, d) ->
                Text(d.titel ?: "Datenschutzerklärung", color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().clickable { lesen = d }.padding(vertical = 8.dp))
            }
            Text("Verantwortlich ist die Staffel, die den Server betreibt. Hier steht die zuletzt gezeigte Fassung je Server.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Text("Version $version", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (entfernenFragen) {
        AlertDialog(
            onDismissRequest = { entfernenFragen = false },
            title = { Text("Heimatstaffel entfernen?") },
            text = { Text("Bei der nächsten Anmeldung fragt die App erneut.") },
            confirmButton = { TextButton(onClick = { heimat.entfernen(); entfernenFragen = false }) { Text("Entfernen") } },
            dismissButton = { TextButton(onClick = { entfernenFragen = false }) { Text("Abbrechen") } },
        )
    }
}
