package io.github.elemirus1996.einsatzspur.ui

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.elemirus1996.einsatzspur.kern.ELAnmeldeFehler
import io.github.elemirus1996.einsatzspur.kern.ELAnsicht
import io.github.elemirus1996.einsatzspur.kern.ELBetrieb
import io.github.elemirus1996.einsatzspur.kern.ELKartenAnsicht
import io.github.elemirus1996.einsatzspur.kern.ELLage
import io.github.elemirus1996.einsatzspur.kern.ELVerlaengerung
import io.github.elemirus1996.einsatzspur.kern.ELVermissteAnsicht
import io.github.elemirus1996.einsatzspur.kern.ServerAdresse
import io.github.elemirus1996.einsatzspur.kern.TeamLage
import io.github.elemirus1996.einsatzspur.kern.TeamOrtung
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Anmeldung der Einsatzleitung: interne Adresse (nur im VPN erreichbar) und Staffel-Passwort, wie iOS `ELAnmeldungView`. */
@Composable
fun ELAnmeldungScreen(
    gespeicherteAdresse: String,
    hinweis: String?,
    anmelden: suspend (ServerAdresse, String, String) -> Unit,
    zurueck: () -> Unit,
) {
    val context = LocalContext.current
    val bereich = rememberCoroutineScope()
    var server by remember { mutableStateOf(gespeicherteAdresse) }
    var passwort by remember { mutableStateOf("") }
    var geraet by remember {
        mutableStateOf(Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL)
    }
    var laedt by remember { mutableStateOf(false) }
    var fehler by remember { mutableStateOf<String?>(null) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Einsatzleitung", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            hinweis?.let { Text(it, color = ORANGE) }
            OutlinedTextField(
                server, { server = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Interne Adresse, z. B. einsatz.intern") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            )
            OutlinedTextField(
                passwort, { passwort = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Staffel-Passwort") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            )
            OutlinedTextField(geraet, { geraet = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name dieses Geräts") })
            Text(
                "Die Einsatzleitung ist nur im WireGuard-VPN der Staffel erreichbar. Der Gerätename erscheint im Protokoll " +
                    "und als Marker „EL“ auf der Einsatzkarte.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = zurueck) { Text("Zurück") }
                Button(
                    enabled = server.isNotBlank() && passwort.isNotEmpty() && !laedt,
                    onClick = {
                        var text = server.trim()
                        if (!text.lowercase().startsWith("https://")) {
                            if (text.contains("://")) {
                                fehler = "Nur sichere Adressen (https) sind erlaubt."
                                return@Button
                            }
                            text = "https://$text"
                        }
                        val adresse = ServerAdresse.aus(text)
                        if (adresse == null) {
                            fehler = "Die Adresse ist ungültig."
                            return@Button
                        }
                        laedt = true
                        bereich.launch {
                            try {
                                anmelden(adresse, passwort, geraet)
                                passwort = ""
                            } catch (e: Exception) {
                                fehler = elFehlerText(e)
                            } finally {
                                laedt = false
                            }
                        }
                    },
                ) {
                    if (laedt) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Anmelden")
                }
            }
        }
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

private fun elFehlerText(e: Exception): String = when (e) {
    ELAnmeldeFehler.PasswortFalsch -> "Das Staffel-Passwort ist falsch."
    is ELAnmeldeFehler.Gesperrt -> "Zu viele Fehlversuche. Bitte in ${(e.sekunden / 60).coerceAtLeast(1)} Minuten erneut versuchen."
    ELAnmeldeFehler.KeinPasswort -> "Auf dem Server ist noch kein Staffel-Passwort eingerichtet (Einstellungen im Monitor)."
    ELAnmeldeFehler.NichtErreichbar ->
        "Server nicht erreichbar. Ist WireGuard eingeschaltet? Die Einsatzleitung geht nur über das VPN der Staffel."
    else -> "Der Server meldet einen Fehler. Bitte später erneut versuchen."
}

/**
 * Übersicht der Einsatzleitung (Nachbau von `/el`) wie iOS `ELUebersichtView`: Einsatz, Teams mit Suchzeit und
 * Warnstufe, Wetter. Telefon: eine Spalte; Tablet: Raster mit mehreren Teams nebeneinander.
 */
@Composable
fun ELUebersichtScreen(
    ansicht: ELAnsicht,
    ortung: TeamOrtung,
    betrieb: ELBetrieb,
    karte: ELKartenAnsicht?,
    vermisste: ELVermissteAnsicht?,
    verlaengerung: ELVerlaengerung?,
    abmelden: suspend () -> Unit,
) {
    var funkOffen by remember { mutableStateOf(false) }
    var karteOffen by remember { mutableStateOf(false) }
    var vermissteOffen by remember { mutableStateOf(false) }
    var gewaehltesTeam by remember { mutableStateOf<ELLage.Team?>(null) }
    val offen by betrieb.offen.collectAsState()
    val betriebHinweis by betrieb.hinweis.collectAsState()
    if (funkOffen) {
        ELFunkScreen(betrieb, ansicht.lage.collectAsState().value?.teams ?: emptyList(), zurueck = { funkOffen = false })
        return
    }
    if (vermissteOffen && vermisste != null) {
        ELVermissteScreen(vermisste, betrieb, zurueck = { vermissteOffen = false })
        return
    }
    if (karteOffen && karte != null) {
        ELLagekarteScreen(karte, ansicht.lage.collectAsState().value?.teams ?: emptyList(), zurueck = { karteOffen = false })
        return
    }
    val context = LocalContext.current
    val bereich = rememberCoroutineScope()
    val lage by ansicht.lage.collectAsState()
    val live by ansicht.live.collectAsState()
    val verbindungsfehler by ansicht.verbindungsfehler.collectAsState()
    val warnung by ansicht.warnung.collectAsState()
    val zustand by ortung.zustand.collectAsState()
    val ablaufHinweis = verlaengerung?.hinweis?.collectAsState()?.value
    var jetzt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var abmeldenFragen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            jetzt = System.currentTimeMillis()
            delay(1000)
        }
    }
    DisposableEffect(ansicht) {
        // Warnton und Vibration bei Eskalation
        ansicht.beiWarnung = { stufe ->
            runCatching {
                ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                    .startTone(if (stufe >= 2) ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK else ToneGenerator.TONE_PROP_BEEP2, 800)
            }
            runCatching {
                context.getSystemService(Vibrator::class.java)
                    ?.vibrate(VibrationEffect.createWaveform(if (stufe >= 2) longArrayOf(0, 400, 200, 400) else longArrayOf(0, 300), -1))
            }
        }
        onDispose { ansicht.beiWarnung = null }
    }

    Surface(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(320.dp),
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Einsatzleitung", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { abmeldenFragen = true }) { Text("Abmelden") }
                }
            }
            // Eigene Zeile, damit auf dem Telefon alles Platz hat
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (karte != null) OutlinedButton(onClick = { karteOffen = true }) { Text("Karte") }
                    if (vermisste != null) OutlinedButton(onClick = { vermissteOffen = true }) { Text("Vermisste") }
                    OutlinedButton(onClick = { funkOffen = true }) { Text("Funk & Notizen") }
                }
            }
            val wartet = offen.firstOrNull()
            val h = betriebHinweis
            if (wartet != null) {
                item(span = { GridItemSpan(maxLineSpan) }) { Text("„${wartet.beschreibung}“ wartet auf Netz", color = ORANGE) }
            } else if (h != null && jetzt - h.zeitMs < 6_000) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(h.text, color = if (h.fehler) Color.Red else Color(0xFF2E9D4F))
                }
            }
            ablaufHinweis?.let { text -> item(span = { GridItemSpan(maxLineSpan) }) { Text(text, color = ORANGE) } }
            warnung?.takeIf { jetzt - it.zeitMs < 30_000 }?.let { w ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        w.text, fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().background(
                            (if (w.stufe >= 2) Color.Red else ORANGE).copy(alpha = 0.2f), RoundedCornerShape(12.dp),
                        ).padding(16.dp),
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { EinsatzKopf(lage, verbindungsfehler) }
            val aktiv = lage?.takeIf { it.einsatz.aktiv }
            if (aktiv != null) {
                if (aktiv.teams.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("Noch keine Teams im Einsatz.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(aktiv.teams, key = { it.teamId }) { team ->
                    Box(Modifier.clickable { gewaehltesTeam = team }) { TeamKarte(team, ansicht, jetzt) }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (zustand == TeamOrtung.Zustand.ORTET) "EL-Standort wird gesendet" else "EL-Standort aus",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier.size(8.dp).background(
                            if (live) Color(0xFF2E9D4F) else if (verbindungsfehler) Color.Red else ORANGE, CircleShape,
                        ),
                    )
                }
            }
        }
    }
    gewaehltesTeam?.let { team -> ELTeamAktionenDialog(team, betrieb, schliessen = { gewaehltesTeam = null }) }
    if (abmeldenFragen) {
        AlertDialog(
            onDismissRequest = { abmeldenFragen = false },
            title = { Text("Als Einsatzleitung abmelden?") },
            text = { Text("Die Ortung der Einsatzleitung endet, das Token wird gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    abmeldenFragen = false
                    bereich.launch { abmelden() }
                }) { Text("Abmelden") }
            },
            dismissButton = { TextButton(onClick = { abmeldenFragen = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun EinsatzKopf(lage: ELLage?, verbindungsfehler: Boolean) {
    if (lage == null) {
        Text(
            if (verbindungsfehler) "Keine Verbindung. Ist WireGuard eingeschaltet?" else "Lade …",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val e = lage.einsatz
        if (e.aktiv) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(e.text ?: "Einsatz", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (e.uebung) {
                    Text(
                        "ÜBUNG", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(ORANGE, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            val zeile = listOfNotNull(
                e.szenario,
                e.beginnMs?.let { "seit " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) },
                e.einsatzleiter?.takeIf { it.isNotBlank() }?.let { "EL $it" },
            ).joinToString(" · ")
            if (zeile.isNotEmpty()) Text(zeile, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            lage.wetter?.let { w ->
                val text = listOfNotNull(
                    w.beschreibung, w.temperatur?.let { "%.0f °C".format(it) }, w.wind?.let { "Wind %.0f km/h".format(it) },
                ).joinToString(" · ")
                if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Text("Kein Einsatz aktiv", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Die Ortung der Einsatzleitung ist aus und startet, sobald ein Einsatz läuft.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Karte eines Teams in der Übersicht, wie iOS `ELTeamKarte`. */
@Composable
private fun TeamKarte(team: ELLage.Team, ansicht: ELAnsicht, jetzt: Long) {
    val hintergrund = when (team.warnstufe) {
        2 -> Color.Red.copy(alpha = 0.15f)
        1 -> ORANGE.copy(alpha = 0.15f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Column(
        Modifier.fillMaxWidth().background(hintergrund, RoundedCornerShape(14.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(team.teamName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            val (status, farbe) = when (team.suche.status) {
                TeamLage.SucheStatus.BEREIT -> "Bereit" to MaterialTheme.colorScheme.onSurfaceVariant
                TeamLage.SucheStatus.LAEUFT -> "Sucht" to Color(0xFF2E9D4F)
                TeamLage.SucheStatus.KURZPAUSE -> "Kurzpause" to ORANGE
                TeamLage.SucheStatus.BEENDET -> "Beendet" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(status, color = farbe, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
        val wer = listOfNotNull(team.dogName, team.hundefuehrerName).filter { it.isNotBlank() }.joinToString(" · ")
        if (wer.isNotEmpty()) Text(wer, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val dauer = ansicht.suchdauerSec(team, jetzt)
        if (dauer != null && team.suche.status != TeamLage.SucheStatus.BEREIT) {
            Text(
                "%d:%02d:%02d".format(dauer / 3600, dauer / 60 % 60, dauer % 60),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                color = when (team.warnstufe) {
                    2 -> Color.Red
                    1 -> ORANGE
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
        val geraete = listOfNotNull(
            team.suchgebiet?.name,
            team.halsband?.let { "Halsband " + geraetText(it, ansicht, jetzt) },
            team.handy?.let { "Handy " + geraetText(it, ansicht, jetzt) },
        ).joinToString(" · ")
        if (geraete.isNotEmpty()) {
            Text(geraete, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        team.pauseErforderlichBisMs?.takeIf { it > jetzt }?.let {
            Text("Pause bis " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)), color = ORANGE, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun geraetText(g: ELLage.Geraet, ansicht: ELAnsicht, jetzt: Long): String {
    val teile = mutableListOf<String>()
    ansicht.alterSec(g.zeitMs, jetzt)?.let { teile += if (it < 120) "$it s" else "${it / 60} min" }
    g.akku?.let { teile += "$it %" }
    return if (teile.isEmpty()) g.name ?: "–" else teile.joinToString(" ")
}

private val ORANGE = Color(0xFFF07A2A)

