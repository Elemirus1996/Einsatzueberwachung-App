package io.github.elemirus1996.einsatzspur.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import io.github.elemirus1996.einsatzspur.kern.ELAktionAbgelehnt
import io.github.elemirus1996.einsatzspur.kern.ELPerson
import kotlinx.coroutines.launch

/**
 * „Wer bin ich“ nach der Anmeldung der Einsatzleitung (APP_API EL Abschnitt 4a), wie iOS `ELPersonWahlView`.
 * Die Wahl gilt bis zur Abmeldung; ohne Wechsel (Entscheidung Paul 17.11.2026).
 */
@Composable
fun ELPersonWahlScreen(personen: List<ELPerson>, waehlen: suspend (ELPerson) -> Unit, abmelden: suspend () -> Unit) {
    val bereich = rememberCoroutineScope()
    var suche by remember { mutableStateOf("") }
    var laedt by remember { mutableStateOf<String?>(null) }
    var fehler by remember { mutableStateOf<String?>(null) }
    val gefiltert = personen.filter { suche.isBlank() || it.name.contains(suche.trim(), ignoreCase = true) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Wer bist du?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { bereich.launch { abmelden() } }) { Text("Abmelden") }
            }
            Text("Die Wahl gilt bis zur Abmeldung und steht bei jeder Aktion im Protokoll. Steuern und den Standort senden darf, " +
                "wer im Einsatz als Einsatzleiter eingetragen ist. Der Führungsassistent steuert ohne Standort, alle anderen sehen die Lage nur an.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(suche, { suche = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            fehler?.let { Text(it, color = Color(0xFFC62828)) }
            LazyColumn(Modifier.weight(1f)) {
                items(gefiltert, key = { it.personId }) { person ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = laedt == null) {
                            laedt = person.personId
                            bereich.launch {
                                runCatching { waehlen(person) }.onFailure {
                                    fehler = (it as? ELAktionAbgelehnt)?.text ?: "Auswahl nicht gespeichert. Bitte erneut versuchen."
                                }
                                laedt = null
                            }
                        }.padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(person.name, style = MaterialTheme.typography.titleMedium)
                            val rollen = listOfNotNull(if (person.einsatzleiter) "Einsatzleiter" else null,
                                if (person.fuehrungsassistent) "Führungsassistent" else null)
                            if (rollen.isNotEmpty()) Text(rollen.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                        if (laedt == person.personId) CircularProgressIndicator()
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
