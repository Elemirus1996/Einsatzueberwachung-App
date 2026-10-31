package io.github.elemirus1996.einsatzspur.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.elemirus1996.einsatzspur.kern.ELKarte
import io.github.elemirus1996.einsatzspur.kern.ELKartenAnsicht
import io.github.elemirus1996.einsatzspur.kern.ELLage
import io.github.elemirus1996.einsatzspur.kern.ELPosition
import io.github.elemirus1996.einsatzspur.kern.Koordinate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Lagekarte der Einsatzleitung wie iOS `ELKarteView` (Karte in `/el`): Suchgebiete mit Abdeckung, abgeschlossene
 * gestrichelt, Laufwege je Team, Halsbänder, Team-Handys, Einsatzleitung und ELW. Positionen bewegen sich live.
 */
@Composable
fun ELLagekarteScreen(karte: ELKartenAnsicht, teams: List<ELLage.Team>, betrieb: io.github.elemirus1996.einsatzspur.kern.ELBetrieb, zurueck: () -> Unit) {
    val lebenszyklus = LocalLifecycleOwner.current
    val daten by karte.karte.collectAsState()
    val tracks by karte.tracks.collectAsState()
    var art by remember { mutableStateOf(Kartenart.TOPO) }
    var stil by remember { mutableStateOf<Style?>(null) }
    var eingepasst by remember { mutableStateOf(false) }
    // Zählt Kamerabewegungen, damit die Beschriftungen mitwandern
    var kamera by remember { mutableIntStateOf(0) }
    var gewaehlterPunkt by remember { mutableStateOf<ELKarte.Punkt?>(null) }
    val (ansicht, m) = rememberMapView()
    BackHandler(onBack = zurueck)

    // Abrufe nur, solange die Karte sichtbar ist
    DisposableEffect(lebenszyklus, karte) {
        val beobachter = LifecycleEventObserver { _, ereignis ->
            when (ereignis) {
                Lifecycle.Event.ON_START -> karte.starten()
                Lifecycle.Event.ON_STOP -> karte.anhalten()
                else -> Unit
            }
        }
        lebenszyklus.lifecycle.addObserver(beobachter)
        onDispose {
            lebenszyklus.lifecycle.removeObserver(beobachter)
            karte.anhalten()
        }
    }

    DisposableEffect(m) {
        val bewegt = MapLibreMap.OnCameraMoveListener { kamera++ }
        m?.addOnCameraMoveListener(bewegt)
        onDispose { m?.removeOnCameraMoveListener(bewegt) }
    }

    // Kartenpunkt antippen (Variante C): nächster Punkt im Umkreis von 40 px → an ein Team senden
    DisposableEffect(m, daten) {
        val k = m
        val tippen = MapLibreMap.OnMapClickListener { ort ->
            val tipp = k?.projection?.toScreenLocation(ort) ?: return@OnMapClickListener false
            val treffer = daten?.punkte.orEmpty().map { p -> p to k.projection.toScreenLocation(LatLng(p.lat, p.lng)) }
                .map { (p, s) -> p to kotlin.math.hypot((s.x - tipp.x).toDouble(), (s.y - tipp.y).toDouble()) }
                .filter { it.second <= 40 * ansicht.resources.displayMetrics.density }
                .minByOrNull { it.second }?.first
            if (treffer != null) gewaehlterPunkt = treffer
            treffer != null
        }
        k?.addOnMapClickListener(tippen)
        onDispose { k?.removeOnMapClickListener(tippen) }
    }

    LaunchedEffect(m, art) {
        val k = m ?: return@LaunchedEffect
        stil = null
        k.setStyle(Style.Builder().fromJson(stilJson(art))) { s ->
            listOf("gebiete", "gebiete-fertig", "fruehere", "mensch", "hund", "punkte", "positionen").forEach { s.addSource(GeoJsonSource(it)) }
            s.addLayer(FillLayer("gebiete-flaeche", "gebiete").withProperties(fillColor(get("farbe")), fillOpacity(0.15f)))
            s.addLayer(LineLayer("gebiete-rand", "gebiete").withProperties(lineColor(get("farbe")), lineWidth(2f)))
            // Abgeschlossene Gebiete blasser, gestrichelt
            s.addLayer(FillLayer("gebiete-fertig-flaeche", "gebiete-fertig").withProperties(fillColor(get("farbe")), fillOpacity(0.06f)))
            s.addLayer(LineLayer("gebiete-fertig-rand", "gebiete-fertig").withProperties(
                lineColor(get("farbe")), lineWidth(2f), lineOpacity(0.6f), lineDasharray(arrayOf(3f, 2f))))
            s.addLayer(LineLayer("fruehere", "fruehere").withProperties(lineColor(get("farbe")), lineWidth(2f), lineOpacity(0.4f)))
            s.addLayer(LineLayer("mensch", "mensch").withProperties(lineColor("#1F6FC4"), lineWidth(3f)))
            s.addLayer(LineLayer("hund", "hund").withProperties(lineColor("#C62828"), lineWidth(3f)))
            // Kartenpunkte als Quadrat-ähnliche Kreise mit dunklem Rand, von Positionen unterscheidbar
            s.addLayer(CircleLayer("punkte", "punkte").withProperties(
                circleColor(get("farbe")), circleRadius(7f), circleStrokeColor("#333333"), circleStrokeWidth(2f)))
            s.addLayer(CircleLayer("positionen", "positionen").withProperties(
                circleColor(get("farbe")), circleRadius(8f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2.5f)))
            stil = s
        }
    }

    LaunchedEffect(stil, daten, tracks) {
        val s = stil ?: return@LaunchedEffect
        val gebiete = daten?.suchgebiete.orEmpty().filter { it.punkte.size > 2 }
        quelle(s, "gebiete", gebiete.filter { !it.abgeschlossen }.map { merkmal(polygon(it.punkte), it.farbe ?: BLAU) })
        quelle(s, "gebiete-fertig", gebiete.filter { it.abgeschlossen }.map { merkmal(polygon(it.punkte), it.farbe ?: BLAU) })
        quelle(s, "fruehere", tracks?.abgeschlossen.orEmpty().flatMap { it.tracks }.filter { it.punkte.size > 1 }.map { t ->
            merkmal(linie(t.punkte.map { Koordinate(it.lat, it.lng) }), t.farbe ?: "#808080")
        })
        val laufend = tracks?.laufend.orEmpty()
        quelle(s, "mensch", laufend.filter { it.mensch.size > 1 }.map { l -> merkmal(linie(l.mensch.map { Koordinate(it.lat, it.lng) })) })
        quelle(s, "hund", laufend.filter { it.hund.size > 1 }.map { l -> merkmal(linie(l.hund.map { Koordinate(it.lat, it.lng) })) })
        quelle(s, "punkte", daten?.punkte.orEmpty().map { merkmal(punkt(Koordinate(it.lat, it.lng)), it.farbe ?: "#2196F3") })
        quelle(s, "positionen", daten?.positionen.orEmpty().map { merkmal(punkt(Koordinate(it.lat, it.lng)), farbe(it.art)) })

        // Beim ersten Mal auf Gebiete und Positionen einpassen, danach nicht mehr bewegen
        if (!eingepasst) {
            val alle = gebiete.flatMap { it.punkte } + daten?.positionen.orEmpty().map { Koordinate(it.lat, it.lng) }
            val k = m
            if (alle.isNotEmpty() && k != null) {
                eingepasst = true
                if (alle.size == 1) {
                    k.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(alle[0].lat, alle[0].lng), 15.0))
                } else {
                    val grenzen = LatLngBounds.Builder().apply { alle.forEach { include(LatLng(it.lat, it.lng)) } }.build()
                    k.moveCamera(CameraUpdateFactory.newLatLngBounds(grenzen, 80))
                }
                kamera++
            }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = zurueck) { Text("‹ Zurück") }
                Text("Lagekarte", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.fillMaxSize()) {
                AndroidView({ ansicht }, Modifier.matchParentSize())
                val k = m
                // Neu zeichnen, wenn die Karte bewegt wurde
                if (k != null && stil != null) key(kamera) {
                    daten?.suchgebiete.orEmpty().filter { it.punkte.size > 2 }.forEach { g ->
                        Beschriftung(k, mitte(g.punkte), gebietText(g), ueber = false)
                    }
                    daten?.positionen.orEmpty().forEach { p ->
                        beschriftung(p, teams).takeIf { it.isNotEmpty() }?.let { Beschriftung(k, Koordinate(p.lat, p.lng), it, ueber = true) }
                    }
                    daten?.punkte.orEmpty().forEach { p ->
                        p.name?.takeIf { it.isNotBlank() }?.let { Beschriftung(k, Koordinate(p.lat, p.lng), it, ueber = true) }
                    }
                }
                if (daten?.let { it.suchgebiete.isEmpty() && it.positionen.isEmpty() } == true) {
                    Text(
                        "Kein laufender Einsatz oder noch nichts auf der Karte.",
                        Modifier.align(Alignment.TopCenter).padding(12.dp)
                            .background(Color(0xE6FFFFFF), RoundedCornerShape(8.dp)).padding(8.dp),
                        color = Color.Black,
                    )
                }
                FilledTonalButton(
                    onClick = { art = if (art == Kartenart.TOPO) Kartenart.OSM else Kartenart.TOPO },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 40.dp),
                ) { Text(if (art == Kartenart.TOPO) Kartenart.OSM.titel else Kartenart.TOPO.titel) }
                gewaehlterPunkt?.let { PunktSendenDialog(it, teams, betrieb) { gewaehlterPunkt = null } }
                // Quellenangabe immer sichtbar (Nutzungsregeln OSM/OpenTopoMap)
                Text(
                    art.quelle, Modifier.align(Alignment.BottomStart).background(Color(0xCCFFFFFF)).padding(horizontal = 4.dp),
                    color = Color.Black, fontSize = 10.sp,
                )
            }
        }
    }
}

/** Kartenpunkt ansehen und als Navigationsziel an ein Team senden (`/teams/{teamId}/ziel`), wie iOS `ELPunktView`. */
@Composable
private fun PunktSendenDialog(punkt: ELKarte.Punkt, teams: List<ELLage.Team>, betrieb: io.github.elemirus1996.einsatzspur.kern.ELBetrieb, schliessen: () -> Unit) {
    val bereich = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = schliessen,
        title = { Text(punkt.name ?: "Punkt") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                punkt.beschreibung?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(String.format(java.util.Locale.ROOT, "%.6f, %.6f", punkt.lat, punkt.lng), style = MaterialTheme.typography.bodySmall)
                Text("An Team senden", fontWeight = FontWeight.Bold)
                if (teams.isEmpty()) Text("Noch keine Teams im Einsatz.")
                teams.forEach { team ->
                    TextButton(onClick = {
                        bereich.launch {
                            betrieb.ausfuehren(io.github.elemirus1996.einsatzspur.kern.ELAktion.ziel(team.teamId, team.teamName, punkt.id, punkt.name ?: "Punkt"))
                        }
                        schliessen()
                    }) { Text(team.teamName) }
                }
                Text("Das Team bekommt den Punkt als Ziel und startet damit seine Navigation.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = schliessen) { Text("Schließen") } },
    )
}

/** Beschriftung an einer Koordinate: über dem Marker bzw. mittig auf dem Gebiet. */
@Composable
private fun Beschriftung(karte: MapLibreMap, ort: Koordinate, text: String, ueber: Boolean) {
    val punkt = karte.projection.toScreenLocation(LatLng(ort.lat, ort.lng))
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black, textAlign = TextAlign.Center,
        modifier = Modifier
            .layout { messbar, grenzen ->
                val p = messbar.measure(grenzen.copy(minWidth = 0, minHeight = 0))
                layout(0, 0) {
                    val y = if (ueber) punkt.y - p.height - 12.dp.toPx() else punkt.y - p.height / 2f
                    p.place((punkt.x - p.width / 2f).toInt(), y.toInt())
                }
            }
            .background(Color(0xE6FFFFFF), RoundedCornerShape(6.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

private const val BLAU = "#1F6FC4"

/** Markerfarben wie iOS: Halsband rot, Handy blau, Einsatzleitung lila, ELW schwarz. */
internal fun farbe(art: String): String = when (art) {
    "halsband" -> "#C62828"
    "handy" -> BLAU
    "el" -> "#7B1FA2"
    "elw" -> "#000000"
    else -> "#808080"
}

internal fun gebietText(g: ELKarte.Suchgebiet): String {
    val name = listOfNotNull(if (g.abgeschlossen) "✓" else null, g.name).joinToString(" ")
    return listOfNotNull(name.takeIf { it.isNotEmpty() }, g.abdeckungProzent?.let { "${Math.round(it)} %" }).joinToString("\n")
}

internal fun beschriftung(p: ELPosition, teams: List<ELLage.Team>): String = when (p.art) {
    "el" -> "EL ${p.name ?: p.id.orEmpty()}"
    "elw" -> "ELW"
    else -> listOfNotNull(
        teams.firstOrNull { it.teamId == p.teamId }?.teamName,
        if (p.art == "halsband") p.name ?: p.id else null,
    ).joinToString(" · ")
}

private fun mitte(punkte: List<Koordinate>) = Koordinate(punkte.sumOf { it.lat } / punkte.size, punkte.sumOf { it.lng } / punkte.size)
