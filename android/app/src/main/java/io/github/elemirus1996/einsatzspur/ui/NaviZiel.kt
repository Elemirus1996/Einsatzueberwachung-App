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
    val link = ziel.geo ?: return
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

    AlertDialog(
        onDismissRequest = { if (!ortet) schliessen() },
        title = { Text("Punkt setzen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Der Punkt kommt mit deinem jetzigen Standort zur Einsatzleitung, auf die Karte und als Notiz.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(TeamPunkt.MAX_TEXT) },
                    label = { Text("Was ist hier?") },
                    placeholder = { Text("z. B. Fund: Jacke") },
                    maxLines = 3,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Fund", "Spur", "Hinweis", "Gefahr").forEach { vorschlag ->
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
                TextButton(enabled = text.isNotBlank(), onClick = {
                    ortet = true
                    bereich.launch {
                        if (!ortung.freigegeben) {
                            fehler = "Ohne Freigabe der Ortung kann die App keinen Punkt setzen."
                        } else {
                            val ort = ortung.position()
                            if (ort == null) {
                                fehler = "Keine Position gefunden. Bitte kurz unter freiem Himmel erneut versuchen."
                            } else {
                                ansicht.punktSetzen(text, ort.lat, ort.lng, ort.genauigkeit, ort.zeitMs)
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
