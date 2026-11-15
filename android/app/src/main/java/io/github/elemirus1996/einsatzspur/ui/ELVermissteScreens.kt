package io.github.elemirus1996.einsatzspur.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
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
import io.github.elemirus1996.einsatzspur.kern.ELAktionAbgelehnt
import io.github.elemirus1996.einsatzspur.kern.ELBetrieb
import io.github.elemirus1996.einsatzspur.kern.ELSzenario
import io.github.elemirus1996.einsatzspur.kern.ELVermissteAnsicht
import io.github.elemirus1996.einsatzspur.kern.ELVermissteGeaendert
import io.github.elemirus1996.einsatzspur.kern.ELVermisstenprofil
import io.github.elemirus1996.einsatzspur.kern.checkliste
import io.github.elemirus1996.einsatzspur.kern.szenario
import io.github.elemirus1996.einsatzspur.kern.vermisstenEntfernen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Vermisste (wie `/el`), wie iOS `ELVermissteView`: Szenario, Profile, Anlegen, Bearbeiten mit Versionsprüfung, Checkliste. */
@Composable
fun ELVermissteScreen(vermisste: ELVermissteAnsicht, betrieb: ELBetrieb, lage: io.github.elemirus1996.einsatzspur.kern.ELLage? = null,
                      zurueck: (() -> Unit)? = null) {
    val bereich = rememberCoroutineScope()
    val stand by vermisste.stand.collectAsState()
    val fehler by vermisste.fehler.collectAsState()
    var bearbeiten by remember { mutableStateOf<ELVermisstenprofil?>(null) }
    LaunchedEffect(Unit) { vermisste.laden() }

    bearbeiten?.let { profil ->
        ELProfilScreen(profil, vermisste, betrieb, zurueck = { bearbeiten = null })
        return
    }
    zurueck?.let { BackHandler(onBack = it) }

    Column(Modifier.fillMaxSize().background(ELStil.papier)) {
        ELReiterKopf("Vermisst", lage)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val s = stand
            if (s == null) {
                Text(fehler ?: "Lade …", color = ELStil.vermerk)
                return@Column
            }
            Column(Modifier.elKarte()) {
                Auswahl("Szenario", s.szenario.anzeige, ELSzenario.entries.map { it.anzeige }) { i ->
                    bereich.launch {
                        betrieb.ausfuehren(ELAktion.szenario(ELSzenario.entries[i]))
                        vermisste.laden()
                    }
                }
                Text(
                    if (s.mehrereErlaubt) "In diesem Szenario sind mehrere Personen möglich." else "In diesem Szenario wird eine Person gesucht.",
                    style = MaterialTheme.typography.bodySmall, color = ELStil.vermerk,
                )
            }
            Text("Gesuchte Personen  ${s.profile.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = ELStil.inschrift)
            if (s.profile.isEmpty()) Text("Noch kein Profil angelegt.", color = ELStil.vermerk, modifier = Modifier.elKarte())
            s.profile.forEach { p -> Steckbrief(p) { bearbeiten = p } }
            if (s.mehrereErlaubt || s.profile.isEmpty()) {
                TextButton(
                    onClick = { bearbeiten = ELVermisstenprofil() },
                    modifier = Modifier.fillMaxWidth().background(ELStil.aktion.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                ) { Text("+ Person hinzufügen", color = ELStil.aktion, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** Steckbrief wie iOS `ELSteckbrief`: Initialen, Name, Warnchips, zuletzt gesehen, Checkliste */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Steckbrief(p: ELVermisstenprofil, oeffnen: () -> Unit) {
    Column(Modifier.clickable(onClick = oeffnen).elKarte(rand = ELStil.aktion), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val initialen = listOf(p.text("vorname"), p.text("nachname")).mapNotNull { it.firstOrNull()?.toString() }.joinToString("").ifEmpty { "?" }
            Box(Modifier.size(46.dp).background(ELStil.aktion.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                Text(initialen, color = ELStil.aktion, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text(p.vollerName ?: "Unbekannt", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = ELStil.inschrift)
                val unter = listOfNotNull(p.text("alter").takeIf { it.isNotEmpty() }?.let { "$it Jahre" }, p.text("besonderheiten").takeIf { it.isNotEmpty() })
                    .joinToString(" · ")
                if (unter.isNotEmpty()) Text(unter, color = ELStil.vermerk)
            }
            Text("›", color = ELStil.vermerk, style = MaterialTheme.typography.titleLarge)
        }
        val warn = warnungen(p)
        if (warn.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                warn.forEach { (text, stufe) ->
                    ELChip(text, when (stufe) { 2 -> ELStil.warnung2; 1 -> ELStil.warnung1; else -> ELStil.pause })
                }
            }
        }
        listOf("Zuletzt gesehen" to "zuletztGesehenOrt", "Zeit" to "zuletztGesehenZeit", "Kleidung" to "kleidung", "Aussehen" to "beschreibung")
            .forEach { (titel, feld) ->
                p.text(feld).takeIf { it.isNotEmpty() }?.let {
                    Row {
                        Text(titel, color = ELStil.vermerk, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(120.dp))
                        Text(it, color = ELStil.inschrift, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        p.checkliste?.takeIf { it.punkte.isNotEmpty() }?.let { c ->
            val erledigt = c.punkte.count { it.erledigt }
            Row {
                Text("Checkliste ${c.name ?: ""}", modifier = Modifier.weight(1f), color = ELStil.inschrift)
                Text("$erledigt / ${c.punkte.size}", color = ELStil.aktiv, fontWeight = FontWeight.SemiBold)
            }
            androidx.compose.material3.LinearProgressIndicator(
                progress = { erledigt.toFloat() / c.punkte.size }, modifier = Modifier.fillMaxWidth(), color = ELStil.aktiv,
            )
        }
    }
}

/** Warnchips: 2 = rot (Risiken), 1 = orange (Erkrankung, Medikamente, Orientierung), 0 = grau (Mobilität) */
private fun warnungen(p: ELVermisstenprofil): List<Pair<String, Int>> {
    val w = mutableListOf<Pair<String, Int>>()
    if (p.auswahlen["suizidrisiko"] == "Ja") w += "Suizidrisiko" to 2
    if (p.auswahlen["bewaffnet"] == "Ja") w += "Bewaffnet" to 2
    p.text("vorerkrankungen").split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { w += it to 1 }
    p.text("medikamente").split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { w += it to 1 }
    if (p.auswahlen["orientierung"] == "Eingeschraenkt") w += "Orientierung eingeschränkt" to 1
    when (val m = p.auswahlen["mobilitaet"]) {
        null, "Unbekannt" -> Unit
        "ZuFuss" -> w += "Zu Fuß" to 0
        else -> w += m to 0
    }
    return w
}

/**
 * Ein Profil bearbeiten. Gespeichert werden nur Änderungen mit der `version`, auf der die Bearbeitung beruht;
 * hat jemand anderes inzwischen gespeichert (409), zeigt die App den neuen Stand zum erneuten Bearbeiten.
 */
@Composable
private fun ELProfilScreen(start: ELVermisstenprofil, vermisste: ELVermissteAnsicht, betrieb: ELBetrieb, zurueck: () -> Unit) {
    val bereich = rememberCoroutineScope()
    val stand by vermisste.stand.collectAsState()
    var original by remember { mutableStateOf(start) }
    var profil by remember { mutableStateOf(start) }
    var speichert by remember { mutableStateOf(false) }
    var meldung by remember { mutableStateOf<String?>(null) }
    var entfernenFragen by remember { mutableStateOf(false) }
    // Checkliste immer aus dem zuletzt geladenen Stand (wird über /checkliste geändert, nicht beim Speichern)
    val checkliste = stand?.profile?.firstOrNull { it.profilId == original.profilId }?.checkliste ?: original.checkliste
    BackHandler(onBack = zurueck)

    fun feld(titel: String, name: String, lang: Boolean = false): @Composable () -> Unit = {
        OutlinedTextField(
            profil.text(name), { profil = profil.mitText(name, it.take(2000)) }, label = { Text(titel) },
            singleLine = !lang, minLines = if (lang) 2 else 1, modifier = Modifier.fillMaxWidth(),
        )
    }
    fun schalter(titel: String, name: String): @Composable () -> Unit = {
        SchalterZeile(titel, profil.schalter(name)) { profil = profil.mitSchalter(name, it) }
    }
    fun auswahl(titel: String, name: String): @Composable () -> Unit = {
        val werte = ELVermisstenprofil.AUSWAHL_FELDER.getValue(name)
        Auswahl(titel, ELVermisstenprofil.anzeige(profil.auswahl(name)), werte.map(ELVermisstenprofil::anzeige)) {
            profil = profil.mitAuswahl(name, werte[it])
        }
    }

    fun speichern() = bereich.launch {
        speichert = true
        try {
            vermisste.speichern(profil, original)
            zurueck()
        } catch (e: ELVermissteGeaendert) {
            // Neuer Stand vom Server; eigene Eingaben gehen nicht stillschweigend verloren: Hinweis und neu bearbeiten
            original = e.profil
            profil = e.profil
            meldung = "Das Profil wurde inzwischen geändert. Der aktuelle Stand wird angezeigt, bitte die Änderung erneut eintragen."
        } catch (e: ELAktionAbgelehnt) {
            meldung = e.text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            meldung = "Speichern nicht möglich (keine Verbindung?). Bitte erneut versuchen."
        } finally {
            speichert = false
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = zurueck) { Text(if (original.profilId == null) "Abbrechen" else "‹ Zurück") }
                Text(
                    if (original.profilId == null) "Neue Person" else original.vollerName ?: "Profil",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { speichern() },
                    enabled = !speichert && (original.profilId == null || profil.aenderungen(original).length() > 0),
                ) { Text("Speichern") }
            }
            Abschnitt("Person", feld("Vorname", "vorname"), feld("Nachname", "nachname"), feld("Alter", "alter"), feld("Geburtsdatum", "geburtsdatum"))
            Abschnitt("Erscheinung", feld("Beschreibung", "beschreibung", true), feld("Kleidung", "kleidung", true), feld("Besonderheiten", "besonderheiten", true))
            Abschnitt("Zuletzt gesehen", feld("Ort", "zuletztGesehenOrt"), feld("Zeit (Freitext)", "zuletztGesehenZeit"), feld("Von", "zuletztGesehenVon"))
            Abschnitt(
                "Gesundheit und Einschätzung", feld("Vorerkrankungen", "vorerkrankungen", true), feld("Medikamente", "medikamente", true),
                auswahl("Orientierung", "orientierung"), auswahl("Mobilität", "mobilitaet"),
                auswahl("Suizidrisiko", "suizidrisiko"), auswahl("Bewaffnet", "bewaffnet"),
            )
            Abschnitt(
                "Polizei", feld("Kontakt", "polizeiKontaktName"), feld("Dienstnummer", "polizeiDienstnummer"), feld("Telefon", "polizeiTelefon"),
                schalter("Vermisstenmeldung aufgenommen", "polizeiVermisstenmeldungAufgenommen"),
                schalter("Koordination besprochen", "polizeiKoordinationBesprochen"),
                schalter("Suchabschnitt abgestimmt", "polizeiSuchabschnittAbgestimmt"),
                schalter("Rückmeldepflicht vereinbart", "polizeiRueckmeldepflichtVereinbart"),
                schalter("Datenschutz geklärt", "polizeiDatenschutzGeklaert"),
            )
            Abschnitt(
                "Andere BOS", feld("Einheit", "bosEinheit"), feld("Zugführer", "bosZugfuehrer"), feld("Funkrufname", "bosFunkrufname"),
                feld("Aufgabenteilung", "bosAufgabenteilung", true),
                schalter("Abschnitt abgestimmt", "bosAbschnittAbgestimmt"), schalter("Ressourcen besprochen", "bosRessourcenBesprochen"),
            )
            val profilId = original.profilId
            if (checkliste != null && profilId != null) {
                HorizontalDivider()
                Text(checkliste.name?.let { "Checkliste $it" } ?: "Checkliste", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                checkliste.punkte.forEach { punkt ->
                    val titel = punkt.text + if (punkt.pflicht) " *" else ""
                    when (punkt.typ) {
                        "jaNein" -> SchalterZeile(titel, punkt.erledigt) { neu ->
                            bereich.launch {
                                betrieb.ausfuehren(ELAktion.checkliste(profilId, punkt, erledigt = neu))
                                vermisste.laden()
                            }
                        }
                        "auswahl" -> Auswahl(titel, punkt.wert?.takeIf { it.isNotEmpty() } ?: "–", listOf("–") + punkt.auswahl) { i ->
                            bereich.launch {
                                betrieb.ausfuehren(ELAktion.checkliste(profilId, punkt, wert = if (i == 0) "" else punkt.auswahl[i - 1]))
                                vermisste.laden()
                            }
                        }
                        else -> Text("$titel: ${punkt.wert.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (profilId != null) {
                HorizontalDivider()
                TextButton(onClick = { entfernenFragen = true }) { Text("Profil entfernen", color = Color.Red) }
            }
        }
    }
    if (entfernenFragen) {
        AlertDialog(
            onDismissRequest = { entfernenFragen = false },
            title = { Text("Profil entfernen?") },
            confirmButton = {
                TextButton(onClick = {
                    entfernenFragen = false
                    val id = original.profilId ?: return@TextButton
                    bereich.launch {
                        betrieb.ausfuehren(ELAktion.vermisstenEntfernen(id, original.vollerName ?: "Profil"))
                        vermisste.laden()
                        zurueck()
                    }
                }) { Text("Entfernen", color = Color.Red) }
            },
            dismissButton = { TextButton(onClick = { entfernenFragen = false }) { Text("Abbrechen") } },
        )
    }
    meldung?.let { text ->
        AlertDialog(
            onDismissRequest = { meldung = null },
            title = { Text("Hinweis") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { meldung = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun Abschnitt(titel: String, vararg inhalt: @Composable () -> Unit) {
    HorizontalDivider()
    Text(titel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    inhalt.forEach { it() }
}

@Composable
private fun SchalterZeile(titel: String, wert: Boolean, aendern: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(titel, Modifier.weight(1f))
        Switch(wert, aendern)
    }
}

/** Auswahlliste als Knopf mit Menü; liefert den Index der gewählten Anzeige. */
@Composable
private fun Auswahl(titel: String, aktuell: String, anzeigen: List<String>, waehlen: (Int) -> Unit) {
    var offen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(titel, Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { offen = true }) { Text(aktuell) }
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
