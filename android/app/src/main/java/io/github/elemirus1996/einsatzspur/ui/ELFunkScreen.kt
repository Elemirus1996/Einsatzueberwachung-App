package io.github.elemirus1996.einsatzspur.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import io.github.elemirus1996.einsatzspur.kern.ELAktion
import io.github.elemirus1996.einsatzspur.kern.ELBetrieb
import io.github.elemirus1996.einsatzspur.kern.ELLage
import io.github.elemirus1996.einsatzspur.kern.ELNotiz
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Funk und Notizen als Chat wie iOS `ELFunkView` (Gestaltung Punkt 9): Teams links, Einsatzleitung rechts in Staffelrot,
 * Status mittig, Notizen gelb. Neueste unten, Eingabe mit Team-Chips unten. Antworten und Löschen über langes Drücken.
 * [zurueck] null: eingebettet (Bereich oder Tablet-Leitstand), dann ohne Zurück-Geste.
 */
@Composable
fun ELFunkScreen(betrieb: ELBetrieb, teams: List<ELLage.Team>, lage: ELLage? = null, mitKopf: Boolean = true, zurueck: (() -> Unit)? = null) {
    val bereich = rememberCoroutineScope()
    val notizen by betrieb.notizen.collectAsState()
    val weitere by betrieb.weitereNotizen.collectAsState()
    var text by remember { mutableStateOf("") }
    var istFunk by remember { mutableStateOf(true) }
    var teamId by remember { mutableStateOf<String?>(null) }
    var antwortAuf by remember { mutableStateOf<ELNotiz?>(null) }
    val liste = rememberLazyListState()
    zurueck?.let { BackHandler(onBack = it) }
    LaunchedEffect(Unit) { betrieb.notizenLaden() }
    // Neuester Eintrag unten im Blick
    LaunchedEffect(notizen.firstOrNull()?.id) { if (notizen.isNotEmpty()) liste.scrollToItem(notizen.size) }

    Column(Modifier.fillMaxSize().background(ELStil.papier).imePadding()) {
        if (mitKopf) ELReiterKopf("Funk", lage)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = liste,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                if (weitere && notizen.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { bereich.launch { betrieb.aeltereNotizenLaden() } }) { Text("Ältere laden") }
                    }
                }
            }
            items(notizen.reversed(), key = { it.id }) { n ->
                Eintrag(n, teams,
                    antworten = if (n.art == "funk") ({ antwortAuf = n; istFunk = true }) else null,
                    loeschen = if (n.loeschbar) ({ bereich.launch { betrieb.ausfuehren(ELAktion.notizLoeschen(n.id)); betrieb.notizenLaden() } }) else null)
            }
        }
        // Eingabe
        Column(Modifier.fillMaxWidth().background(ELStil.platte).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val antwort = antwortAuf
            if (antwort != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("↩ Antwort auf: ${antwort.text}", maxLines = 1, style = MaterialTheme.typography.bodySmall,
                        color = ELStil.vermerk, modifier = Modifier.weight(1f))
                    TextButton(onClick = { antwortAuf = null }) { Text("✕") }
                }
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChatChip("Alle", teamId == null) { teamId = null }
                    teams.forEach { t -> ChatChip(t.teamName, teamId == t.teamId) { teamId = t.teamId } }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { istFunk = !istFunk; if (!istFunk) antwortAuf = null },
                    modifier = Modifier.background(ELStil.platteTief, CircleShape),
                ) { Text(if (istFunk) "Funk" else "Notiz", color = if (istFunk) ELStil.aktion else ELStil.warnung1, fontWeight = FontWeight.SemiBold) }
                TextField(
                    text, { text = it }, Modifier.weight(1f), placeholder = { Text(if (istFunk) "Funkspruch" else "Notiz") },
                    maxLines = 4, shape = RoundedCornerShape(20.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = ELStil.platteTief, unfocusedContainerColor = ELStil.platteTief,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                IconButton(
                    enabled = text.isNotBlank(),
                    onClick = {
                        val inhalt = text.trim()
                        text = ""
                        val a = if (istFunk) ELAktion.funk(inhalt, antwort?.teamId ?: teamId, antwort?.id) else ELAktion.notiz(inhalt, teamId)
                        antwortAuf = null
                        bereich.launch {
                            betrieb.ausfuehren(a)
                            betrieb.notizenLaden()
                        }
                    },
                    modifier = Modifier.size(44.dp).background(if (text.isBlank()) ELStil.vermerk else ELStil.aktion, CircleShape),
                ) { Text("➤", color = Color.White) }
            }
        }
    }
}

/** Eigene Einträge stehen rechts: von der Einsatzleitung oder löschbar (nur EL-Notizen sind löschbar), wie iOS */
internal fun vonLeitung(n: ELNotiz): Boolean = n.loeschbar || (n.von ?: "").contains("einsatzleitung", ignoreCase = true)

private fun uhrzeit(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Eintrag(n: ELNotiz, teams: List<ELLage.Team>, antworten: (() -> Unit)?, loeschen: (() -> Unit)?) {
    var menue by remember { mutableStateOf(false) }
    val druck = Modifier.combinedClickable(onClick = {}, onLongClick = { if (antworten != null || loeschen != null) menue = true })
    when (n.art) {
        "status", "system" -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Statusmeldungen der Teams bringen den Teamnamen schon mit („[Team Bravo] …“), dann kein zweites Mal davor
            val vorsatz = n.von?.takeUnless { n.text.startsWith("[$it]") }?.let { "$it: " } ?: ""
            Text("$vorsatz${n.text} · ${uhrzeit(n.zeitMs)}",
                style = MaterialTheme.typography.bodySmall, color = ELStil.vermerk,
                modifier = Modifier.background(ELStil.platteTief, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp))
        }
        "funk" -> {
            val eigene = vonLeitung(n)
            val team = teams.firstOrNull { it.teamId == n.teamId }?.teamName
            val absender = when {
                eigene && team != null -> "An $team"
                !eigene && team != null -> team
                else -> n.von ?: "Funk"
            }
            Row(Modifier.fillMaxWidth()) {
                if (eigene) Spacer(Modifier.weight(1f))
                Box {
                    Column(
                        druck.widthIn(max = 300.dp).background(
                            if (eigene) ELStil.aktion.copy(alpha = 0.13f) else ELStil.platte,
                            RoundedCornerShape(14.dp, 14.dp, if (eigene) 4.dp else 14.dp, if (eigene) 14.dp else 4.dp),
                        ).padding(10.dp),
                    ) {
                        Text("$absender · ${uhrzeit(n.zeitMs)}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                            color = if (eigene) ELStil.aktion else ELStil.vermerk)
                        Text(n.text, color = ELStil.inschrift)
                        n.antworten.forEach { Text("↳ ${it.text}", style = MaterialTheme.typography.bodyMedium, color = ELStil.vermerk) }
                    }
                    Menue(menue, { menue = false }, antworten, loeschen)
                }
                if (!eigene) Spacer(Modifier.weight(1f))
            }
        }
        else -> Box {
            Column(druck.fillMaxWidth().background(ELStil.warnung1.copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(10.dp)) {
                Text("Notiz · ${uhrzeit(n.zeitMs)}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = ELStil.warnung1)
                Text(n.text, color = ELStil.inschrift)
            }
            Menue(menue, { menue = false }, antworten, loeschen)
        }
    }
}

@Composable
private fun Menue(offen: Boolean, schliessen: () -> Unit, antworten: (() -> Unit)?, loeschen: (() -> Unit)?) {
    DropdownMenu(expanded = offen, onDismissRequest = schliessen) {
        antworten?.let { DropdownMenuItem(text = { Text("Antworten") }, onClick = { schliessen(); it() }) }
        loeschen?.let { DropdownMenuItem(text = { Text("Löschen", color = MaterialTheme.colorScheme.error) }, onClick = { schliessen(); it() }) }
    }
}

@Composable
private fun ChatChip(titel: String, gewaehlt: Boolean, aktion: () -> Unit) {
    TextButton(
        onClick = aktion,
        modifier = Modifier.background(if (gewaehlt) ELStil.aktion else ELStil.platteTief, CircleShape),
    ) { Text(titel, color = if (gewaehlt) Color.White else ELStil.inschrift) }
}
