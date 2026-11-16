package io.github.elemirus1996.einsatzspur.ui

import io.github.elemirus1996.einsatzspur.kern.ELAktion
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBar
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.elemirus1996.einsatzspur.kern.ELAnmeldeFehler
import io.github.elemirus1996.einsatzspur.kern.ELAnsicht
import io.github.elemirus1996.einsatzspur.kern.ELBerichtFehler
import io.github.elemirus1996.einsatzspur.kern.ELQRCode
import io.github.elemirus1996.einsatzspur.kern.TeamQRCode
import io.github.elemirus1996.einsatzspur.kern.ELBetrieb
import io.github.elemirus1996.einsatzspur.kern.ELKartenAnsicht
import io.github.elemirus1996.einsatzspur.kern.ELLage
import io.github.elemirus1996.einsatzspur.kern.ELVerlaengerung
import io.github.elemirus1996.einsatzspur.kern.ELSzenario
import io.github.elemirus1996.einsatzspur.kern.ELVermissteAnsicht
import io.github.elemirus1996.einsatzspur.kern.HalsbandAkku
import io.github.elemirus1996.einsatzspur.kern.OrtungsHinweise
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
    var scannerOffen by remember { mutableStateOf(false) }
    // Server aus dem QR-Code der Einstellungen (Idee 6.3), zur Bestätigung angezeigt
    var ausQR by remember { mutableStateOf<String?>(null) }

    if (scannerOffen) {
        QrScanner(gefunden = { text ->
            val qr = ELQRCode.aus(text)
            when {
                // Code eines älteren Servers, über die IP-Adresse geöffnet
                qr != null && qr.server.istIpAdresse -> "Der Code enthält eine IP-Adresse. Die Einstellungen am Server über den Namen öffnen und neu scannen."
                qr != null -> { scannerOffen = false; server = qr.server.name; ausQR = qr.server.name; null }
                TeamQRCode.aus(text) != null -> "Das ist ein Team-Code. Für die Einsatzleitung den Code aus den Einstellungen scannen."
                else -> "Das ist kein QR-Code für die Einsatzleitung."
            }
        }, abbrechen = { scannerOffen = false })
        return
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Einsatzleitung", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            hinweis?.let { Text(it, color = ORANGE) }
            OutlinedButton(onClick = { scannerOffen = true }, Modifier.fillMaxWidth(), enabled = !laedt) {
                Text("QR-Code aus den Einstellungen scannen")
            }
            ausQR?.let { Text("Server aus dem QR-Code: $it", color = Color(0xFF2E9D4F)) }
            Text(
                "Der Code steht im Monitor unter Einstellungen → System → „EL-App anmelden“. Er enthält nur die Adresse, " +
                    "das Passwort gibt man hier ein.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                server, { server = it; if (it != ausQR) ausQR = null }, Modifier.fillMaxWidth(), singleLine = true,
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
                        if (adresse.istIpAdresse) {
                            fehler = ServerAdresse.IP_ADRESSE_HINWEIS
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

/** PDF über den FileProvider an eine andere App geben: ansehen oder teilen (Mail, Messenger, Dateien) */
private fun berichtStarten(context: android.content.Context, datei: java.io.File, teilen: Boolean) {
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.dateien", datei)
    val absicht = if (teilen) {
        android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
    } else {
        android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
    }
    absicht.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(android.content.Intent.createChooser(absicht, if (teilen) "Bericht teilen" else "Bericht öffnen"))
    } catch (e: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(context, "Keine App für PDF gefunden.", android.widget.Toast.LENGTH_LONG).show()
    }
}

private fun elFehlerText(e: Exception): String =
    (e as? ELAnmeldeFehler)?.meldung ?: "Der Server meldet einen Fehler. Bitte später erneut versuchen."

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
    toene: io.github.elemirus1996.einsatzspur.kern.Toene,
    abmelden: suspend () -> Unit,
) {
    // Bereiche wie die Reiter unter iOS: Lage, Karte, Funk, Vermisst
    var bereichWahl by remember { mutableStateOf(ELBereich.LAGE) }
    var startOffen by remember { mutableStateOf(false) }
    var beendenOffen by remember { mutableStateOf(false) }
    var gewaehltesTeam by remember { mutableStateOf<ELLage.Team?>(null) }
    // Bericht nach dem Beenden (Idee 6.2): liegt nur im Cache, bis „Fertig“
    val context = LocalContext.current
    val berichtOrdner = remember { java.io.File(context.cacheDir, "bericht") }
    val berichtBereich = rememberCoroutineScope()
    var berichtDatei by remember { mutableStateOf<java.io.File?>(null) }
    var berichtFehler by remember { mutableStateOf<String?>(null) }
    var berichtLaedt by remember { mutableStateOf(false) }
    val offen by betrieb.offen.collectAsState()
    val betriebHinweis by betrieb.hinweis.collectAsState()
    if (startOffen) {
        ELEinsatzStartScreen(betrieb, ortung.letztePosition.collectAsState().value, schliessen = { startOffen = false })
        return
    }
    val beendenLage = ansicht.lage.collectAsState().value
    if (beendenOffen && beendenLage != null) {
        ELEinsatzBeendenScreen(betrieb, beendenLage, schliessen = { beendenOffen = false })
        return
    }
    val bereich = rememberCoroutineScope()
    val lage by ansicht.lage.collectAsState()
    val live by ansicht.live.collectAsState()
    val verbindungsfehler by ansicht.verbindungsfehler.collectAsState()
    val warnung by ansicht.warnung.collectAsState()
    val teamPunkt by ansicht.teamPunkt.collectAsState()
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
        // Warnton je Einstellung; Stufe 2 lässt sich nicht abschalten
        ansicht.beiWarnung = { stufe ->
            toene.spielen(if (stufe >= 2) io.github.elemirus1996.einsatzspur.kern.Tonart.WARNUNG2 else io.github.elemirus1996.einsatzspur.kern.Tonart.WARNUNG1)
        }
        onDispose { ansicht.beiWarnung = null }
    }

    // Tablet quer (ab 840 dp): Leiste links, Lage als Leitstand mit Teams, Karte und Funk; Telefon: Leiste unten
    val breit = LocalConfiguration.current.screenWidthDp >= 840
    val teams = lage?.teams.orEmpty()
    if (bereichWahl != ELBereich.LAGE) BackHandler { bereichWahl = ELBereich.LAGE }
    Row(Modifier.fillMaxSize().background(ELStil.papier)) {
        if (breit) {
            NavigationRail(containerColor = ELStil.platte, modifier = Modifier.safeDrawingPadding()) {
                ELBereich.entries.forEach { b ->
                    NavigationRailItem(
                        selected = bereichWahl == b, onClick = { bereichWahl = b },
                        icon = { Icon(painterResource(b.symbol), contentDescription = null) }, label = { Text(b.titel) },
                        colors = NavigationRailItemDefaults.colors(selectedIconColor = ELStil.aktion, selectedTextColor = ELStil.aktion),
                    )
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (bereichWahl) {
                    ELBereich.KARTE -> if (karte != null) ELLagekarteScreen(karte, teams, betrieb, lage)
                    ELBereich.FUNK -> ELFunkScreen(betrieb, teams, lage)
                    ELBereich.VERMISST -> if (vermisste != null) ELVermissteScreen(vermisste, betrieb, lage)
                    ELBereich.LAGE -> Column(Modifier.fillMaxSize()) {
                        EinsatzKopf(lage, verbindungsfehler, jetzt,
                            starten = { startOffen = true }, beenden = { beendenOffen = true }, abmelden = { abmeldenFragen = true })
                        val aktiv = lage?.takeIf { it.einsatz.aktiv }
                        if (aktiv != null) ELStatusChips(aktiv.teams, live)
                        val teamListe: @Composable (Modifier, Boolean) -> Unit = { mod, eineSpalte ->
                            LazyVerticalGrid(
                                columns = if (eineSpalte) GridCells.Fixed(1) else GridCells.Adaptive(320.dp),
                                modifier = mod,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                val wartet = offen.firstOrNull()
                                val h = betriebHinweis
                                if (wartet != null) {
                                    item(span = { GridItemSpan(maxLineSpan) }) { Text("„${wartet.beschreibung}“ wartet auf Netz", color = ELStil.warnung1) }
                                } else if (h != null && jetzt - h.zeitMs < 6_000) {
                                    item(span = { GridItemSpan(maxLineSpan) }) { Text(h.text, color = if (h.fehler) ELStil.warnung2 else ELStil.aktiv) }
                                }
                                ablaufHinweis?.let { text -> item(span = { GridItemSpan(maxLineSpan) }) { Text(text, color = ELStil.warnung1) } }
                                warnung?.takeIf { jetzt - it.zeitMs < 30_000 }?.let { w ->
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        Text(w.text, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth()
                                            .background((if (w.stufe >= 2) ELStil.warnung2 else ELStil.warnung1).copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                                            .padding(16.dp))
                                    }
                                }
                                teamPunkt?.takeIf { jetzt - it.second < 30_000 }?.let { (text, _) ->
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        Text(text, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth()
                                            .background(ELStil.warnung1.copy(alpha = 0.18f), RoundedCornerShape(12.dp)).padding(16.dp))
                                    }
                                }
                                if (aktiv != null) {
                                    if (aktiv.teams.isEmpty()) {
                                        item(span = { GridItemSpan(maxLineSpan) }) { Text("Noch keine Teams im Einsatz.", color = ELStil.vermerk) }
                                    }
                                    items(aktiv.teams, key = { it.teamId }) { team ->
                                        TeamKarte(team, ansicht, jetzt, details = { gewaehltesTeam = team }) { a -> bereich.launch { betrieb.ausfuehren(a) } }
                                    }
                                }
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Text(
                                        if (zustand == TeamOrtung.Zustand.ORTET) "EL-Standort wird gesendet" else "EL-Standort aus",
                                        style = MaterialTheme.typography.bodySmall, color = ELStil.vermerk,
                                    )
                                }
                            }
                        }
                        if (breit && aktiv != null && karte != null) {
                            // Leitstand: Teams | Karte | Funk
                            Row(Modifier.fillMaxSize()) {
                                teamListe(Modifier.width(340.dp).fillMaxHeight(), true)
                                Box(Modifier.weight(1f).fillMaxHeight().padding(vertical = 10.dp).clip(RoundedCornerShape(ELStil.radius))) {
                                    ELLagekarteScreen(karte, aktiv.teams, betrieb, aktiv, mitKopf = false)
                                }
                                Box(Modifier.width(330.dp).fillMaxHeight().padding(10.dp).clip(RoundedCornerShape(ELStil.radius))) {
                                    ELFunkScreen(betrieb, aktiv.teams, aktiv, mitKopf = false)
                                }
                            }
                        } else {
                            teamListe(Modifier.fillMaxSize(), false)
                        }
                    }
                }
            }
            if (!breit) {
                NavigationBar(containerColor = ELStil.platte) {
                    ELBereich.entries.forEach { b ->
                        NavigationBarItem(
                            selected = bereichWahl == b, onClick = { bereichWahl = b },
                            icon = { Icon(painterResource(b.symbol), contentDescription = null) }, label = { Text(b.titel) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = ELStil.aktion, selectedTextColor = ELStil.aktion,
                                indicatorColor = ELStil.aktion.copy(alpha = 0.12f)),
                        )
                    }
                }
            }
        }
    }
    betrieb.einsatzEnde.collectAsState().value?.let { ende ->
        val mitBericht = ende.pdfErstellt == true && ende.berichtPfad != null
        AlertDialog(
            onDismissRequest = betrieb::einsatzEndeGelesen,
            title = { Text(if (ende.pdfErstellt == false) "Einsatz beendet, Bericht fehlt" else "Einsatz beendet") },
            text = { Text(ende.meldung) },
            confirmButton = {
                if (mitBericht) {
                    TextButton(onClick = {
                        betrieb.einsatzEndeGelesen()
                        berichtLaedt = true
                        berichtBereich.launch {
                            betrieb.berichtLaden(ende, berichtOrdner)
                                .onSuccess { berichtDatei = it }
                                .onFailure { berichtFehler = (it as? ELBerichtFehler)?.text ?: "Bericht konnte nicht geladen werden." }
                            berichtLaedt = false
                        }
                    }) { Text("Bericht öffnen") }
                } else {
                    TextButton(onClick = betrieb::einsatzEndeGelesen) { Text("OK") }
                }
            },
            dismissButton = if (mitBericht) ({ TextButton(onClick = betrieb::einsatzEndeGelesen) { Text("OK") } }) else null,
        )
    }
    if (berichtLaedt) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Bericht") },
            text = { Text("Bericht wird geladen …") },
            confirmButton = {},
        )
    }
    berichtFehler?.let { text ->
        AlertDialog(
            onDismissRequest = { berichtFehler = null },
            title = { Text("Bericht") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { berichtFehler = null }) { Text("OK") } },
        )
    }
    berichtDatei?.let { datei ->
        fun fertig() {
            betrieb.berichtLoeschen(berichtOrdner)
            berichtDatei = null
        }
        AlertDialog(
            onDismissRequest = ::fertig,
            title = { Text("Bericht") },
            text = { Text(datei.name) },
            confirmButton = {
                Row {
                    TextButton(onClick = { berichtStarten(context, datei, teilen = false) }) { Text("Öffnen") }
                    TextButton(onClick = { berichtStarten(context, datei, teilen = true) }) { Text("Teilen") }
                }
            },
            dismissButton = { TextButton(onClick = ::fertig) { Text("Fertig") } },
        )
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

/** Bereiche der Einsatzleitung, Reihenfolge nach Häufigkeit wie iOS */
enum class ELBereich(val titel: String, val symbol: Int) {
    LAGE("Lage", io.github.elemirus1996.einsatzspur.R.drawable.el_lage),
    KARTE("Karte", io.github.elemirus1996.einsatzspur.R.drawable.el_karte),
    FUNK("Funk", io.github.elemirus1996.einsatzspur.R.drawable.el_funk),
    VERMISST("Vermisst", io.github.elemirus1996.einsatzspur.R.drawable.el_vermisst),
}

/** Roter Kopf der Lage wie iOS: Stichwort, Übung, Menü; Ort, Szenario, EL; Uhr und Einsatzdauer */
@Composable
private fun EinsatzKopf(lage: ELLage?, verbindungsfehler: Boolean, jetzt: Long, starten: () -> Unit, beenden: () -> Unit, abmelden: () -> Unit) {
    var menue by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().background(ELStil.rot).windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val e = lage?.einsatz
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Titel und Übung-Chip teilen sich den Platz links, der Titel kürzt mit „…“
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        lage == null -> if (verbindungsfehler) "Keine Verbindung" else "Lade …"
                        e?.aktiv == true -> e.stichwort?.takeIf { it.isNotBlank() } ?: "Einsatz"
                        else -> "Kein Einsatz"
                    },
                    color = ELStil.aufRot, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    softWrap = false, modifier = Modifier.weight(1f, fill = false),
                )
                if (e?.uebung == true) ELChip("Übung", Color.White)
            }
            Box {
                TextButton(onClick = { menue = true }) { Text("•••", color = ELStil.aufRot, fontWeight = FontWeight.Bold) }
                DropdownMenu(expanded = menue, onDismissRequest = { menue = false }) {
                    if (e?.aktiv == true) DropdownMenuItem(text = { Text("Einsatz beenden …") }, onClick = { menue = false; beenden() })
                    else if (lage != null) DropdownMenuItem(text = { Text("Einsatz oder Übung starten") }, onClick = { menue = false; starten() })
                    DropdownMenuItem(text = { Text("Abmelden", color = MaterialTheme.colorScheme.error) }, onClick = { menue = false; abmelden() })
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            val zeile = when {
                lage == null -> if (verbindungsfehler) "Ist WireGuard eingeschaltet?" else ""
                e?.aktiv == true -> listOfNotNull(e.ort, e.szenario?.let { w -> ELSzenario.entries.firstOrNull { it.wert == w }?.anzeige ?: w }, e.einsatzleiter?.takeIf { it.isNotBlank() }?.let { "EL $it" }).joinToString(" · ")
                else -> "Die Ortung der Einsatzleitung ist aus und startet, sobald ein Einsatz läuft."
            }
            Text(zeile, color = ELStil.aufRot.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.GERMANY).format(Date(jetzt)), color = ELStil.aufRot,
                    style = ELStil.ziffern, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                e?.beginnMs?.takeIf { e.aktiv }?.let { Text("Einsatz " + dauerText((jetzt - it) / 1000), color = ELStil.aufRot.copy(alpha = 0.8f),
                    style = ELStil.ziffern, fontSize = 12.sp) }
            }
        }
        lage?.wetter?.let { w ->
            val text = listOfNotNull(w.temperatur?.let { "%.0f °C".format(it) }, w.beschreibung, w.wind?.let { "Wind %.0f km/h".format(it) }).joinToString(" · ")
            if (text.isNotEmpty()) Text(text, color = ELStil.aufRot.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Karte eines Teams wie iOS `ELTeamKarte`: Name, Suchzeit, Personen, Balken bis Warnstufe 2, Geräte, Status-Chip und Knöpfe */
@Composable
private fun TeamKarte(team: ELLage.Team, ansicht: ELAnsicht, jetzt: Long, details: () -> Unit, ausfuehren: (ELAktion) -> Unit) {
    val status = team.suche.status
    val (statusText, statusFarbe) = when (status) {
        TeamLage.SucheStatus.BEREIT -> "Bereit" to ELStil.bereit
        TeamLage.SucheStatus.LAEUFT -> "Aktiv" to ELStil.aktiv
        TeamLage.SucheStatus.KURZPAUSE -> "Pause" to ELStil.pause
        TeamLage.SucheStatus.BEENDET -> "Beendet" to ELStil.pause
    }
    val zeitFarbe = when (team.warnstufe) {
        2 -> ELStil.warnung2
        1 -> ELStil.warnung1
        else -> if (status == TeamLage.SucheStatus.LAEUFT) ELStil.aktiv else ELStil.vermerk
    }
    val rand = when {
        team.warnstufe >= 2 -> ELStil.warnung2
        team.warnstufe == 1 -> ELStil.warnung1
        status == TeamLage.SucheStatus.LAEUFT -> ELStil.aktiv
        else -> null
    }
    Column(Modifier.clickable(onClick = details).elKarte(rand), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(team.teamName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = ELStil.inschrift,
                modifier = Modifier.weight(1f), maxLines = 1)
            val dauer = ansicht.suchdauerSec(team, jetzt)
            if (dauer != null && status != TeamLage.SucheStatus.BEREIT) {
                Text(dauerText(dauer), style = ELStil.ziffern, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = zeitFarbe)
            }
        }
        val wer = listOfNotNull(team.hundefuehrerName, team.dogName).filter { it.isNotBlank() }.joinToString(" · ")
        if (wer.isNotEmpty()) Text(wer, color = ELStil.vermerk)
        val dauer = ansicht.suchdauerSec(team, jetzt)
        val w2 = team.warnung2Min
        if (dauer != null && w2 != null && w2 > 0 && status != TeamLage.SucheStatus.BEREIT) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { (dauer / (w2 * 60f)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = zeitFarbe,
                trackColor = ELStil.platteTief,
            )
        }
        val geraete = listOfNotNull(
            team.suchgebiet?.name,
            team.halsband?.let { "Halsband " + geraetText(it, ansicht, jetzt, halsband = true) },
            team.handy?.let { "Handy " + geraetText(it, ansicht, jetzt, halsband = false) },
        ).joinToString(" · ")
        if (geraete.isNotEmpty()) Text(geraete, style = MaterialTheme.typography.bodySmall, color = ELStil.vermerk, maxLines = 2)
        val hinweise = OrtungsHinweise.texte(team, ansicht.alterSec(team.halsband?.zeitMs, jetzt), ansicht.alterSec(team.handy?.zeitMs, jetzt))
        if (hinweise.isNotEmpty()) Text("⚠ " + hinweise.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = ELStil.warnung1, maxLines = 2)
        team.pauseErforderlichBisMs?.takeIf { it > jetzt }?.let {
            Text("Pause bis " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)), color = ELStil.warnung1, style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ELChip(statusText, statusFarbe)
            Spacer(Modifier.weight(1f))
            when (status) {
                TeamLage.SucheStatus.LAEUFT -> Taste("Pause") { ausfuehren(ELAktion.teamPause(team.teamId, team.teamName)) }
                TeamLage.SucheStatus.KURZPAUSE -> Taste("Weiter", ELStil.aktiv) { ausfuehren(ELAktion.teamWeiter(team.teamId, team.teamName)) }
                TeamLage.SucheStatus.BEREIT -> Taste("Start", ELStil.aktiv, onClick = details)
                TeamLage.SucheStatus.BEENDET -> Unit
            }
            Taste("•••", onClick = details)
        }
    }
}

/** Bedienknopf an der Teamkarte, groß genug für Handschuhe */
@Composable
private fun Taste(text: String, farbe: Color = ELStil.inschrift, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).background(ELStil.platteTief, RoundedCornerShape(10.dp))) {
        Text(text, color = farbe, fontWeight = FontWeight.SemiBold)
    }
}

private fun geraetText(g: ELLage.Geraet, ansicht: ELAnsicht, jetzt: Long, halsband: Boolean): String {
    val teile = mutableListOf<String>()
    ansicht.alterSec(g.zeitMs, jetzt)?.let { teile += if (it < 120) "$it s" else "${it / 60} min" }
    if (halsband) HalsbandAkku.text(g.akku)?.let { teile += it } else g.akku?.let { teile += "$it %" }
    return if (teile.isEmpty()) g.name ?: "–" else teile.joinToString(" ")
}

private val ORANGE = Color(0xFFF07A2A)

