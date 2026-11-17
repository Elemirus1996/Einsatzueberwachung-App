package io.github.elemirus1996.einsatzspur.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.elemirus1996.einsatzspur.kern.EinmalOrtung
import io.github.elemirus1996.einsatzspur.kern.NavigationsZiel
import io.github.elemirus1996.einsatzspur.kern.TeamAnsicht
import io.github.elemirus1996.einsatzspur.kern.TeamPunkt
import kotlinx.coroutines.launch

/** Ziel der Einsatzleitung als `geo:`-Link (APP_API.md Abschnitt 13): Android bietet alle installierten Navi-Apps an. */
fun naviStarten(context: Context, ziel: NavigationsZiel) {
    // Nur `geo:` öffnen, nie beliebige Links aus der Antwort
    val link = ziel.geo?.takeIf { it.startsWith("geo:") } ?: return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
    runCatching { context.startActivity(Intent.createChooser(intent, "Navigation zu „${ziel.name}“").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
fun NavigationStartenKnopf(ziel: NavigationsZiel) {
    val context = LocalContext.current
    Button(onClick = { naviStarten(context, ziel) }) { Text("Navigation starten") }
}

/** Punkt am eigenen Standort setzen (Fund, Hinweis). Position nur auf Knopfdruck. */
@Composable
fun PunktSetzenDialog(ansicht: TeamAnsicht, schliessen: () -> Unit) {
    val context = LocalContext.current
    val ortung = remember { EinmalOrtung(context) }
    val bereich = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var ortet by remember { mutableStateOf(false) }
    var fehler by remember { mutableStateOf<String?>(null) }
    /** Fund der Person statt Hinweis (Server 1.17.0): eigenes Symbol, Vorschlag für den Fundort im Bericht */
    var fund by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!ortet) schliessen() },
        title = { Text("Punkt setzen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Der Punkt kommt mit deinem jetzigen Standort zur Einsatzleitung, auf die Karte und als Notiz.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(!fund, { fund = false }, label = { Text("Hinweis") })
                    androidx.compose.material3.FilterChip(fund, { fund = true }, label = { Text("Person gefunden") })
                }
                if (fund) Text("Die Einsatzleitung sieht den Fund sofort und übernimmt ihn als Fundort in den Einsatzbericht.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(TeamPunkt.MAX_TEXT) },
                    label = { Text("Was ist hier?") },
                    placeholder = { Text(if (fund) "z. B. ansprechbar, am Bachufer" else "z. B. Jacke am Wegrand") },
                    maxLines = 3,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!fund) listOf("Spur", "Hinweis", "Gefahr", "Gegenstand").forEach { vorschlag ->
                        OutlinedButton(onClick = { if (text.isEmpty()) text = "$vorschlag: " }) {
                            Text(vorschlag, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                fehler?.let { Text(it, color = Color(0xFFC62828)) }
            }
        },
        confirmButton = {
            if (ortet) {
                CircularProgressIndicator()
            } else {
                TextButton(enabled = fund || text.isNotBlank(), onClick = {
                    ortet = true
                    bereich.launch {
                        if (!ortung.freigegeben) {
                            fehler = "Ohne Freigabe der Ortung kann die App keinen Punkt setzen."
                        } else {
                            val ort = ortung.position()
                            if (ort == null) {
                                fehler = "Keine Position gefunden. Bitte kurz unter freiem Himmel erneut versuchen."
                            } else {
                                val inhalt = text.trim()
                                ansicht.punktSetzen(if (fund) (if (inhalt.isEmpty()) "Person gefunden" else "Person gefunden: $inhalt") else inhalt,
                                    ort.lat, ort.lng, ort.genauigkeit, ort.zeitMs, fund = fund)
                                schliessen()
                            }
                        }
                        ortet = false
                    }
                }) { Text("Senden") }
            }
        },
        dismissButton = { TextButton(enabled = !ortet, onClick = schliessen) { Text("Abbrechen") } },
    )
}
