package io.github.elemirus1996.einsatzspur.kern

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mitteilungen der Einsatzleitung im Hintergrund ohne Push (#79) */
class ELMitteilungenTest {
    private class SpeicherAblage : Ablage {
        val werte = mutableMapOf<String, String?>()
        override fun lesen(schluessel: String) = werte[schluessel]
        override fun schreiben(schluessel: String, wert: String?) { werte[schluessel] = wert }
    }

    private class TestServer : ELMitteilungsSchnittstelle {
        var seite = ELMitteilungSeite("a-0", emptyList())
        var fehler = false
        val abgefragt = mutableListOf<String?>()
        override suspend fun mitteilungen(seit: String?): ELMitteilungSeite {
            abgefragt += seit
            if (fehler) throw SchnittstellenFehler.Server(500)
            return seite
        }
    }

    private val ablage = SpeicherAblage()
    private val einstellungen = ELMitteilungsEinstellungen(ablage)
    private val server = TestServer()
    private val gesendet = mutableListOf<LageMeldung>()
    private val abruf = ELMitteilungsAbruf(server, einstellungen) { gesendet += it }

    private fun m(art: String, stufe: Int, text: String) = ELMitteilung("id-$text", art, stufe, "t1", text)

    @Test
    fun ersterStandNurAusgangspunkt_DannNurNeueUndStandardArten() = runTest {
        abruf.standGemeldet("a-3")
        abruf.standGemeldet("a-3")
        assertTrue("Erster Stand: keine Flut alter Mitteilungen", server.abgefragt.isEmpty())

        server.seite = ELMitteilungSeite("a-8", listOf(
            m("zeitwarnung", 1, "Team Rex: 45 min Suchzeit erreicht"),
            m("zeitwarnung", 2, "Team Rex: 60 min Suchzeit erreicht"),
            m("funk", 1, "Neuer Funkspruch von Team Rex"),
            m("gebiet", 2, "Team Rex: Hund hat das Suchgebiet verlassen"),
            m("unbekannt", 2, "Neue Art eines neueren Servers"),
        ))
        abruf.standGemeldet("a-8")
        assertEquals(listOf<String?>("a-3"), server.abgefragt)
        assertEquals("a-8", abruf.letzterStand)
        assertEquals(listOf("Team Rex: 60 min Suchzeit erreicht", "Team Rex: Hund hat das Suchgebiet verlassen"), gesendet.map { it.titel })
        assertEquals(Tonart.WARNUNG2, gesendet.first().tonart)
    }

    @Test
    fun einstellungenJeArtUndHauptschalter_Gespeichert() = runTest {
        einstellungen.setzen(true, ELMitteilungsArt.FUNK)
        einstellungen.setzen(false, ELMitteilungsArt.GEBIET)
        abruf.standGemeldet("a-1")
        server.seite = ELMitteilungSeite("a-3", listOf(m("funk", 1, "Neuer Funkspruch von Team Rex"), m("gebiet", 2, "Team Rex: Hund hat das Suchgebiet verlassen")))
        abruf.standGemeldet("a-3")
        assertEquals(listOf("Neuer Funkspruch von Team Rex"), gesendet.map { it.titel })
        assertEquals(Tonart.FUNK, gesendet.first().tonart)

        einstellungen.setzenAn(false)
        server.seite = ELMitteilungSeite("a-4", listOf(m("funk", 1, "Neuer Funkspruch von Team Rex")))
        abruf.standGemeldet("a-4")
        assertEquals("Hauptschalter aus: nichts zeigen", 1, gesendet.size)
        assertEquals("a-4", abruf.letzterStand)

        val neu = ELMitteilungsEinstellungen(ablage)
        assertFalse(neu.an.value)
        assertTrue(neu.ist(ELMitteilungsArt.FUNK))
        assertFalse(neu.ist(ELMitteilungsArt.GEBIET))
    }

    @Test
    fun netzfehlerBehaeltStand() = runTest {
        abruf.standGemeldet("a-1")
        server.fehler = true
        abruf.standGemeldet("a-2")
        assertEquals("a-1", abruf.letzterStand)
        server.fehler = false
        server.seite = ELMitteilungSeite("a-2", emptyList())
        abruf.standGemeldet("a-2")
        assertEquals(listOf<String?>("a-1", "a-1"), server.abgefragt)
        assertEquals("a-2", abruf.letzterStand)
    }

    @Test
    fun standardNurKritisch() {
        val an = ELMitteilungsArt.entries.filter { einstellungen.ist(it) }.toSet()
        assertEquals(setOf(ELMitteilungsArt.ZEITWARNUNG2, ELMitteilungsArt.FUNKSTILLE, ELMitteilungsArt.GEBIET, ELMitteilungsArt.FUND), an)
        assertTrue(einstellungen.an.value)
    }

    @Test
    fun jsonLesen() {
        assertEquals("a1-17", TeamClient.steuerAntwort(JSONObject("""{ "mode": "track", "mitteilungStand": "a1-17" }""")).mitteilungStand)
        val seite = ELMitteilungSeite.aus(JSONObject("""{ "stand": "a1-17", "mitteilungen": [
            { "id": "a1-17", "art": "einsatzende", "stufe": 1, "teamId": null, "text": "Einsatz beendet", "zeit": "2026-11-18T10:00:00Z" } ] }"""))
        assertEquals("a1-17", seite.stand)
        assertEquals(ELMitteilung("a1-17", "einsatzende", 1, null, "Einsatz beendet"), seite.mitteilungen.single())
    }
}
