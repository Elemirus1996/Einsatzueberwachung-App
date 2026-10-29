package io.github.elemirus1996.einsatzspur.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.Intent
import io.github.elemirus1996.einsatzspur.kern.GeraeteHinweis
import io.github.elemirus1996.einsatzspur.kern.Koordinate
import io.github.elemirus1996.einsatzspur.kern.Statusmeldung
import io.github.elemirus1996.einsatzspur.kern.TeamAnsicht
import io.github.elemirus1996.einsatzspur.kern.TeamLage
import io.github.elemirus1996.einsatzspur.kern.TeamOrtung
import io.github.elemirus1996.einsatzspur.kern.TeamSitzung
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.cos

/** Native Team-Ansicht (Nachbau von `/team`, wie iOS `TeamAnsichtView`). Karte vorerst als Lageskizze ohne Kacheln. */
@Composable
fun TeamAnsichtScreen(
    ortung: TeamOrtung,
    ansicht: TeamAnsicht,
    sitzung: TeamSitzung,
    hinweise: List<GeraeteHinweis>,
    einstellungOeffnen: (Intent) -> Unit,
    kannWechseln: Boolean,
    teamWechseln: suspend (trotzdem: Boolean) -> Boolean,
    vomEinsatzAbmelden: () -> Unit,
    schliessen: () -> Unit,
) {
    var ausgeblendet by remember { mutableStateOf(emptySet<String>()) }
    val zustand by ortung.zustand.collectAsState()
    val lage by ansicht.lage.collectAsState()
    val tracks by ansicht.tracks.collectAsState()
    val live by ansicht.live.collectAsState()
    val fehler by ansicht.verbindungsfehler.collectAsState()
    val offen by ansicht.offeneMeldungen.collectAsState()
    val gelesen by ansicht.gelesen.collectAsState()
    val gesendet by ansicht.gesendet.collectAsState()
    val ich by ortung.letztePosition.collectAsState()
    val bereich = rememberCoroutineScope()
    var info by remember { mutableStateOf(false) }
    var jetzt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            jetzt = System.currentTimeMillis()
            delay(1000)
        }
    }

    if (zustand == TeamOrtung.Zustand.BEENDET) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Einsatz beendet", style = MaterialTheme.typography.headlineMedium)
                Text("Die Ortung ist aus. Die App fragt keinen Standort mehr ab.", Modifier.padding(16.dp))
                Button(onClick = schliessen) { Text("Zurück zum Start") }
            }
        }
        return
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            // Kopf
            Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(lage?.dogName ?: "(ohne Hund)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        if (lage?.uebung == true) {
                            Text(
                                "ÜBUNG", Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background(Color(0xFFF07A2A))
                                    .padding(horizontal = 6.dp), color = Color.White, style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Text(lage?.einsatzText ?: sitzung.teamName, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(sucheText(lage?.suche?.status, fehler), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    ansicht.suchdauerSec(jetzt)?.let { Text(dauer(it), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleLarge) }
                }
                TextButton(onClick = { info = true }) { Text("Info") }
            }

            // Lageskizze und Mitteilungen
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Lageskizze(lage, tracks, ich?.let { Koordinate(it.lat, it.lng) }, Modifier.fillMaxSize())
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    hinweise.filter { it.titel !in ausgeblendet }.forEach { h ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Text(h.titel, fontWeight = FontWeight.Bold, color = Color(0xFFD9530F))
                                Text(h.text, style = MaterialTheme.typography.bodySmall)
                                Row {
                                    TextButton(onClick = { einstellungOeffnen(h.einstellung) }) { Text("Einstellung öffnen") }
                                    TextButton(onClick = { ausgeblendet = ausgeblendet + h.titel }) { Text("Später") }
                                }
                            }
                        }
                    }
                    (lage?.mitteilungen ?: emptyList()).filter { it.id !in gelesen }.takeLast(3).forEach { m ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(m.text, fontWeight = FontWeight.Bold)
                                    Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.zeitMs)), style = MaterialTheme.typography.bodySmall)
                                }
                                Button(onClick = { ansicht.alsGelesen(m) }) { Text("OK") }
                            }
                        }
                    }
                }
            }

            // Statusknöpfe
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Statusmeldung.entries.forEach { s ->
                    OutlinedButton(
                        onClick = { bereich.launch { ansicht.melden(s) } },
                        modifier = Modifier.weight(1f),
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                    ) { Text(s.titel, maxLines = 1) }
                }
            }
            val hinweis = when {
                offen.isNotEmpty() -> if (offen.size == 1) "1 Meldung wartet auf Netz" else "${offen.size} Meldungen warten auf Netz"
                gesendet != null && jetzt - gesendet!!.second < 4000 -> "„${gesendet!!.first.titel}“ gesendet"
                else -> ""
            }
            Text(hinweis, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
                color = if (offen.isNotEmpty()) Color(0xFFD9530F) else Color(0xFF2E8540))

            // Fußzeile
            Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ansicht.halsbandAlterMs(jetzt)?.let { gpsText(it) } ?: "kein GPS", style = MaterialTheme.typography.bodySmall)
                Text(
                    when (zustand) {
                        TeamOrtung.Zustand.ORTET -> "Handy ortet"
                        TeamOrtung.Zustand.PAUSIERT -> "wartet auf neue Suche"
                        else -> "verbinde …"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                entfernung(lage?.suchgebiet?.punkte, ich?.let { Koordinate(it.lat, it.lng) })?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (live) Color(0xFF2E8540) else if (fehler) Color.Red else Color(0xFFF07A2A)))
            }
        }
    }

    if (info) {
        // Rückfrage: null = keine, "abmelden" = vom Einsatz abmelden, "puffer" = trotzdem wechseln
        var rueckfrage by remember { mutableStateOf<String?>(null) }
        fun wechseln(trotzdem: Boolean) = bereich.launch {
            if (teamWechseln(trotzdem)) info = false else rueckfrage = "puffer"
        }
        AlertDialog(
            onDismissRequest = { info = false },
            title = { Text(lage?.teamName ?: sitzung.teamName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    lage?.hundefuehrerName?.let { Text("Hundeführer: $it") }
                    lage?.helfer?.takeIf { it.isNotEmpty() }?.let { Text("Helfer: ${it.joinToString()}") }
                    lage?.suchgebiet?.name?.let { Text("Suchgebiet: $it") }
                    lage?.staffel?.let { Text("Staffel: $it") }
                    Text("Server: ${sitzung.server.name}")
                    when (rueckfrage) {
                        "abmelden" -> Text(
                            "Vom Einsatz abmelden? Die Ortung endet und der Anmelde-Code wird gelöscht.",
                            fontWeight = FontWeight.Bold,
                        )
                        "puffer" -> Text(
                            "${ortung.gepuffert.value} Positionen sind noch nicht gesendet (kein Netz). Beim Wechsel gehen sie verloren.",
                            fontWeight = FontWeight.Bold,
                        )
                        else -> {
                            if (kannWechseln) {
                                Button(onClick = { wechseln(false) }, Modifier.fillMaxWidth()) { Text("Team wechseln") }
                                Text("Zeigt die Teams desselben Einsatzes, ohne neuen Code.", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { rueckfrage = "abmelden" }) {
                                Text("Vom Einsatz abmelden", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                when (rueckfrage) {
                    "abmelden" -> TextButton(onClick = { info = false; vomEinsatzAbmelden() }) { Text("Abmelden") }
                    "puffer" -> TextButton(onClick = { wechseln(true) }) { Text("Trotzdem wechseln") }
                    else -> Unit
                }
            },
            dismissButton = {
                TextButton(onClick = { if (rueckfrage != null) rueckfrage = null else info = false }) {
                    Text(if (rueckfrage != null) "Abbrechen" else "Schließen")
                }
            },
        )
    }
}

/**
 * Lageskizze ohne Kartenkacheln: Suchgebiet, Laufwege, Hund und eigene Position, maßstabsgetreu (Norden oben).
 * Die Entscheidung für eine Kartenbibliothek unter Android steht noch aus.
 */
@Composable
private fun Lageskizze(lage: TeamLage?, tracks: io.github.elemirus1996.einsatzspur.kern.TeamTracks?, ich: Koordinate?, modifier: Modifier) {
    val gebiet = lage?.suchgebiet?.punkte.orEmpty()
    val hund = lage?.halsband?.let { Koordinate(it.lat, it.lng) }
    val alle = gebiet + listOfNotNull(hund, ich) +
        (tracks?.hund.orEmpty() + tracks?.mensch.orEmpty()).map { Koordinate(it.lat, it.lng) }
    val gebietFarbe = farbe(lage?.suchgebiet?.farbe) ?: Color(0xFF1F6FC4)
    Canvas(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (alle.isEmpty()) return@Canvas
        val projektion = Projektion(alle, size.width, size.height, 32f)
        if (gebiet.size > 2) {
            val pfad = Path().apply {
                gebiet.forEachIndexed { i, k -> projektion(k).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
                close()
            }
            drawPath(pfad, gebietFarbe.copy(alpha = 0.15f))
            drawPath(pfad, gebietFarbe, style = Stroke(4f))
        }
        tracks?.abgeschlossen?.forEach { t -> linie(t.punkte.map { Koordinate(it.lat, it.lng) }, projektion, (farbe(t.farbe) ?: Color.Gray).copy(alpha = 0.45f), 3f) }
        linie(tracks?.mensch.orEmpty().map { Koordinate(it.lat, it.lng) }, projektion, Color(0xFF1F6FC4), 6f)
        linie(tracks?.hund.orEmpty().map { Koordinate(it.lat, it.lng) }, projektion, Color(0xFFC62828), 6f)
        hund?.let { drawCircle(Color(0xFFC62828), 14f, projektion(it)); drawCircle(Color.White, 14f, projektion(it), style = Stroke(4f)) }
        ich?.let { drawCircle(Color(0xFF1F6FC4), 11f, projektion(it)); drawCircle(Color.White, 11f, projektion(it), style = Stroke(4f)) }
    }
}

private fun DrawScope.linie(punkte: List<Koordinate>, projektion: Projektion, farbe: Color, breite: Float) {
    if (punkte.size < 2) return
    val pfad = Path()
    punkte.forEachIndexed { i, k -> projektion(k).let { if (i == 0) pfad.moveTo(it.x, it.y) else pfad.lineTo(it.x, it.y) } }
    drawPath(pfad, farbe, style = Stroke(breite))
}

/** Einfache flächentreue Näherung für kleine Gebiete: Längengrade mit cos(Breite) gestaucht. */
private class Projektion(punkte: List<Koordinate>, breite: Float, hoehe: Float, rand: Float) {
    private val minLat = punkte.minOf { it.lat }
    private val maxLat = punkte.maxOf { it.lat }
    private val minLng = punkte.minOf { it.lng }
    private val maxLng = punkte.maxOf { it.lng }
    private val stauchung = cos(Math.toRadians((minLat + maxLat) / 2))
    private val spanneX = ((maxLng - minLng) * stauchung).coerceAtLeast(0.0005)
    private val spanneY = (maxLat - minLat).coerceAtLeast(0.0005)
    private val massstab = minOf((breite - 2 * rand) / spanneX, (hoehe - 2 * rand) / spanneY)
    private val versatzX = (breite - spanneX * massstab) / 2
    private val versatzY = (hoehe - spanneY * massstab) / 2

    operator fun invoke(k: Koordinate) = Offset(
        (versatzX + (k.lng - minLng) * stauchung * massstab).toFloat(),
        (versatzY + (maxLat - k.lat) * massstab).toFloat(),
    )
}

private fun farbe(hex: String?): Color? =
    hex?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }

private fun sucheText(status: TeamLage.SucheStatus?, fehler: Boolean) = when (status) {
    TeamLage.SucheStatus.LAEUFT -> "Suche läuft"
    TeamLage.SucheStatus.KURZPAUSE -> "Kurzpause"
    TeamLage.SucheStatus.BEENDET -> "Suche beendet"
    TeamLage.SucheStatus.BEREIT -> "Bereit"
    null -> if (fehler) "Keine Verbindung" else "Lade …"
}

private fun dauer(sekunden: Long) = "%d:%02d:%02d".format(sekunden / 3600, (sekunden % 3600) / 60, sekunden % 60)

private fun gpsText(alterMs: Long) = (alterMs / 1000).let { if (it < 120) "GPS $it s" else "GPS ${it / 60} min" }

private fun entfernung(gebiet: List<Koordinate>?, ich: Koordinate?): String? {
    if (gebiet == null || gebiet.size < 3 || ich == null) return null
    val mitte = FloatArray(1)
    android.location.Location.distanceBetween(ich.lat, ich.lng, gebiet.map { it.lat }.average(), gebiet.map { it.lng }.average(), mitte)
    return if (mitte[0] < 1000) "${mitte[0].toInt()} m zum Gebiet" else "%.1f km zum Gebiet".format(mitte[0] / 1000)
}
