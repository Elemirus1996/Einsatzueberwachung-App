package io.github.elemirus1996.einsatzspur.kern

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapAblage : Ablage {
    val werte = mutableMapOf<String, String>()
    override fun lesen(schluessel: String) = werte[schluessel]
    override fun schreiben(schluessel: String, wert: String?) {
        if (wert == null) werte.remove(schluessel) else werte[schluessel] = wert
    }
}

val BASIS_LAGE = TeamLage(
    mode = Modus.PAUSE, serverZeitMs = 1_800_000_100_000, einsatzText = "Übung", uebung = true, staffel = null,
    teamId = "t1", teamName = "Team Rex", dogName = "Rex", hundefuehrerName = null, helfer = emptyList(),
    suche = TeamLage.Suche(TeamLage.SucheStatus.BEREIT), suchgebiet = null,
    halsband = Halsband(id = "HB-7", name = "Halsband 7", lat = 49.0, lng = 8.0, zeitMs = 1_800_000_090_000),
    mitteilungen = listOf(Mitteilung("m1", "Hallo", 1_800_000_000_000)),
)

class TestAnsichtSchnittstelle(var lageAntwort: Result<TeamLage> = Result.success(BASIS_LAGE)) : TeamAnsichtSchnittstelle {
    var meldungFehler: Exception? = null
    val meldungen = mutableListOf<Pair<String, Statusmeldung>>()
    val trackAbrufe = mutableListOf<Long?>()
    var offenerStrom = false

    var lageAbrufe = 0
    override suspend fun lage(): TeamLage {
        lageAbrufe++
        return lageAntwort.getOrThrow()
    }
    override suspend fun tracks(seit: Long?): TeamTracks {
        trackAbrufe += seit
        return TeamTracks(emptyList(), emptyList(), emptyList())
    }

    var sucheAntwort: Result<SuchAntwort> = Result.success(SuchAntwort(Modus.TRACK, TeamLage.Suche(TeamLage.SucheStatus.LAEUFT, darfSteuern = true)))
    val aktionen = mutableListOf<Pair<String, Suchaktion>>()
    override suspend fun suche(id: String, aktion: Suchaktion): SuchAntwort {
        aktionen += id to aktion
        return sucheAntwort.getOrThrow()
    }

    override suspend fun statusmeldung(id: String, meldung: Statusmeldung) {
        meldungen += id to meldung
        meldungFehler?.let { throw it }
    }

    override fun ereignisse(): Flow<TeamEreignis> = if (offenerStrom) flow { awaitCancellation() } else emptyFlow()
}

@OptIn(ExperimentalCoroutinesApi::class)
class TeamAnsichtTest {
    private val ablage = MapAblage()
    private val server = TestAnsichtSchnittstelle()
    private val jetzt = 1_800_000_000_000L
    private val modi = mutableListOf<Modus>()
    private val gemeldet = mutableListOf<LageMeldung>()

    private fun TestScope.ansicht(lageTaktMs: Long = 60_000) =
        TeamAnsicht(server, "t1", ablage, backgroundScope, { jetzt }, lageTaktMs) { modi += it }
            .also { it.beiMeldungen = { m -> gemeldet += m } }

    @Test
    fun lageGibtModusWeiterUndErsteLageMeldetNichts() = runTest {
        val a = ansicht()
        a.neuLaden()
        assertEquals(listOf(Modus.PAUSE), modi)
        assertTrue(gemeldet.isEmpty())
        assertEquals(listOf<Long?>(null), server.trackAbrufe)
        assertEquals(10_000L, a.halsbandAlterMs(jetzt))
    }

    /** Entscheidung Server #133: Kurzpause liefert mode = track; die Ortung folgt nur dem Modus und bleibt fein. */
    @Test
    fun kurzpauseMitTrackBleibtFein() = runTest {
        val quelle = TestQuelle()
        val ortung = TeamOrtung(TestSchnittstelle(), quelle, TestPuffer(), jetzt + 3_600_000, backgroundScope, { jetzt }) {}
        val a = TeamAnsicht(server, "t1", ablage, backgroundScope, { jetzt }) { ortung.anwenden(SteuerAntwort(it), melden = false) }
        server.lageAntwort = Result.success(BASIS_LAGE.copy(mode = Modus.TRACK, suche = TeamLage.Suche(TeamLage.SucheStatus.KURZPAUSE)))
        a.neuLaden()
        assertEquals(OrtungsStufe.FEIN, quelle.stufe)
        assertEquals(TeamOrtung.Zustand.ORTET, ortung.zustand.value)
    }

    @Test
    fun meldungImFunklochBleibtUndGehtMitGleicherIdRaus() = runTest {
        val a = ansicht()
        server.meldungFehler = SchnittstellenFehler.Server(503)
        a.melden(Statusmeldung.FUNK)
        assertEquals(1, a.offeneMeldungen.value.size)
        val neu = ansicht()   // App neu gestartet
        assertEquals(1, neu.offeneMeldungen.value.size)
        server.meldungFehler = null
        neu.meldungenSenden()
        assertTrue(neu.offeneMeldungen.value.isEmpty())
        assertEquals(server.meldungen[0].first, server.meldungen[1].first)
    }

    @Test
    fun abgelehnteMeldungNichtEndlosUnd410IstStop() = runTest {
        val a = ansicht()
        server.meldungFehler = SchnittstellenFehler.Server(400)
        a.melden(Statusmeldung.PAUSE)
        assertTrue(a.offeneMeldungen.value.isEmpty())
        server.meldungFehler = SchnittstellenFehler.Beendet
        a.melden(Statusmeldung.BEENDET)
        assertEquals(listOf(Modus.STOP), modi)
    }

    @Test
    fun mitteilungUeberEreignisUndLageNurEinmal() = runTest {
        val a = ansicht()
        a.neuLaden()
        val neu = Mitteilung("m2", "Bitte Funkkontakt", jetzt)
        a.verarbeiten(TeamEreignis.NeueMitteilung(neu))
        server.lageAntwort = Result.success(BASIS_LAGE.copy(mitteilungen = BASIS_LAGE.mitteilungen + neu))
        a.neuLaden()
        assertEquals(listOf(LageMeldung("Mitteilung der Einsatzleitung", "Bitte Funkkontakt")), gemeldet)
        a.alsGelesen(neu)
        assertEquals(listOf("m1"), a.ungelesen.map { it.id })
        a.vergessen()
        assertNull(ablage.lesen("team.t1.gelesen"))
    }

    @Test
    fun halsbandEreignisBehaeltKennung() = runTest {
        val a = ansicht()
        a.neuLaden()
        a.verarbeiten(TeamEreignis.HalsbandPosition(Halsband(lat = 49.1, lng = 8.1, zeitMs = jetzt)))
        a.neuLaden()
        assertTrue(gemeldet.isEmpty())
        assertEquals("HB-7", a.lage.value?.halsband?.id)
    }

    @Test
    fun stopNurMit410AlsEnde() = runTest {
        val a = ansicht()
        a.verarbeiten(TeamEreignis.Stop)
        assertTrue("200 → verdrängt, nicht beenden", Modus.STOP !in modi)
        server.lageAntwort = Result.failure(SchnittstellenFehler.Beendet)
        a.verarbeiten(TeamEreignis.Stop)
        assertEquals(Modus.STOP, modi.last())
    }

    /** Fehler aus dem iPhone-Test: Live steht, aber das lage-Ereignis fehlt. Der Takt holt die Lage trotzdem. */
    @Test
    fun verpasstesLageEreignisDurchTaktAufgefangen() = runTest {
        server.offenerStrom = true
        val a = ansicht(lageTaktMs = 60_000)
        a.starten()
        runCurrent()
        assertTrue(a.live.value)
        server.lageAntwort = Result.success(BASIS_LAGE.copy(mode = Modus.TRACK, suche = TeamLage.Suche(TeamLage.SucheStatus.LAEUFT, dauerSec = 0)))
        advanceTimeBy(61_000)
        a.anhalten()
        assertEquals(Modus.TRACK, modi.last())
        assertEquals(listOf(LageMeldung("Suche gestartet – Ortung läuft")), gemeldet)
    }

    @Test
    fun nachVergessenKeineAbrufeMehr() = runTest {
        val a = ansicht()
        a.hintergrund()
        a.vergessen()
        val vorher = server.lageAbrufe
        advanceTimeBy(3_600_000)
        assertEquals(vorher, server.lageAbrufe)
    }

    @Test
    fun hintergrundHoltLageJedeMinute() = runTest {
        val a = ansicht()
        a.neuLaden()
        a.hintergrund()
        server.lageAntwort = Result.success(BASIS_LAGE.copy(mitteilungen = BASIS_LAGE.mitteilungen + Mitteilung("m3", "Neu", jetzt)))
        advanceTimeBy(61_000)
        a.anhalten()
        assertEquals("Mitteilung der Einsatzleitung", gemeldet.single().titel)
        assertEquals("im Hintergrund keine Laufwege", 1, server.trackAbrufe.size)
    }
}

class TeamLageTest {
    @Test
    fun lageAusDerBeschreibung() {
        val lage = TeamLage.aus(JSONObject("""
            { "mode": "track", "serverZeit": "2026-10-28T09:15:00Z",
              "einsatz": { "text": "Vermisste Person · Testwald", "uebung": false, "staffel": "RHS Musterstadt" },
              "team": { "teamId": "t1", "teamName": "Team Rex", "dogName": "Rex", "hundefuehrerName": "Anna", "helfer": ["Ben"] },
              "suche": { "status": "laeuft", "seit": "2026-10-28T08:40:00Z", "dauerSec": 2100 },
              "suchgebiet": { "id": "g", "name": "Gebiet A", "farbe": "#2f6fd6", "punkte": [ { "lat": 49.31, "lng": 8.43 } ] },
              "halsband": { "id": "HB-7", "name": "Halsband 7", "lat": 49.312, "lng": 8.431, "zeit": "2026-10-28T09:14:48.1234567Z", "akku": 72 },
              "mitteilungen": [ { "id": "m1", "text": "Bitte Funkkontakt", "zeit": "2026-10-28T09:10:00Z" } ] }
        """))
        assertEquals(TeamLage.SucheStatus.LAEUFT, lage.suche.status)
        assertEquals(listOf("Ben"), lage.helfer)
        assertEquals(72, lage.halsband?.akku)
        assertEquals("Gebiet A", lage.suchgebiet?.name)
    }

    @Test
    fun lageOhneGebietUndHalsband() {
        val lage = TeamLage.aus(JSONObject("""
            { "einsatz": { "text": "Übung", "uebung": true }, "team": { "teamId": "t1", "teamName": "Team Rex" },
              "suche": { "status": "bereit", "seit": null, "dauerSec": 0 }, "suchgebiet": null, "halsband": null, "mitteilungen": [] }
        """))
        assertNull(lage.suchgebiet)
        assertNull(lage.halsband)
        assertTrue(lage.uebung)
    }

    @Test
    fun sseEreignisseAusDerBeschreibung() {
        val leser = SseLeser()
        val strom = "retry: 5000\n\nevent: halsband\ndata: {\"lat\":49.312,\"lng\":8.431,\"zeit\":\"2026-10-28T09:14:48Z\",\"akku\":72}\n\n" +
            ": ping\n\nevent: lage\ndata: {}\n\nevent: verdraengt\ndata: {\"wiederholenNachSec\":60}\n\nevent: stop\ndata: {}\n\n"
        val ereignisse = strom.split("\n").mapNotNull { leser.zeile(it) }.mapNotNull(TeamEreignis::aus)
        assertEquals(4, ereignisse.size)
        assertNotNull((ereignisse[0] as TeamEreignis.HalsbandPosition).halsband.akku)
        assertEquals(TeamEreignis.Lage, ereignisse[1])
        assertEquals(TeamEreignis.Verdraengt(60), ereignisse[2])
        assertEquals(TeamEreignis.Stop, ereignisse[3])
    }

    @Test
    fun meldungenBeiAenderung() {
        val neu = BASIS_LAGE.copy(
            suche = TeamLage.Suche(TeamLage.SucheStatus.LAEUFT),
            suchgebiet = TeamLage.Suchgebiet("g2", "Gebiet B", null, emptyList()),
            halsband = BASIS_LAGE.halsband?.copy(id = "HB-9", name = "Halsband 9"),
        )
        assertEquals(
            listOf(
                LageMeldung("Suche gestartet – Ortung läuft"),
                LageMeldung("Neues Suchgebiet: Gebiet B"),
                LageMeldung("Halsband zugewiesen: Halsband 9"),
            ),
            LageMeldung.vergleichen(BASIS_LAGE, neu),
        )
        assertTrue(LageMeldung.vergleichen(BASIS_LAGE, BASIS_LAGE.copy(halsband = BASIS_LAGE.halsband?.copy(lat = 49.5))).isEmpty())
    }
}


@OptIn(ExperimentalCoroutinesApi::class)
class TeamsteuerungTest {
    private val ablage = MapAblage()
    private val server = TestAnsichtSchnittstelle(Result.success(BASIS_LAGE.copy(suche = TeamLage.Suche(TeamLage.SucheStatus.BEREIT, darfSteuern = true))))
    private val modi = mutableListOf<Modus>()

    private fun TestScope.ansicht() = TeamAnsicht(server, "t1", ablage, backgroundScope, { 1_800_000_000_000L }) { modi += it }

    @Test
    fun nurGueltigeUebergaenge() {
        assertEquals(listOf(Suchaktion.STARTEN), Suchaktion.moeglich(TeamLage.SucheStatus.BEREIT))
        assertEquals(listOf(Suchaktion.PAUSE, Suchaktion.BEENDEN), Suchaktion.moeglich(TeamLage.SucheStatus.LAEUFT))
        assertEquals(listOf(Suchaktion.WEITER, Suchaktion.BEENDEN), Suchaktion.moeglich(TeamLage.SucheStatus.KURZPAUSE))
    }

    @Test
    fun startenGibtModusSofortWeiter() = runTest {
        val a = ansicht()
        a.neuLaden()
        a.steuern(Suchaktion.STARTEN)
        assertEquals(Modus.TRACK, modi.last())
        assertEquals(TeamLage.SucheStatus.LAEUFT, a.lage.value?.suche?.status)
        assertTrue(a.offeneAktionen.value.isEmpty())
    }

    @Test
    fun abgelehntUndVerboten() = runTest {
        val a = ansicht()
        a.neuLaden()
        server.sucheAntwort = Result.failure(SuchFehler.Abgelehnt("Suche läuft bereits", TeamLage.Suche(TeamLage.SucheStatus.LAEUFT, darfSteuern = true)))
        a.steuern(Suchaktion.STARTEN)
        assertEquals(TeamLage.SucheStatus.LAEUFT, a.lage.value?.suche?.status)
        assertEquals("Suche läuft bereits", a.suchHinweis.value?.first)
        server.sucheAntwort = Result.failure(SuchFehler.Verboten(null))
        a.steuern(Suchaktion.PAUSE)
        assertTrue(!a.darfSteuern)
    }

    @Test
    fun funklochWiederholtMitGleicherId() = runTest {
        val a = ansicht()
        a.neuLaden()
        server.sucheAntwort = Result.failure(java.io.IOException("Funkloch"))
        a.steuern(Suchaktion.STARTEN)
        assertEquals(1, a.offeneAktionen.value.size)
        val neu = ansicht()
        assertEquals(1, neu.offeneAktionen.value.size)
        server.sucheAntwort = Result.success(SuchAntwort(Modus.TRACK, TeamLage.Suche(TeamLage.SucheStatus.LAEUFT, darfSteuern = true)))
        neu.neuLaden()
        assertTrue(neu.offeneAktionen.value.isEmpty())
        assertEquals(server.aktionen[0].first, server.aktionen[1].first)
    }

    @Test
    fun darfSteuernAusDerLage() {
        assertTrue(TeamLage.suche(JSONObject("""{ "status": "laeuft", "dauerSec": 0, "darfSteuern": true }""")).darfSteuern)
        assertTrue("älterer Server: keine Steuerung", !TeamLage.suche(JSONObject("""{ "status": "bereit" }""")).darfSteuern)
    }
}
