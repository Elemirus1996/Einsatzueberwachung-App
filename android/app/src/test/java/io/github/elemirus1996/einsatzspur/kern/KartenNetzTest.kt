package io.github.elemirus1996.einsatzspur.kern

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KartenNetzTest {
    @Test
    fun nurKachelserverUeberHttps() {
        assertTrue(KartenNetz.istErlaubt("https", "tile.openstreetmap.org"))
        assertTrue(KartenNetz.istErlaubt("https", "B.tile.opentopomap.org"))
        assertFalse(KartenNetz.istErlaubt("http", "tile.openstreetmap.org"))
        assertFalse(KartenNetz.istErlaubt("https", "events.mapbox.com"))
        assertFalse(KartenNetz.istErlaubt("https", "api.maptiler.com"))
        assertFalse(KartenNetz.istErlaubt("https", "tile.openstreetmap.org.boese.example"))
    }

    @Test
    fun fremderHostWirdOhneSendenAbgewiesen() {
        val vorher = KartenNetz.blockiert.get()
        val antwort = KartenNetz.client("test", protokollieren = false)
            .newCall(okhttp3.Request.Builder().url("https://events.mapbox.com/events/v2").build()).execute()
        assertTrue(antwort.code == 403)
        assertTrue(KartenNetz.blockiert.get() == vorher + 1)
    }
}
