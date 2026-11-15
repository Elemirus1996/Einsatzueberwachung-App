package io.github.elemirus1996.einsatzspur.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.elemirus1996.einsatzspur.kern.ELLage
import io.github.elemirus1996.einsatzspur.kern.TeamLage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Farben und Bausteine der Einsatzleitung wie iOS `ELStil` (Gestaltung Punkt 9): Kopfleiste in Staffelrot, hellgraue Fläche,
 * weiße Karten mit runden Ecken, weiche Status-Chips. Hell und dunkel.
 */
object ELStil {
    val rot @Composable get() = if (isSystemInDarkTheme()) Color(0xFF6E1F21) else Color(0xFFA32D2D)
    val aufRot = Color.White
    val papier @Composable get() = if (isSystemInDarkTheme()) Color(0xFF000000) else Color(0xFFF2F2F4)
    val platte @Composable get() = if (isSystemInDarkTheme()) Color(0xFF1C1C1E) else Color.White
    val platteTief @Composable get() = if (isSystemInDarkTheme()) Color(0xFF2C2C2E) else Color(0xFFF2F2F4)
    val inschrift @Composable get() = if (isSystemInDarkTheme()) Color(0xFFF2F2F4) else Color(0xFF1C1C1E)
    val vermerk @Composable get() = if (isSystemInDarkTheme()) Color(0xFFA5A5AD) else Color(0xFF5C5C63)
    val aktiv @Composable get() = if (isSystemInDarkTheme()) Color(0xFF5DC88A) else Color(0xFF1E7A3C)
    val pause @Composable get() = if (isSystemInDarkTheme()) Color(0xFF788292) else Color(0xFF5A6470)
    val bereit @Composable get() = if (isSystemInDarkTheme()) Color(0xFFB9AC98) else Color(0xFF6B5536)
    val aktion @Composable get() = if (isSystemInDarkTheme()) Color(0xFFD0574F) else Color(0xFFA32D2D)
    val warnung1 @Composable get() = if (isSystemInDarkTheme()) Color(0xFFF59E0B) else Color(0xFFD97706)
    val warnung2 @Composable get() = if (isSystemInDarkTheme()) Color(0xFFFF5A63) else Color(0xFFE12431)
    val radius = 12.dp

    /** Zeiten mit gleich breiten Ziffern */
    val ziffern = TextStyle(fontFeatureSettings = "tnum")
}

/** Weiße Karte mit runden Ecken und leichtem Schatten, optional mit farbigem Rand (läuft, Warnung, Steckbrief) */
@Composable
fun Modifier.elKarte(rand: Color? = null): Modifier {
    val form = RoundedCornerShape(ELStil.radius)
    val basis = this.fillMaxWidth().shadow(2.dp, form, clip = false).background(ELStil.platte, form)
    return (if (rand != null) basis.border(1.5.dp, rand, form) else basis).padding(14.dp)
}

/** Weicher Status-Chip wie iOS `ELStempel` */
@Composable
fun ELChip(text: String, farbe: Color, gefuellt: Boolean = false) {
    Text(
        text,
        color = if (gefuellt) Color.White else farbe,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(if (gefuellt) farbe else farbe.copy(alpha = 0.14f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** Status aller Teams als Chips, auf jedem Bereich gleich: „1 sucht · 1 Pause · 1 bereit“, Warnungen, Verbindung */
@Composable
fun ELStatusChips(teams: List<ELLage.Team>, live: Boolean? = null) {
    Row(
        Modifier.fillMaxWidth().background(ELStil.platte).horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val sucht = teams.count { it.suche.status == TeamLage.SucheStatus.LAEUFT }
        val pause = teams.count { it.suche.status == TeamLage.SucheStatus.KURZPAUSE }
        val bereit = teams.count { it.suche.status == TeamLage.SucheStatus.BEREIT }
        ELChip("$sucht ${if (sucht == 1) "sucht" else "suchen"}", ELStil.aktiv)
        if (pause > 0) ELChip("$pause Pause", ELStil.pause)
        if (bereit > 0) ELChip("$bereit bereit", ELStil.bereit)
        teams.filter { it.warnstufe > 0 }.forEach { t ->
            ELChip("⚠ ${t.teamName}", if (t.warnstufe >= 2) ELStil.warnung2 else ELStil.warnung1)
        }
        if (live != null) {
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(8.dp).background(if (live) ELStil.aktiv else ELStil.warnung1, CircleShape))
        }
    }
}

/** Kopfleiste der Bereiche Karte, Funk und Vermisst: Titel und Uhr auf Staffelrot, darunter die Status-Chips */
@Composable
fun ELReiterKopf(titel: String, lage: ELLage?) {
    var jetzt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            jetzt = System.currentTimeMillis()
            delay(1000)
        }
    }
    Column {
        Row(
            Modifier.fillMaxWidth().background(ELStil.rot).windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(titel, color = ELStil.aufRot, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (lage?.einsatz?.uebung == true) ELChip("Übung", Color.White)
            Spacer(Modifier.weight(1f))
            val beginn = lage?.einsatz?.beginnMs
            if (beginn != null && lage.einsatz.aktiv) {
                Text(dauerText((jetzt - beginn) / 1000), color = ELStil.aufRot.copy(alpha = 0.8f), style = ELStil.ziffern, fontSize = 13.sp)
            }
            Text(SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(jetzt)), color = ELStil.aufRot, style = ELStil.ziffern,
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        if (lage != null && lage.einsatz.aktiv) ELStatusChips(lage.teams)
    }
}

/** „0:42:05“ */
fun dauerText(sekunden: Long): String {
    val s = sekunden.coerceAtLeast(0)
    return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}
