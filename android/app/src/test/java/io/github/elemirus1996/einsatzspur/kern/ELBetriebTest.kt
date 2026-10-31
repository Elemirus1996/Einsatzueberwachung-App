package io.github.elemirus1996.einsatzspur.kern

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TestELAktionen : ELAktionsSchnittstelle {
    var fehler: Exception? = null
    var antwort = """{"doppelt":false}"""
    val ausgefuehrt = mutableListOf<ELAktion>()
    var notizListe = emptyList<ELNotiz>()
    override suspend fun ausfuehren(aktion: ELAktion): String {
        ausgefuehrt += aktion
        fehler?.let { throw it }
        return antwort
    }
    override suspend fun notizen(vor: String?) = ELNotizSeite(notizListe, false)
    override suspend fun halsbaender() = ELHalsbaender.aus(JSONObject("""{ "frei": [ { "id": "HB-1", "name": "Halsband 1", "akku": 80 } ], "zuletzt": [ { "teamId": "t1", "halsbandId": "HB-1" } ] }"""))
}

class ELBetriebTest {
    private val ablage = MapAblage()
    private val server = TestELAktionen()
    private var gestoppt = false
    private fun betrieb() = ELBetrieb(server, ablage, jetzt = { 1_800_000_000_000 }) { gestoppt = true }

    @Test fun koerperMitIdUndNull() {
        val a = ELAktion.teamStart("t1", "Team Rex", null)
        assertEquals("teams/t1/start", a.pfad)
        val k = a.koerper()
        assertEquals(a.id, k.getString("id"))
        assertTrue("ohne Halsband = null", k.isNull("halsbandId"))
        val f = ELAktion.funk("Kommen", "t1", "n7").koerper()
        assertEquals("n7", f.getString("antwortAuf"))
        assertEquals(a, ELAktion.aus(a.alsJson()))
    }

    @Test fun funklochPuffertUndWiederholtMitDerselbenId() = runTest {
        server.fehler = IOException()
        val b = betrieb()
        b.ausfuehren(ELAktion.teamPause("t1", "Team Rex"))
        assertEquals(1, b.offen.value.size)
        // Bleibt auch nach Neustart der App erhalten
        val nachNeustart = betrieb()
        assertEquals(1, nachNeustart.offen.value.size)
        server.fehler = null
        nachNeustart.senden()
        assertTrue(nachNeustart.offen.value.isEmpty())
        assertEquals("dieselbe id", server.ausgefuehrt[0].id, server.ausgefuehrt[1].id)
        assertEquals("Team Rex: Pause ✓", nachNeustart.hinweis.value?.text)
    }

    @Test fun ablehnungZeigenUndNichtWiederholen() = runTest {
        server.fehler = ELAktionAbgelehnt("Halsband vergeben")
        val b = betrieb()
        var neuGeladen = 0
        b.nachAktion = { neuGeladen++ }
        b.ausfuehren(ELAktion.teamStart("t1", "Team Rex", "HB-1"))
        assertTrue(b.offen.value.isEmpty())
        assertEquals("Halsband vergeben", b.hinweis.value?.text)
        assertTrue(b.hinweis.value!!.fehler)
        assertEquals(1, neuGeladen)
    }

    @Test fun beendetStopptDieRolle() = runTest {
        server.fehler = SchnittstellenFehler.Beendet
        betrieb().ausfuehren(ELAktion.notiz("x", null))
        assertTrue(gestoppt)
    }

    @Test fun vergessenLeertAlles() = runTest {
        server.fehler = IOException()
        val b = betrieb()
        b.ausfuehren(ELAktion.notiz("x", null))
        b.vergessen()
        assertTrue(betrieb().offen.value.isEmpty())
    }

    @Test fun notizenUndHalsbaenderLesen() {
        val seite = ELNotizSeite.aus(JSONObject("""{ "notizen": [ { "id": "n1", "art": "funk", "text": "Kommen", "zeit": "2026-10-29T20:00:00Z",
            "von": "Team Rex", "teamId": "t1", "loeschbar": false, "antworten": [ { "id": "a1", "text": "Verstanden", "zeit": "2026-10-29T20:01:00Z" } ] } ],
            "weitere": true }"""))
        assertTrue(seite.weitere)
        val n = seite.notizen.single()
        assertEquals("funk", n.art)
        assertFalse(n.loeschbar)
        assertEquals("Verstanden", n.antworten.single().text)
        val h = runCatching { kotlinx.coroutines.runBlocking { server.halsbaender() } }.getOrThrow()
        assertEquals("HB-1", h.zuletzt["t1"])
        assertEquals(80, h.frei.single().akku)
    }

    @Test fun clientFehlerZuordnen() = runTest {
        val sitzung = ELSitzung(ServerAdresse.aus("https://einsatz.intern")!!, "t", 1_900_000_000_000)
        fun client(status: Int, text: String) = ELClient(ELZugang(sitzung), transport = { _, _, _, _ -> status to text })
        val a = ELAktion.teamPause("t1", "Team Rex")
        assertEquals("""{"doppelt":true}""", client(200, """{"doppelt":true}""").ausfuehren(a))
        assertEquals("Team läuft schon", (runCatching { client(409, """{"error":"Team läuft schon"}""").ausfuehren(a) }.exceptionOrNull() as ELAktionAbgelehnt).text)
        assertEquals("Nicht gefunden.", (runCatching { client(404, "") .ausfuehren(a) }.exceptionOrNull() as ELAktionAbgelehnt).text)
        assertEquals(SchnittstellenFehler.Beendet, runCatching { client(410, "").ausfuehren(a) }.exceptionOrNull())
        assertEquals(SchnittstellenFehler.Server(503), runCatching { client(503, "").ausfuehren(a) }.exceptionOrNull())
    }
}
