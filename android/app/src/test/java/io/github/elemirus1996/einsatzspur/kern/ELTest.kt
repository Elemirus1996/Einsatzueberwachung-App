package io.github.elemirus1996.einsatzspur.kern

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

private val SERVER = ServerAdresse.aus("https://einsatz.intern")!!
private const val JETZT = 1_800_000_000_000L
private const val STUNDE = 3_600_000L

private const val LAGE_JSON = """
{ "mode": "track", "serverZeit": "2027-01-15T08:00:00Z",
  "einsatz": { "aktiv": true, "text": "Vermisste Person · Testwald", "szenario": "Flaeche", "uebung": true, "beginn": "2027-01-15T07:00:00Z", "einsatzleiter": "Anna" },
  "teams": [ { "teamId": "t1", "teamName": "Team Rex", "dogName": "Rex", "hundefuehrerName": "Anna", "helfer": ["Ben"],
    "suche": { "status": "laeuft", "seit": "2027-01-15T07:30:00Z", "dauerSec": 2100 },
    "warnstufe": 1, "warnung1Min": 45, "warnung2Min": 60,
    "suchgebiet": { "id": "g1", "name": "Gebiet A", "farbe": "#2f6fd6" },
    "halsband": { "id": "HB-7", "name": "Halsband 7", "zeit": "2027-01-15T07:59:00Z", "akku": 72 },
    "handy": null, "pause": { "erforderlichBis": "2027-01-15T09:00:00Z" } } ],
  "wetter": { "temperatur": 12.5, "wind": 14, "beschreibung": "Bewölkt", "zeit": "2027-01-15T07:00:00Z" } }
"""

private fun sitzung(token: String, restStunden: Double = 5.0, bis: Long? = null) =
    ELSitzung(SERVER, token, JETZT + (restStunden * STUNDE).toLong(), geraet = "Tablet ELW", verlaengerbarBisMs = bis)

class ELModellTest {
    @Test fun lageLesen() {
        val lage = ELLage.aus(JSONObject(LAGE_JSON))
        assertEquals(Modus.TRACK, lage.mode)
        assertTrue(lage.einsatz.aktiv && lage.einsatz.uebung)
        val t = lage.teams.single()
        assertEquals(TeamLage.SucheStatus.LAEUFT, t.suche.status)
        assertEquals(2100, t.suche.dauerSec)
        assertEquals(1, t.warnstufe)
        assertEquals("Gebiet A", t.suchgebiet?.name)
        assertEquals(72, t.halsband?.akku)
        assertNull(t.handy)
        assertEquals(12.5, lage.wetter?.temperatur)
        val ohne = ELLage.aus(JSONObject("""{ "mode": "pause", "einsatz": { "aktiv": false }, "teams": [], "wetter": null }"""))
        assertFalse(ohne.einsatz.aktiv)
        assertNull(ohne.wetter)
    }

    @Test fun ereignisseLesen() {
        val leser = SseLeser()
        val roh = "event: warnung\ndata: {\"teamId\":\"t1\",\"stufe\":2,\"text\":\"Team Rex 60 min\"}\n\n" +
            "event: position\ndata: {\"art\":\"handy\",\"id\":\"t1\",\"teamId\":\"t1\",\"lat\":49.3,\"lng\":8.4,\"zeit\":\"2027-01-15T08:00:00Z\",\"akku\":50}\n\n" +
            "event: einsatz\ndata: {\"aktiv\":false}\n\nevent: stop\ndata: {}\n\n"
        val e = roh.split("\n").mapNotNull { leser.zeile(it) }.mapNotNull(ELEreignis::aus)
        assertEquals(ELEreignis.Warnung("t1", 2, "Team Rex 60 min"), e[0])
        assertEquals(50, (e[1] as ELEreignis.Position).position.akku)
        assertEquals(ELEreignis.Einsatz(false), e[2])
        assertEquals(ELEreignis.Stop, e[3])
    }

    @Test fun sitzungUebersteht() {
        val s = sitzung("e3.abc", bis = JETZT + 72 * STUNDE)
        assertEquals(s, ELSitzung.ausText(s.alsText()))
    }

    @Test fun anmeldungFehlerFallweise() = runTest {
        suspend fun mit(status: Int, text: String = "{}") =
            ELAnmeldung(SERVER) { _, _, _, _ -> status to text }.anmelden("geheim", "Tablet")
        val (s, _) = mit(200, """{ "token": "e3.x", "expiresAt": "2027-01-15T10:00:00Z" }""")
        assertEquals("e3.x", s.token)
        assertEquals("Tablet", s.geraet)
        for ((status, fehler) in listOf(401 to ELAnmeldeFehler.PasswortFalsch, 409 to ELAnmeldeFehler.KeinPasswort,
            404 to ELAnmeldeFehler.NichtErreichbar, 429 to ELAnmeldeFehler.Gesperrt(300))) {
            val e = runCatching { mit(status, """{ "wiederholenNachSec": 300 }""") }.exceptionOrNull()
            assertEquals(fehler, e)
        }
    }
}

class TestELLage(var antwort: Result<ELLage>) : ELLageSchnittstelle {
    var abrufe = 0
    override suspend fun lage(): ELLage {
        abrufe++
        return antwort.getOrThrow()
    }
    override fun ereignisse(): Flow<ELEreignis> = emptyFlow()
}

@OptIn(ExperimentalCoroutinesApi::class)
class ELAnsichtTest {
    private val lage = ELLage.aus(JSONObject(LAGE_JSON))

    @Test fun stopNurWennLageEsBestaetigt() = runTest {
        val server = TestELLage(Result.success(lage))
        val modi = mutableListOf<Modus>()
        val a = ELAnsicht(server, this) { modi += it }
        a.verarbeiten(ELEreignis.Stop)
        assertEquals(listOf(Modus.TRACK), modi)
        server.antwort = Result.failure(SchnittstellenFehler.Beendet)
        a.verarbeiten(ELEreignis.Stop)
        assertEquals(Modus.STOP, modi.last())
    }

    @Test fun warnungUndSuchdauer() = runTest {
        var zeit = JETZT
        val a = ELAnsicht(TestELLage(Result.success(lage)), this, jetzt = { zeit }) {}
        var stufe = 0
        a.beiWarnung = { stufe = it }
        a.verarbeiten(ELEreignis.Warnung("t1", 2, "60 min"))
        assertEquals(2, stufe)
        assertEquals("60 min", a.warnung.value?.text)
        a.neuLaden()
        zeit += 30_000
        assertEquals(2130L, a.suchdauerSec(lage.teams[0], zeit))
    }

    @Test fun einsatzEndeLaedtLageUndSchaltetPause() = runTest {
        val server = TestELLage(Result.success(lage))
        val modi = mutableListOf<Modus>()
        val a = ELAnsicht(server, this) { modi += it }
        server.antwort = Result.success(lage.copy(mode = Modus.PAUSE, einsatz = ELLage.Einsatz(aktiv = false), teams = emptyList()))
        a.verarbeiten(ELEreignis.Einsatz(false))
        assertEquals(listOf(Modus.PAUSE), modi)
        assertFalse(a.lage.value!!.einsatz.aktiv)
    }
}

/** Einsatzleitung ohne Einsatz: Ortung ganz aus, auch keine Grob-Ortung (entschieden 29.10.2026). */
class ELOrtungTest {
    @Test fun pauseOhneOrtung() = runTest {
        val quelle = TestQuelle()
        val o = TeamOrtung(TestSchnittstelle(), quelle, TestPuffer(), JETZT + STUNDE, this, jetzt = { JETZT },
            pauseOhneOrtung = true) {}
        o.anwenden(SteuerAntwort(Modus.TRACK))
        assertEquals(OrtungsStufe.FEIN, quelle.stufe)
        o.anwenden(SteuerAntwort(Modus.PAUSE))
        assertNull("kein GPS und keine Grob-Ortung", quelle.stufe)
        assertEquals(TeamOrtung.Zustand.PAUSIERT, o.zustand.value)
        o.ablaufSetzen(JETZT + 24 * STUNDE)
        assertEquals(JETZT + 24 * STUNDE, o.steuerung.expiresAtMs)
        o.anwenden(SteuerAntwort.STOP)
    }
}

class TestVerlaengern(var antwort: Result<ELSitzung>) : ELVerlaengerSchnittstelle {
    val aufrufe = mutableListOf<String?>()
    override suspend fun verlaengern(geraet: String?): ELSitzung {
        aufrufe += geraet
        return antwort.getOrThrow()
    }
}

class ELVerlaengerungTest {
    private fun v(zugang: ELZugang, server: TestVerlaengern, gespeichert: MutableList<ELSitzung> = mutableListOf()) =
        ELVerlaengerung(server, zugang, jetzt = { JETZT }, uhrzeit = { "17:45" }) { gespeichert += it }

    @Test fun nurBeiLaufendemEinsatzUndWenigerAls6Stunden() {
        val z = ELZugang(sitzung("alt", restStunden = 7.0))
        val v = v(z, TestVerlaengern(Result.failure(java.io.IOException())))
        assertFalse("noch 7 h", v.faellig(true))
        z.ersetzen(sitzung("alt", 5.0))
        assertTrue(v.faellig(true))
        assertFalse("ohne Einsatz nicht", v.faellig(false))
        z.ersetzen(sitzung("alt", 5.0, bis = JETZT + 5 * STUNDE))
        assertFalse("72 h erreicht", v.faellig(true))
        z.ersetzen(sitzung("alt", -1.0))
        assertFalse("abgelaufen", v.faellig(true))
    }

    @Test fun neuesTokenErsetztUndWirdGespeichert() = runTest {
        val z = ELZugang(sitzung("alt"))
        val neu = sitzung("neu", 24.0)
        val server = TestVerlaengern(Result.success(neu))
        val gespeichert = mutableListOf<ELSitzung>()
        val v = v(z, server, gespeichert)
        v.pruefen(true)
        assertEquals(listOf<String?>("Tablet ELW"), server.aufrufe)
        assertEquals("neu", z.sitzung.token)
        assertEquals(listOf(neu), gespeichert)
        assertFalse(z.verlaengerungLaeuft)
        v.pruefen(true)
        assertEquals(1, server.aufrufe.size)
    }

    @Test fun ablehnungNichtWiederholenUndHinweis() = runTest {
        val z = ELZugang(sitzung("alt"))
        val server = TestVerlaengern(Result.failure(ELVerlaengerAbgelehnt("Kein Einsatz läuft.")))
        val v = v(z, server)
        v.pruefen(true)
        v.pruefen(true)
        assertEquals("409 heißt nicht wiederholen", 1, server.aufrufe.size)
        assertEquals("Anmeldung läuft um 17:45 ab.", v.hinweis.value)
        assertEquals("alt", z.sitzung.token)
    }

    @Test fun funklochWirdWiederholt() = runTest {
        val z = ELZugang(sitzung("alt"))
        val server = TestVerlaengern(Result.failure(java.io.IOException()))
        val v = v(z, server)
        v.pruefen(true)
        server.antwort = Result.success(sitzung("neu", 24.0))
        v.pruefen(true)
        assertEquals(2, server.aufrufe.size)
        assertEquals("neu", z.sitzung.token)
        assertNull(v.hinweis.value)
    }
}

/** 410 bzw. `stop` mit dem alten Token ist keine Abmeldung, wenn schon ein neueres da ist (#141). */
@OptIn(ExperimentalCoroutinesApi::class)
class ELTokenWechselTest {
    private val neu = sitzung("neu", 24.0)
    private val lageText = """{ "mode": "track", "einsatz": { "aktiv": true }, "teams": [] }"""

    @Test fun altes410MitNeuemTokenWiederholen() = runTest {
        val z = ELZugang(sitzung("alt"))
        val tokens = mutableListOf<String?>()
        val client = ELClient(z, transport = { _, _, _, token ->
            tokens += token
            if (token == "alt") {
                z.ersetzen(neu)
                410 to """{"mode":"stop"}"""
            } else 200 to lageText
        })
        assertTrue(client.lage().einsatz.aktiv)
        assertEquals(listOf<String?>("alt", "neu"), tokens)
    }

    @Test fun wartetAufLaufendeVerlaengerung() = runTest {
        val z = ELZugang(sitzung("alt"))
        z.verlaengerungBeginnt()
        val client = ELClient(z, transport = { _, _, _, token -> if (token == "alt") 410 to "{}" else 200 to lageText })
        launch {
            advanceTimeBy(300)
            z.ersetzen(neu)
            z.verlaengerungEndet()
        }
        assertTrue(client.lage().einsatz.aktiv)
    }

    @Test fun echtes410BleibtStop() = runTest {
        var anfragen = 0
        val client = ELClient(ELZugang(sitzung("alt")), transport = { _, _, _, _ ->
            anfragen++
            410 to "{}"
        })
        assertEquals(SchnittstellenFehler.Beendet, runCatching { client.lage() }.exceptionOrNull())
        assertEquals(1, anfragen)
    }

    private fun strom(text: String) = BufferedReader(StringReader(text))

    @Test fun stopImLiveStromMitAltemTokenIstKeineAbmeldung() = runTest {
        val z = ELZugang(sitzung("alt"))
        val client = ELClient(z, sse = { _, _ ->
            z.ersetzen(neu)
            SseVerbindung(200, strom("event: team\ndata: {\"teamId\":\"t1\"}\n\nevent: stop\ndata: {}\n\n")) {}
        })
        assertEquals(listOf<ELEreignis>(ELEreignis.Team("t1")), client.ereignisse().toList())
    }

    @Test fun stopOhneNeuesTokenKommtAn() = runTest {
        val client = ELClient(ELZugang(sitzung("alt")), sse = { _, _ -> SseVerbindung(200, strom("event: stop\ndata: {}\n\n")) {} })
        assertEquals(listOf<ELEreignis>(ELEreignis.Stop), client.ereignisse().toList())
    }

    @Test fun verbindenMitAltemTokenNachVerlaengerung() = runTest {
        val z = ELZugang(sitzung("alt"))
        val tokens = mutableListOf<String>()
        val client = ELClient(z, sse = { _, token ->
            tokens += token
            if (token == "alt") {
                z.ersetzen(neu)
                SseVerbindung(410, null) {}
            } else SseVerbindung(200, strom("event: einsatz\ndata: {\"aktiv\":true}\n\n")) {}
        })
        assertEquals(listOf<ELEreignis>(ELEreignis.Einsatz(true)), client.ereignisse().toList())
        assertEquals(listOf("alt", "neu"), tokens)
    }
}
