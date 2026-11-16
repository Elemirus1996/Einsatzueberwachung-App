package io.github.elemirus1996.einsatzspur.ui

import io.github.elemirus1996.einsatzspur.kern.ELNotiz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gestaltung Punkt 9: Funk als Chat, eigene Einträge rechts (wie iOS) */
class ELFunkChatTest {
    private fun notiz(von: String?, loeschbar: Boolean) = ELNotiz("n1", "funk", "x", 0L, von, null, emptyList(), loeschbar)

    @Test fun eigeneEintraegeRechts() {
        assertTrue(vonLeitung(notiz("Einsatzleitung", false)))
        assertTrue(vonLeitung(notiz("Tablet EL", true)))
        assertFalse(vonLeitung(notiz("Team Rex", false)))
        assertFalse(vonLeitung(notiz(null, false)))
    }

    private fun status(text: String, teamId: String? = "t1") = ELNotiz("n2", "status", text, 0L, "Team Bravo", teamId, emptyList(), false)

    @Test fun teamMeldungenAlsBlase() {
        assertEquals("Im Suchgebiet eingetroffen", teamMeldung(status("[Team Bravo] im Suchgebiet eingetroffen")))
        assertEquals("Funkkontakt benötigt", teamMeldung(status("[Team Bravo] Funkkontakt benötigt")))
        assertTrue(dringend("Funkkontakt benötigt"))
        assertFalse(dringend("im Suchgebiet eingetroffen"))
        // Protokoll bleibt Zeile
        assertEquals(null, teamMeldung(status("[Team Bravo] hat die Suche gestartet (App)")))
        assertEquals(null, teamMeldung(status("Timer gestartet")))
        assertEquals(null, teamMeldung(status("[Team Bravo] im Suchgebiet eingetroffen", teamId = null)))
    }

    @Test fun funkAnTeamOhneVorsatz() {
        assertEquals("Team Bravo" to "Bravo, bitte melden", anTeam("@Team Bravo Bravo, bitte melden", listOf("Team Alpha", "Team Bravo")))
        assertEquals(null, anTeam("Alle Teams: Sammelpunkt", listOf("Team Bravo")))
    }

    @Test fun dauerMitStunden() {
        assertEquals("0:00:00", dauerText(-5))
        assertEquals("1:02:03", dauerText(3723))
    }
}
