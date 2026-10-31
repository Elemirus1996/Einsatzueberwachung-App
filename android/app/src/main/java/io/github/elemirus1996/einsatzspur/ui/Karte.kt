package io.github.elemirus1996.einsatzspur.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.elemirus1996.einsatzspur.kern.Koordinate
import io.github.elemirus1996.einsatzspur.kern.TeamLage
import io.github.elemirus1996.einsatzspur.kern.TeamTracks
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
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
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.sources.GeoJsonSource

/** Kartenhintergrund wie auf dem Server: OpenStreetMap oder OpenTopoMap (Höhenlinien, für Wald und Gelände). */
enum class Kartenart(val titel: String, val kacheln: List<String>, val quelle: String) {
    OSM("Straße", listOf("https://tile.openstreetmap.org/{z}/{x}/{y}.png"), "© OpenStreetMap-Mitwirkende"),
    TOPO(
        "Gelände",
        listOf("a", "b", "c").map { "https://$it.tile.opentopomap.org/{z}/{x}/{y}.png" },
        "Kartendaten: © OpenStreetMap-Mitwirkende, SRTM · Darstellung: © OpenTopoMap (CC-BY-SA)",
    ),
}

/** MapLibre-Karte mit Suchgebiet, Laufwegen, Hund und eigener Position. */
@Composable
fun Karte(lage: TeamLage?, tracks: TeamTracks?, ich: Koordinate?, modifier: Modifier) {
    var art by remember { mutableStateOf(Kartenart.TOPO) }
    var stil by remember { mutableStateOf<Style?>(null) }
    var eingepasst by remember { mutableStateOf(false) }
    val (ansicht, karte) = rememberMapView()

    // Stil bei Wechsel der Kartenart neu setzen; Ebenen für die Lage darüber
    LaunchedEffect(karte, art) {
        val m = karte ?: return@LaunchedEffect
        stil = null
        m.setStyle(Style.Builder().fromJson(stilJson(art))) { s ->
            listOf("gebiet", "fruehere", "mensch", "hund", "hundpunkt", "ich").forEach { s.addSource(GeoJsonSource(it)) }
            s.addLayer(FillLayer("gebiet-flaeche", "gebiet").withProperties(fillColor(get("farbe")), fillOpacity(0.15f)))
            s.addLayer(LineLayer("gebiet-rand", "gebiet").withProperties(lineColor(get("farbe")), lineWidth(2.5f)))
            s.addLayer(LineLayer("fruehere", "fruehere").withProperties(lineColor(get("farbe")), lineWidth(2f), lineOpacity(0.45f)))
            s.addLayer(LineLayer("mensch", "mensch").withProperties(lineColor("#1F6FC4"), lineWidth(3.5f)))
            s.addLayer(LineLayer("hund", "hund").withProperties(lineColor("#C62828"), lineWidth(3.5f)))
            s.addLayer(CircleLayer("hundpunkt", "hundpunkt").withProperties(
                circleColor("#C62828"), circleRadius(8f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2.5f)))
            s.addLayer(CircleLayer("ich", "ich").withProperties(
                circleColor("#1F6FC4"), circleRadius(7f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2.5f)))
            stil = s
        }
    }

    // Daten in die Quellen schreiben
    LaunchedEffect(stil, lage, tracks, ich) {
        val s = stil ?: return@LaunchedEffect
        val gebiet = lage?.suchgebiet?.takeIf { it.punkte.size > 2 }
        quelle(s, "gebiet", gebiet?.let { g ->
            listOf(merkmal(polygon(g.punkte), g.farbe ?: "#1F6FC4"))
        })
        quelle(s, "fruehere", tracks?.abgeschlossen?.filter { it.punkte.size > 1 }?.map { t ->
            merkmal(linie(t.punkte.map { Koordinate(it.lat, it.lng) }), t.farbe ?: "#808080")
        })
        quelle(s, "mensch", tracks?.mensch?.takeIf { it.size > 1 }?.let { listOf(merkmal(linie(it.map { p -> Koordinate(p.lat, p.lng) }))) })
        quelle(s, "hund", tracks?.hund?.takeIf { it.size > 1 }?.let { listOf(merkmal(linie(it.map { p -> Koordinate(p.lat, p.lng) }))) })
        quelle(s, "hundpunkt", lage?.halsband?.let { listOf(merkmal(punkt(Koordinate(it.lat, it.lng)))) })
        quelle(s, "ich", ich?.let { listOf(merkmal(punkt(it))) })

        // Beim ersten Mal auf Gebiet bzw. Positionen einpassen, danach nicht mehr bewegen
        if (!eingepasst) {
            val alle = gebiet?.punkte.orEmpty() + listOfNotNull(lage?.halsband?.let { Koordinate(it.lat, it.lng) }, ich)
            if (alle.isNotEmpty()) {
                eingepasst = true
                val m = karte ?: return@LaunchedEffect
                if (alle.size == 1) {
                    m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(alle[0].lat, alle[0].lng), 15.0))
                } else {
                    val grenzen = LatLngBounds.Builder().apply { alle.forEach { include(LatLng(it.lat, it.lng)) } }.build()
                    m.moveCamera(CameraUpdateFactory.newLatLngBounds(grenzen, 80))
                }
            }
        }
    }

    Box(modifier) {
        AndroidView({ ansicht }, Modifier.matchParentSize())
        // Über der Quellenangabe, damit sie nicht verdeckt wird
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 40.dp), horizontalAlignment = Alignment.End) {
            lage?.halsband?.let { h ->
                FilledTonalButton(onClick = {
                    karte?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(h.lat, h.lng), 16.0))
                }) { Text("Hund") }
            }
            FilledTonalButton(onClick = { art = if (art == Kartenart.TOPO) Kartenart.OSM else Kartenart.TOPO }) {
                Text(if (art == Kartenart.TOPO) Kartenart.OSM.titel else Kartenart.TOPO.titel)
            }
        }
        // Quellenangabe immer sichtbar (Nutzungsregeln OSM/OpenTopoMap)
        Text(
            art.quelle, Modifier.align(Alignment.BottomStart).background(Color(0xCCFFFFFF)).padding(horizontal = 4.dp),
            color = Color.Black, fontSize = 10.sp,
        )
    }
}

/** MapView mit Lebenszyklus der Activity; die Karte ist da, sobald MapLibre sie geladen hat. */
@Composable
internal fun rememberMapView(): Pair<MapView, MapLibreMap?> {
    val context = LocalContext.current
    val lebenszyklus = LocalLifecycleOwner.current
    var karte by remember { mutableStateOf<MapLibreMap?>(null) }
    val ansicht = remember {
        MapView(context).apply {
            onCreate(null)
            getMapAsync { m ->
                m.uiSettings.isAttributionEnabled = false   // eigene, immer sichtbare Quellenangabe
                m.uiSettings.isLogoEnabled = false
                m.uiSettings.isRotateGesturesEnabled = false
                karte = m
            }
        }
    }

    DisposableEffect(lebenszyklus) {
        val beobachter = LifecycleEventObserver { _, ereignis ->
            when (ereignis) {
                Lifecycle.Event.ON_START -> ansicht.onStart()
                Lifecycle.Event.ON_RESUME -> ansicht.onResume()
                Lifecycle.Event.ON_PAUSE -> ansicht.onPause()
                Lifecycle.Event.ON_STOP -> ansicht.onStop()
                else -> Unit
            }
        }
        lebenszyklus.lifecycle.addObserver(beobachter)
        onDispose {
            lebenszyklus.lifecycle.removeObserver(beobachter)
            ansicht.onDestroy()
        }
    }
    return ansicht to karte
}

internal fun stilJson(art: Kartenart): String = JSONObject()
    .put("version", 8)
    .put("sources", JSONObject().put("hintergrund", JSONObject()
        .put("type", "raster")
        .put("tiles", JSONArray(art.kacheln))
        .put("tileSize", 256)
        .put("maxzoom", if (art == Kartenart.TOPO) 17 else 19)
        .put("attribution", art.quelle)))
    .put("layers", JSONArray().put(JSONObject().put("id", "hintergrund").put("type", "raster").put("source", "hintergrund")))
    .toString()

internal fun quelle(stil: Style, id: String, merkmale: List<JSONObject>?) {
    val sammlung = JSONObject().put("type", "FeatureCollection").put("features", JSONArray(merkmale.orEmpty()))
    stil.getSourceAs<GeoJsonSource>(id)?.setGeoJson(sammlung.toString())
}

internal fun merkmal(geometrie: JSONObject, farbe: String? = null) = JSONObject()
    .put("type", "Feature").put("geometry", geometrie)
    .put("properties", JSONObject().apply { farbe?.let { put("farbe", it) } })

internal fun koordinaten(punkte: List<Koordinate>) = JSONArray(punkte.map { JSONArray(listOf(it.lng, it.lat)) })

internal fun punkt(k: Koordinate) = JSONObject().put("type", "Point").put("coordinates", JSONArray(listOf(k.lng, k.lat)))
internal fun linie(p: List<Koordinate>) = JSONObject().put("type", "LineString").put("coordinates", koordinaten(p))
internal fun polygon(p: List<Koordinate>) = JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(koordinaten(p + p.first())))
