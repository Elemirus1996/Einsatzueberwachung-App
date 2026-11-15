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

    @Test fun dauerMitStunden() {
        assertEquals("0:00:00", dauerText(-5))
        assertEquals("1:02:03", dauerText(3723))
    }
}
