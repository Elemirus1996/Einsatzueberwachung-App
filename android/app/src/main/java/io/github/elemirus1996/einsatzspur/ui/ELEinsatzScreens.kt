package io.github.elemirus1996.einsatzspur.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import io.github.elemirus1996.einsatzspur.kern.ELAbschluss
import io.github.elemirus1996.einsatzspur.kern.ELAbschlussEingabe
import io.github.elemirus1996.einsatzspur.kern.ELAktion
import io.github.elemirus1996.einsatzspur.kern.ELAlarmiertDurch
import io.github.elemirus1996.einsatzspur.kern.ELBerichtAbschnitt
import io.github.elemirus1996.einsatzspur.kern.ELBetrieb
import io.github.elemirus1996.einsatzspur.kern.ELEinsatzStart
import io.github.elemirus1996.einsatzspur.kern.ELErgebnis
import io.github.elemirus1996.einsatzspur.kern.ELKartenart
import io.github.elemirus1996.einsatzspur.kern.ELLage
import io.github.elemirus1996.einsatzspur.kern.ELSzenario
import io.github.elemirus1996.einsatzspur.kern.ELVorOrt
import io.github.elemirus1996.einsatzspur.kern.ELVorlage
import io.github.elemirus1996.einsatzspur.kern.Position
import io.github.elemirus1996.einsatzspur.kern.TeamLage
import io.github.elemirus1996.einsatzspur.kern.einsatzBeenden
import io.github.elemirus1996.einsatzspur.kern.einsatzStart
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * Einsatz starten (wie `/el/start`), wie iOS `ELEinsatzStartView`: Divera-Alarm oder von Hand, Stichwort, Ort, Szenario,
 * Einsatz/Übung, Einsatzleiter und Führungsassistent. Die Auswahllisten kommen aus `GET /einsatz/vorlage`.
 */
@Composable
fun ELEinsatzStartScreen(betrieb: ELBetrieb, eigenePosition: Position?, schliessen: () -> Unit,
                         /** Gewählte Person (Abschnitt 4a): als Einsatzleiter vorgeschlagen, damit wer anlegt auch steuern darf */
                         eigenerName: String? = null) {
    val bereich = rememberCoroutineScope()
    var daten by remember { mutableStateOf(ELEinsatzStart()) }
    var standortUebernehmen by remember { mutableStateOf(true) }
    var vorlage by remember { mutableStateOf<ELVorlage?>(null) }
    var geladen by remember { mutableStateOf(false) }
    val alarm = daten.diveraAlarmId?.let { id -> vorlage?.divera?.firstOrNull { it.id == id } }
    BackHandler(onBack = schliessen)
    LaunchedEffect(Unit) {
        if (daten.einsatzleiter.isEmpty() && eigenerName != null) daten = daten.copy(einsatzleiter = eigenerName)
        vorlage = betrieb.vorlage()
        geladen = true
    }

    Formular(if (daten.uebung) "Übung starten" else "Einsatz starten", "Starten",
        aktiv = daten.vollstaendig && vorlage?.aktiv != true, schliessen = schliessen,
        bestaetigen = {
            val start = if (standortUebernehmen && eigenePosition != null) daten.copy(lat = eigenePosition.lat, lng = eigenePosition.lng) else daten
            bereich.launch { betrieb.ausfuehren(ELAktion.einsatzStart(start)) }
            schliessen()
        },
    ) {
        if (vorlage?.aktiv == true) {
            Text("Es läuft bereits ein Einsatz. Erst beenden, dann neu starten.", color = ORANGE_HINWEIS)
        }
        // Offener Divera-Alarm: oben vorschlagen wie im Browser
        val neuester = vorlage?.divera?.maxByOrNull { it.zeitMs ?: 0L }
        if (daten.diveraAlarmId == null && neuester != null) {
            Button(onClick = {
                daten = daten.copy(diveraAlarmId = neuester.id)
                if (neuester.lat != null) standortUebernehmen = false
            }, modifier = Modifier.fillMaxWidth()) { Text("Divera-Alarm übernehmen: " + alarmText(neuester)) }
        }
        vorlage?.divera?.takeIf { it.isNotEmpty() }?.let { alarme ->
            Wahl("Divera-Alarm", alarm?.let(::alarmText) ?: "ohne Alarm (von Hand)",
                listOf("ohne Alarm (von Hand)") + alarme.map(::alarmText)) { i ->
                val neu = if (i == 0) null else alarme[i - 1]
                daten = daten.copy(diveraAlarmId = neu?.id)
                // Koordinate des Alarms nicht mit dem eigenen Standort überschreiben
                if (neu?.lat != null) standortUebernehmen = false
            }
            if (alarm != null) Klein("Ort, Stichwort und Koordinate übernimmt der Server aus dem Alarm. Eigene Angaben gehen vor.")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!daten.uebung, { daten = daten.copy(uebung = false) }, label = { Text("Einsatz") })
            FilterChip(daten.uebung, { daten = daten.copy(uebung = true) }, label = { Text("Übung") })
        }
        Wahl("Alarmiert durch (Pflicht)", daten.alarmiertDurch?.anzeige ?: "bitte wählen", ELAlarmiertDurch.entries.map { it.anzeige }) {
            daten = daten.copy(alarmiertDurch = ELAlarmiertDurch.entries[it])
        }
        Feld("Stelle oder Name (optional)", daten.melder) { daten = daten.copy(melder = it) }
        if (alarm != null) Klein("Den Melder aus dem Alarm übernimmt der Server, wer alarmiert hat, wählst du hier.")
        Feld(alarm?.titel?.let { "Stichwort (aus Alarm: $it)" } ?: "Stichwort", daten.stichwort) { daten = daten.copy(stichwort = it) }
        Feld(alarm?.let { "Einsatzort (aus Alarm: ${it.ort ?: "–"})" } ?: "Einsatzort (Pflicht)", daten.ort) { daten = daten.copy(ort = it) }
        val szenarien = (vorlage ?: ELVorlage()).szenarioWahl
        Wahl("Szenario (Pflicht)", if (daten.szenario == ELSzenario.UNBESTIMMT) "bitte wählen" else daten.szenario.anzeige,
            szenarien.map { it.anzeige }) { daten = daten.copy(szenario = szenarien[it]) }
        HorizontalDivider()
        Ueberschrift("Führung")
        NamensFeld("Einsatzleiter", daten.einsatzleiter, vorlage?.einsatzleiter.orEmpty()) { daten = daten.copy(einsatzleiter = it) }
        NamensFeld("Führungsassistent", daten.fuehrungsassistent, vorlage?.fuehrungsassistenten.orEmpty()) {
            daten = daten.copy(fuehrungsassistent = it)
        }
        if (eigenePosition != null) {
            HorizontalDivider()
            Schalter("Eigenen Standort als Einsatzort", standortUebernehmen) { standortUebernehmen = it }
            Klein("Die Koordinate hilft beim Wetter und als Mittelpunkt der Karte." +
                (eigenePosition.accuracy?.let { " Genauigkeit ± ${Math.round(it)} m." } ?: ""))
        }
        if (geladen && vorlage == null) Klein("Auswahllisten nicht geladen, Eingabe von Hand.")
    }
}

/**
 * Einsatz beenden (wie `/el/beenden`), wie iOS `ELEinsatzBeendenView`: Ergebnis, Anwesenheit, Prüfung der Ressourcen, Endzeit,
 * PDF mit Tracks, Kartenpunkten und Teamauswahl.
 */
@Composable
fun ELEinsatzBeendenScreen(betrieb: ELBetrieb, lage: ELLage, schliessen: () -> Unit) {
    val bereich = rememberCoroutineScope()
    val context = LocalContext.current
    var ergebnisse by remember { mutableStateOf<List<ELErgebnis>>(emptyList()) }
    var kartenarten by remember { mutableStateOf(ELVorlage().kartenWahl) }
    var ergebnis by remember { mutableStateOf<ELErgebnis?>(null) }
    var bemerkungen by remember { mutableStateOf("") }
    var geprueft by remember { mutableStateOf(false) }
    var mitTracks by remember { mutableStateOf(true) }
    var mitPunkten by remember { mutableStateOf(false) }
    var kartenart by remember { mutableStateOf(ELKartenart.STREETS.wert) }
    var alleTeams by remember { mutableStateOf(true) }
    var teamIds by remember { mutableStateOf(emptySet<String>()) }
    var endzeitAngeben by remember { mutableStateOf(false) }
    var endzeit by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var fragen by remember { mutableStateOf(false) }
    var vorOrt by remember { mutableStateOf<ELVorOrt?>(null) }
    var personalIds by remember { mutableStateOf(emptySet<String>()) }
    var hundeIds by remember { mutableStateOf(emptySet<String>()) }
    // Server 1.17.0: Angaben für den Bericht und Abschnitte; null = älterer Server, Beenden wie bisher
    var abschluss by remember { mutableStateOf<ELAbschluss?>(null) }
    var eingabe by remember { mutableStateOf(ELAbschlussEingabe()) }
    var abschnitte by remember { mutableStateOf(ELBerichtAbschnitt.entries.toSet()) }
    var trotzdem by remember { mutableStateOf(false) }
    val istFund = ergebnis?.fund == true
    val fehlend = abschluss?.let { eingabe.fehlend(it, istFund) }.orEmpty()
    BackHandler(onBack = schliessen)
    LaunchedEffect(Unit) {
        betrieb.abschluss()?.let { a ->
            eingabe = ELAbschlussEingabe.aus(a)
            abschluss = a
        }
        val v = betrieb.vorlage()
        v?.let { kartenarten = it.kartenWahl }
        ergebnisse = v?.ergebnisse?.takeIf { it.isNotEmpty() } ?: betrieb.ergebnisse()
        betrieb.vorOrt()?.let { liste ->
            personalIds = liste.personal.filter { it.vorausgewaehlt }.map { it.id }.toSet()
            hundeIds = liste.hunde.filter { it.vorausgewaehlt }.map { it.id }.toSet()
            vorOrt = liste
        }
    }

    Formular("Einsatz beenden", "Beenden",
        aktiv = ergebnis != null && geprueft && (alleTeams || teamIds.isNotEmpty()) && (fehlend.isEmpty() || trotzdem),
        schliessen = schliessen, bestaetigen = { fragen = true }) {
        Ueberschrift("Ergebnis")
        if (ergebnisse.isEmpty()) Klein("Lade …")
        Wahl("Ergebnis", ergebnis?.anzeige ?: "bitte wählen", ergebnisse.map { it.anzeige }) { ergebnis = ergebnisse[it] }
        Feld("Bemerkungen (optional)", bemerkungen, lang = true) { bemerkungen = it }
        HorizontalDivider()
        abschluss?.let { a ->
            ELAbschlussAngaben(a, eingabe, istFund) { eingabe = it }
            HorizontalDivider()
        }
        vorOrt?.let { liste ->
            Ueberschrift("Vor Ort")
            AnwesenheitListe("Personal", liste.personal, personalIds) { personalIds = it }
            AnwesenheitListe("Hunde", liste.hunde, hundeIds) { hundeIds = it }
            Klein("Vorausgewählt ist, wer in einem Team eingeteilt war.")
            HorizontalDivider()
        }
        Ueberschrift("Ressourcen")
        val aktive = lage.teams.filter { it.suche.status == TeamLage.SucheStatus.LAEUFT || it.suche.status == TeamLage.SucheStatus.KURZPAUSE }
        if (aktive.isEmpty()) Text("Keine Suche läuft mehr", color = Color(0xFF2E9D4F))
        aktive.forEach { Text("${it.teamName} sucht noch", color = ORANGE_HINWEIS) }
        Schalter("Alle Teams und Halsbänder sind zurück", geprueft) { geprueft = it }
        Klein("Erst beenden, wenn alle Teams und Geräte zurück sind.")
        HorizontalDivider()
        Ueberschrift("Endzeit")
        Schalter("Andere Endzeit", endzeitAngeben) { endzeitAngeben = it }
        if (endzeitAngeben) {
            OutlinedButton(onClick = {
                val k = Calendar.getInstance().apply { timeInMillis = endzeit }
                DatePickerDialog(context, { _, j, m, t ->
                    TimePickerDialog(context, { _, h, min ->
                        val gewaehlt = Calendar.getInstance().apply { set(j, m, t, h, min, 0) }.timeInMillis
                        // Zwischen Einsatzbeginn und jetzt
                        endzeit = gewaehlt.coerceIn(lage.einsatz.beginnMs ?: 0L, System.currentTimeMillis())
                    }, k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE), true).show()
                }, k.get(Calendar.YEAR), k.get(Calendar.MONTH), k.get(Calendar.DAY_OF_MONTH)).apply {
                    datePicker.maxDate = System.currentTimeMillis()
                    lage.einsatz.beginnMs?.let { datePicker.minDate = it }
                }.show()
            }) { Text("Ende: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(endzeit))) }
            Klein("Zwischen Einsatzbeginn und jetzt.")
        } else {
            Klein("Ohne Angabe gilt der Zeitpunkt des Beendens.")
        }
        HorizontalDivider()
        Ueberschrift("Bericht (PDF)")
        Schalter("Tracks in den Bericht", mitTracks) { mitTracks = it }
        Schalter("Kartenpunkte in den Bericht", mitPunkten) { mitPunkten = it }
        Wahl("Karte", kartenarten.firstOrNull { it.first == kartenart }?.second ?: kartenart, kartenarten.map { it.second }) {
            kartenart = kartenarten[it].first
        }
        if (abschluss != null) {
            Klein("Abschnitte im Bericht (Kopf, Ergebnis und Polizei immer):")
            ELBerichtAbschnitt.entries.forEach { ab ->
                Schalter(ab.anzeige, ab in abschnitte) { an -> abschnitte = if (an) abschnitte + ab else abschnitte - ab }
            }
        }
        if (fehlend.isNotEmpty()) {
            Text("Es fehlen noch: ${fehlend.joinToString(", ")}. Nach dem Beenden ist nichts mehr änderbar.", color = ORANGE_HINWEIS)
            Schalter("Trotzdem ohne diese Angaben beenden", trotzdem) { trotzdem = it }
        }
        if (lage.teams.isNotEmpty()) {
            Schalter("Alle Teams im Bericht", alleTeams) { alleTeams = it }
            if (!alleTeams) lage.teams.forEach { team ->
                Schalter(team.teamName, team.teamId in teamIds) { an ->
                    teamIds = if (an) teamIds + team.teamId else teamIds - team.teamId
                }
            }
        }
    }
    if (fragen) {
        AlertDialog(
            onDismissRequest = { fragen = false },
            title = { Text("Einsatz wirklich beenden?") },
            text = { Text("Alle Team-Handys werden abgemeldet, ihre Ortung endet.") },
            confirmButton = {
                TextButton(onClick = {
                    fragen = false
                    val e = ergebnis ?: return@TextButton
                    // Reihenfolge der Teams wie in der Lage
                    val auswahl = if (alleTeams) null else lage.teams.map { it.teamId }.filter { it in teamIds }
                    // Reihenfolge wie in der Liste; ohne Liste (alter Server) nimmt der Server die Vorauswahl
                    val personal = vorOrt?.personal?.map { it.id }?.filter { it in personalIds }
                    val hunde = vorOrt?.hunde?.map { it.id }?.filter { it in hundeIds }
                    // Lücken hat die Einsatzleitung oben ausdrücklich bestätigt
                    val a = abschluss
                    val angaben = a?.let { eingabe.json(it, istFund) }
                    val berichtAbschnitte = a?.let { ELBerichtAbschnitt.entries.filter { it in abschnitte }.map { it.wert } + if (mitTracks) listOf("tracks") else emptyList() }
                    bereich.launch {
                        betrieb.ausfuehren(ELAktion.einsatzBeenden(
                            e.wert, mitTracks, kartenart, auswahl,
                            if (endzeitAngeben) minOf(endzeit, System.currentTimeMillis()) else null, bemerkungen, mitPunkten,
                            personal, hunde, abschluss = angaben, unvollstaendigBestaetigt = if (a == null) null else true,
                            abschnitte = berichtAbschnitte,
                        ))
                    }
                    schliessen()
                }) { Text("Einsatz beenden", color = Color.Red) }
            },
            dismissButton = { TextButton(onClick = { fragen = false }) { Text("Abbrechen") } },
        )
    }
}

private val ORANGE_HINWEIS = Color(0xFFF07A2A)

/** Angaben für den Einsatzbericht (APP_API EL Abschnitt 10), wie iOS `ELAbschlussAbschnitte` */
@Composable
private fun ELAbschlussAngaben(a: ELAbschluss, e: ELAbschlussEingabe, fund: Boolean, aendern: (ELAbschlussEingabe) -> Unit) {
    val context = LocalContext.current
    Ueberschrift("Alarmierung")
    Wahl("Alarmiert durch", e.alarmiertDurch?.anzeige ?: "bitte wählen", ELAlarmiertDurch.entries.map { it.anzeige }) {
        aendern(e.copy(alarmiertDurch = ELAlarmiertDurch.entries[it]))
    }
    Feld("Stelle oder Name (optional)", e.melder) { aendern(e.copy(melder = it)) }
    if (!e.uebung) Schalter("Keine Polizei beteiligt", e.keinePolizei) { aendern(e.copy(keinePolizei = it)) }
    if (!e.keinePolizei) {
        Ueberschrift("Polizei")
        Feld("Vorgangsnummer", e.vorgangsnummer) { aendern(e.copy(vorgangsnummer = it)) }
        Feld("Ansprechpartner", e.polizeiName) { aendern(e.copy(polizeiName = it)) }
        Feld("Dienstnummer", e.dienstnummer) { aendern(e.copy(dienstnummer = it)) }
        Feld("Telefon", e.telefon) { aendern(e.copy(telefon = it)) }
        Klein(if (e.polizeiGefragt) "Pflicht, weil die Polizei alarmiert hat. Nach dem Beenden ist nichts mehr änderbar."
              else "Freiwillig, wenn die Polizei nicht alarmiert hat.")
    }
    if (fund) {
        Ueberschrift("Fund")
        OutlinedButton(onClick = {
            val k = Calendar.getInstance().apply { timeInMillis = e.fundZeitMs ?: System.currentTimeMillis() }
            TimePickerDialog(context, { _, h, min ->
                val zeit = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min); set(Calendar.SECOND, 0) }.timeInMillis
                // Uhrzeit nach „jetzt“ war am Vortag
                aendern(e.copy(fundZeitMs = if (zeit > System.currentTimeMillis()) zeit - 86_400_000L else zeit))
            }, k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE), true).show()
        }) { Text("Fundzeit: " + (e.fundZeitMs?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) } ?: "angeben")) }
        Feld("Fundort (Beschreibung)", e.fundOrt) { aendern(e.copy(fundOrt = it)) }
        if (e.fundLat != null && e.fundLng != null) Klein("Koordinate %.5f, %.5f".format(e.fundLat, e.fundLng))
        a.fund.vorschlag?.let { v -> OutlinedButton(onClick = { aendern(e.vorschlagUebernehmen(v)) }) { Text("Fund-Punkt übernehmen") } }
        Wahl("Gefunden durch", a.teams.firstOrNull { it.teamId == e.fundTeamId }?.teamName ?: "nicht angegeben",
            listOf("nicht angegeben") + a.teams.map { it.teamName }) { i ->
            aendern(e.copy(fundTeamId = if (i == 0) null else a.teams[i - 1].teamId))
        }
        Feld("Übergabe an (z. B. Rettungsdienst)", e.uebergabeAn) { aendern(e.copy(uebergabeAn = it)) }
    }
    if (a.teams.isNotEmpty()) {
        Ueberschrift("Eingesetzt als")
        a.teams.forEach { t ->
            val wahl = t.wahl
            Wahl(t.teamName, e.einsatzart[t.teamId]?.anzeige ?: "nicht festgelegt", wahl.map { it.anzeige }) { i ->
                aendern(e.copy(einsatzart = e.einsatzart + (t.teamId to wahl[i])))
            }
        }
    }
    if (a.trails.isNotEmpty()) {
        Ueberschrift("Trails")
        a.trails.forEach { t ->
            Text(t.teamName, fontWeight = FontWeight.SemiBold)
            val gruende = (a.abbruchgruende + listOf(t.abbruchgrund)).filter { it.isNotEmpty() }.distinct()
            Wahl("Abbruchgrund", e.abbruchgrund[t.sucheId]?.takeIf { it.isNotEmpty() } ?: "bitte wählen", gruende) { i ->
                aendern(e.copy(abbruchgrund = e.abbruchgrund + (t.sucheId to gruende[i])))
            }
            Feld("Geruchsartikel", e.geruchsartikel[t.sucheId] ?: "") { aendern(e.copy(geruchsartikel = e.geruchsartikel + (t.sucheId to it))) }
        }
    }
}

/** Aufklappbare Liste zum Ankreuzen (Personal oder Hunde vor Ort) mit Zähler wie in `/el/beenden` */
@Composable
private fun AnwesenheitListe(titel: String, eintraege: List<ELVorOrt.Eintrag>, auswahl: Set<String>, aendern: (Set<String>) -> Unit) {
    var offen by remember { mutableStateOf(false) }
    TextButton(onClick = { offen = !offen }) { Text("$titel (${auswahl.size}) " + if (offen) "▾" else "▸") }
    if (offen) {
        if (eintraege.isEmpty()) Klein("Keine aktiven Einträge in den Stammdaten.")
        eintraege.forEach { e -> Schalter(e.name, e.id in auswahl) { an -> aendern(if (an) auswahl + e.id else auswahl - e.id) } }
    }
}

private fun alarmText(a: ELVorlage.DiveraAlarm) = listOfNotNull(
    a.titel ?: "Alarm ${a.id}", a.ort?.takeIf { it.isNotEmpty() }, a.zeitMs?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) },
).joinToString(" · ")

@Composable
private fun Formular(
    titel: String,
    knopf: String,
    aktiv: Boolean,
    schliessen: () -> Unit,
    bestaetigen: () -> Unit,
    inhalt: @Composable () -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = schliessen) { Text("Abbrechen") }
                Text(titel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Button(onClick = bestaetigen, enabled = aktiv) { Text(knopf) }
            }
            inhalt()
        }
    }
}

@Composable
private fun Ueberschrift(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

@Composable
private fun Klein(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Feld(titel: String, wert: String, lang: Boolean = false, aendern: (String) -> Unit) = OutlinedTextField(
    wert, aendern, label = { Text(titel) }, singleLine = !lang, minLines = if (lang) 2 else 1, modifier = Modifier.fillMaxWidth(),
)

@Composable
private fun Schalter(titel: String, wert: Boolean, aendern: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(titel, Modifier.weight(1f))
        Switch(wert, aendern)
    }
}

/** Auswahlliste als Knopf mit Menü; liefert den Index der gewählten Anzeige. */
@Composable
private fun Wahl(titel: String, aktuell: String, anzeigen: List<String>, waehlen: (Int) -> Unit) {
    var offen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(titel, Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { offen = true }, enabled = anzeigen.isNotEmpty()) { Text(aktuell) }
            DropdownMenu(offen, { offen = false }) {
                anzeigen.forEachIndexed { i, a ->
                    DropdownMenuItem(text = { Text(a) }, onClick = {
                        offen = false
                        waehlen(i)
                    })
                }
            }
        }
    }
}

/** Textfeld mit Auswahl aus den Stammdaten; freie Eingabe bleibt möglich. */
@Composable
private fun NamensFeld(titel: String, wert: String, vorschlaege: List<String>, aendern: (String) -> Unit) {
    var offen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(wert, aendern, label = { Text(titel) }, singleLine = true, modifier = Modifier.weight(1f))
        if (vorschlaege.isNotEmpty()) {
            Box {
                TextButton(onClick = { offen = true }) { Text("Auswahl") }
                DropdownMenu(offen, { offen = false }) {
                    vorschlaege.forEach { name ->
                        DropdownMenuItem(text = { Text(name) }, onClick = {
                            offen = false
                            aendern(name)
                        })
                    }
                }
            }
        }
    }
}
