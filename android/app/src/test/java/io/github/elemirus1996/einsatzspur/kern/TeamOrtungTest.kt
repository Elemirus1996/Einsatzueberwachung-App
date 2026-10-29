package io.github.elemirus1996.einsatzspur.kern

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestQuelle : OrtungsQuelle {
    override var beiPosition: ((Position) -> Unit)? = null
    var stufe: OrtungsStufe? = null
    val aktiv get() = stufe != null

    override fun starten(stufe: OrtungsStufe) {
        this.stufe = stufe
    }

    override fun beenden() {
        stufe = null
    }
}

class TestPuffer : PositionsSpeicher {
    private val eintraege = mutableListOf<Pair<Long, Position>>()
    private var naechste = 1L

    override fun hinzufuegen(position: Position) {
        eintraege += naechste++ to position
    }

    override fun aelteste(anzahl: Int) = eintraege.take(anzahl)
    override fun loeschenBis(id: Long) {
        eintraege.removeAll { it.first <= id }
    }

    override fun anzahl() = eintraege.size
    override fun leeren() = eintraege.clear()
}

class TestSchnittstelle : TeamSchnittstelle {
    var antwort: Result<SteuerAntwort> = Result.success(SteuerAntwort(Modus.TRACK))
    val gesendet = mutableListOf<List<Position>>()
    var statusAbfragen = 0

    override suspend fun config() = throw SchnittstellenFehler.AntwortUngueltig

    override suspend fun positionenSenden(positionen: List<Position>): SteuerAntwort {
        val ergebnis = antwort.getOrThrow()
        gesendet += positionen
        return ergebnis
    }

    override suspend fun status(): SteuerAntwort {
        statusAbfragen++
        return antwort.getOrThrow()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TeamOrtungTest {
    private val quelle = TestQuelle()
    private val puffer = TestPuffer()
    private val server = TestSchnittstelle()
    private val start = 1_800_000_000_000L

    private fun TestScope.ortung(ablaufMs: Long = 3_600_000, beimBeenden: () -> Unit = {}) =
        TeamOrtung(server, quelle, puffer, start + ablaufMs, backgroundScope, { start + testScheduler.currentTime }, beimBeenden)

    private fun position(ms: Long) = Position(49.31, 8.43, 5.0, ms, 70)

    @Test
    fun trackFeinPauseGrobStopAus() = runTest {
        val o = ortung()
        o.anwenden(SteuerAntwort(Modus.TRACK, intervalSec = 20))
        assertEquals(OrtungsStufe.FEIN, quelle.stufe)
        assertEquals(TeamOrtung.Zustand.ORTET, o.zustand.value)
        o.anwenden(SteuerAntwort(Modus.PAUSE))
        assertEquals("Pause: kein GPS, nur Grob-Ortung", OrtungsStufe.GROB, quelle.stufe)
        assertEquals(TeamOrtung.Zustand.PAUSIERT, o.zustand.value)
        o.anwenden(SteuerAntwort(Modus.TRACK))
        assertEquals("Neue Suche startet ohne Eingabe", OrtungsStufe.FEIN, quelle.stufe)
        o.anwenden(SteuerAntwort.STOP)
        assertNull("stop: Ortungsdienst ganz aus", quelle.stufe)
    }

    @Test
    fun stopIstEndgueltig() = runTest {
        var beendet = 0
        val o = ortung { beendet++ }
        o.anwenden(SteuerAntwort(Modus.TRACK))
        puffer.hinzufuegen(position(start))
        o.anwenden(SteuerAntwort.STOP)
        assertEquals(TeamOrtung.Zustand.BEENDET, o.zustand.value)
        assertEquals(0, puffer.anzahl())
        assertEquals(1, beendet)
        o.anwenden(SteuerAntwort(Modus.TRACK))
        assertFalse("Nach stop startet nichts mehr", quelle.aktiv)
    }

    @Test
    fun ablaufBeendetOhneNetz() = runTest {
        val o = ortung(ablaufMs = 60_000)
        o.anwenden(SteuerAntwort(Modus.TRACK))
        advanceTimeBy(61_000)
        o.positionErhalten(position(start + 61_000))
        assertEquals(TeamOrtung.Zustand.BEENDET, o.zustand.value)
        assertFalse(quelle.aktiv)
    }

    @Test
    fun positionenNachIntervallGedrosselt() = runTest {
        val o = ortung()
        o.anwenden(SteuerAntwort(Modus.TRACK, intervalSec = 20))
        server.antwort = Result.failure(java.io.IOException("Funkloch"))
        o.positionErhalten(position(start))
        o.positionErhalten(position(start + 5_000))
        o.positionErhalten(position(start + 20_000))
        runCurrent()
        assertEquals(2, puffer.anzahl())
    }

    @Test
    fun uploadLeertPufferUndUebernimmtMode() = runTest {
        val o = ortung()
        o.anwenden(SteuerAntwort(Modus.TRACK))
        puffer.hinzufuegen(position(start))
        puffer.hinzufuegen(position(start + 20_000))
        server.antwort = Result.success(SteuerAntwort(Modus.PAUSE))
        o.hochladen()
        assertEquals(listOf(2), server.gesendet.map { it.size })
        assertEquals(0, puffer.anzahl())
        assertEquals(TeamOrtung.Zustand.PAUSIERT, o.zustand.value)
        assertEquals(OrtungsStufe.GROB, quelle.stufe)
    }

    @Test
    fun grosserPufferInPaketenZu500() = runTest {
        val o = ortung()
        o.anwenden(SteuerAntwort(Modus.TRACK))
        repeat(1200) { puffer.hinzufuegen(position(start + it * 1000L)) }
        o.hochladen()
        assertEquals(listOf(500, 500, 200), server.gesendet.map { it.size })
    }

    @Test
    fun funklochBehaeltPositionenMitOriginalzeit() = runTest {
        val o = ortung()
        o.anwenden(SteuerAntwort(Modus.TRACK))
        val p = position(start - 300_000)
        puffer.hinzufuegen(p)
        server.antwort = Result.failure(SchnittstellenFehler.Server(503))
        o.hochladen()
        assertEquals(1, puffer.anzahl())
        server.antwort = Result.success(SteuerAntwort(Modus.TRACK))
        o.hochladen()
        assertEquals(listOf(p), server.gesendet.last())
    }

    @Test
    fun http410IstImmerStop() = runTest {
        val o = ortung()
        o.anwenden(SteuerAntwort(Modus.PAUSE))
        server.antwort = Result.failure(SchnittstellenFehler.Beendet)
        o.statusPruefen()
        assertEquals(TeamOrtung.Zustand.BEENDET, o.zustand.value)
        assertFalse(quelle.aktiv)
    }

    @Test
    fun leererPufferFragtNurStatus() = runTest {
        val o = ortung()
        o.anwenden(SteuerAntwort(Modus.TRACK))
        server.antwort = Result.success(SteuerAntwort(Modus.PAUSE))
        o.hochladen()
        assertEquals(1, server.statusAbfragen)
        assertTrue(server.gesendet.isEmpty())
    }

    /** Ohne Bewegung kommen keine Positionen; der Takt muss das Suchende trotzdem bemerken. */
    @Test
    fun taktOhneBewegungSchaltetAufGrob() = runTest {
        val o = ortung()
        server.antwort = Result.success(SteuerAntwort(Modus.PAUSE))
        o.anwenden(SteuerAntwort(Modus.TRACK, uploadEverySec = 60))
        advanceTimeBy(61_000)
        assertTrue(server.statusAbfragen >= 1)
        assertEquals(TeamOrtung.Zustand.PAUSIERT, o.zustand.value)
        assertEquals(OrtungsStufe.GROB, quelle.stufe)
    }

    /** In der Pause fragt der Takt jede Minute nach und schaltet bei einer neuen Suche auf fein. */
    @Test
    fun pauseTaktBemerktNeueSuche() = runTest {
        val o = ortung()
        server.antwort = Result.success(SteuerAntwort(Modus.PAUSE))
        o.anwenden(SteuerAntwort(Modus.PAUSE, pauseCheckSec = 120))
        advanceTimeBy(30_000)
        assertEquals(0, server.statusAbfragen)
        server.antwort = Result.success(SteuerAntwort(Modus.TRACK))
        advanceTimeBy(31_000)
        assertEquals("höchstens jede Minute, auch wenn der Server 120 s vorgibt", 1, server.statusAbfragen)
        assertEquals(TeamOrtung.Zustand.ORTET, o.zustand.value)
        assertEquals(OrtungsStufe.FEIN, quelle.stufe)
    }

    @Test
    fun pauseSammeltKeinePositionen() = runTest {
        val o = ortung()
        o.anwenden(SteuerAntwort(Modus.PAUSE))
        o.positionErhalten(position(start))
        assertEquals(0, puffer.anzahl())
    }

    @Test
    fun moduswechselWirdGemeldetAusLageNicht() = runTest {
        val o = ortung()
        val gemeldet = mutableListOf<Modus>()
        o.beiModuswechsel = { gemeldet += it }
        o.anwenden(SteuerAntwort(Modus.TRACK), melden = false)
        assertTrue(gemeldet.isEmpty())
        server.antwort = Result.success(SteuerAntwort(Modus.PAUSE))
        o.statusPruefen()
        o.statusPruefen()
        assertEquals(listOf(Modus.PAUSE), gemeldet)
    }
}
