package io.github.elemirus1996.einsatzspur.kern

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val KARTE_JSON = """
{ "suchgebiete": [
    { "id": "g1", "name": "Gebiet A", "farbe": "#2f6fd6", "teamId": "t1", "abgeschlossen": false, "abdeckungProzent": 42.6,
      "punkte": [ { "lat": 49.30, "lng": 8.40 }, { "lat": 49.31, "lng": 8.40 }, { "lat": 49.31, "lng": 8.41 } ] },
    { "id": "g2", "name": "Gebiet B", "farbe": null, "teamId": null, "abgeschlossen": true, "abdeckungProzent": null, "punkte": [] } ],
  "positionen": [
    { "art": "halsband", "id": "HB-7", "name": "Halsband 7", "teamId": "t1", "lat": 49.305, "lng": 8.405, "zeit": "2027-01-15T08:00:00Z", "akku": 72 },
    { "art": "elw", "id": "elw", "name": null, "teamId": null, "lat": 49.29, "lng": 8.39, "zeit": null, "akku": null } ] }
"""

private const val TRACKS_JSON = """
{ "serverZeit": 1761642900000,
  "laufend": [ { "teamId": "t1", "hund": [ { "lat": 49.31, "lng": 8.43, "t": 1000 } ], "mensch": [ { "lat": 49.30, "lng": 8.42, "t": 2000 } ] } ],
  "abgeschlossen": [ { "sucheId": "s1", "teamId": "t2", "beginn": "2027-01-15T06:00:00Z", "ende": "2027-01-15T07:00:00Z",
    "tracks": [ { "art": "hund", "farbe": "#d32f2f", "punkte": [ { "lat": 49.3, "lng": 8.4, "t": 500 } ] } ] } ] }
"""

private class KartenAttrappe : ELKartenSchnittstelle {
    var karten = 0
    val seitAbrufe = mutableListOf<Long?>()
    var neuePunkte = ELTracks(null, emptyList(), emptyList())
    override suspend fun karte(): ELKarte {
        karten++
        return ELKarte.aus(JSONObject(KARTE_JSON))
    }
    override suspend fun tracks(seit: Long?): ELTracks {
        seitAbrufe += seit
        return if (seit == null) ELTracks.aus(JSONObject(TRACKS_JSON)) else neuePunkte
    }
}

class ELKarteModellTest {
    @Test fun karteLesen() {
        val k = ELKarte.aus(JSONObject(KARTE_JSON))
        val a = k.suchgebiete[0]
        assertEquals(3, a.punkte.size)
        assertEquals(42.6, a.abdeckungProzent!!, 0.001)
        assertFalse(a.abgeschlossen)
        val b = k.suchgebiete[1]
        assertTrue(b.abgeschlossen)
        assertNull(b.abdeckungProzent)
        assertNull(b.farbe)
        assertEquals("Halsband 7", k.positionen[0].name)
        assertEquals(72, k.positionen[0].akku)
        assertEquals("elw", k.positionen[1].art)
        assertNull(k.positionen[1].zeitMs)
    }

    @Test fun ohneEinsatzLeer() {
        val k = ELKarte.aus(JSONObject("""{ "suchgebiete": [], "positionen": [] }"""))
        assertTrue(k.suchgebiete.isEmpty() && k.positionen.isEmpty())
    }

    @Test(expected = SchnittstellenFehler.AntwortUngueltig::class)
    fun karteUngueltig() {
        ELKarte.aus(JSONObject("""{ "suchgebiete": [ { "name": "ohne id" } ], "positionen": [] }"""))
    }

    @Test fun tracksLesenUndAnhaengen() {
        val t = ELTracks.aus(JSONObject(TRACKS_JSON))
        assertEquals(1761642900000, t.serverZeitMs)
        assertEquals(2000L, t.letzterZeitpunkt)
        assertEquals("t2", t.abgeschlossen.single().teamId)
        assertEquals("#d32f2f", t.abgeschlossen.single().tracks.single().farbe)

        val neu = ELTracks(null, listOf(
            ELTracks.Laufend("t1", listOf(TeamTracks.Punkt(49.32, 8.44, 3000)), emptyList()),
            ELTracks.Laufend("t3", emptyList(), listOf(TeamTracks.Punkt(49.2, 8.3, 2500))),
        ), emptyList())
        val zusammen = t.anhaengen(neu)
        assertEquals(2, zusammen.laufend.first { it.teamId == "t1" }.hund.size)
        assertEquals(1, zusammen.laufend.first { it.teamId == "t3" }.mensch.size)
        assertEquals(1, zusammen.abgeschlossen.size)
        assertEquals(3000L, zusammen.letzterZeitpunkt)
    }

    @Test fun positionsEreignisMitName() {
        val roh = SseLeser.Ereignis("position",
            """{"art":"el","id":"Tablet ELW","teamId":null,"lat":49.3,"lng":8.4,"zeit":"2027-01-15T08:00:00Z","akku":80}""")
        val p = (ELEreignis.aus(roh) as ELEreignis.Position).position
        assertEquals("el|Tablet ELW", p.schluessel)
        assertNull(p.name)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ELKartenAnsichtTest {
    @Test fun positionBewegtMarkerUndBehaeltNamen() = runTest {
        val ansicht = ELKartenAnsicht(KartenAttrappe(), backgroundScope)
        ansicht.position(ELPosition("handy", "t1", "t1", 1.0, 1.0, null, null))
        assertNull(ansicht.karte.value)   // ohne geladene Karte nichts tun

        ansicht.laden(voll = true)
        ansicht.position(ELPosition("halsband", "HB-7", "t1", 49.4, 8.5, null, 70))
        val hb = ansicht.karte.value!!.positionen.first { it.art == "halsband" }
        assertEquals(49.4, hb.lat, 0.0)
        assertEquals("Halsband 7", hb.name)
        assertEquals(2, ansicht.karte.value!!.positionen.size)

        ansicht.position(ELPosition("handy", "t1", "t1", 49.3, 8.4, null, null))
        assertEquals(3, ansicht.karte.value!!.positionen.size)
    }

    @Test fun taktMitSeitUndRegelmaessigVoll() = runTest {
        val attrappe = KartenAttrappe()
        val ansicht = ELKartenAnsicht(attrappe, backgroundScope, jetzt = { testScheduler.currentTime })
        attrappe.neuePunkte = ELTracks(null, listOf(ELTracks.Laufend("t1", listOf(TeamTracks.Punkt(49.32, 8.44, 3000)), emptyList())), emptyList())
        ansicht.starten()
        runCurrent()
        assertEquals(listOf<Long?>(null), attrappe.seitAbrufe)
        assertEquals(1, attrappe.karten)

        advanceTimeBy(ELKartenAnsicht.TRACK_TAKT_MS + 1)
        assertEquals(2000L, attrappe.seitAbrufe.last())
        assertEquals(2, ansicht.tracks.value!!.laufend.single().hund.size)

        // Nach 5 Minuten wieder ohne seit
        advanceTimeBy(ELKartenAnsicht.TRACK_VOLL_NACH_MS)
        assertTrue(attrappe.seitAbrufe.drop(1).contains(null))

        ansicht.anhalten()
        val anzahl = attrappe.seitAbrufe.size
        advanceTimeBy(ELKartenAnsicht.TRACK_TAKT_MS * 3)
        assertEquals(anzahl, attrappe.seitAbrufe.size)
    }

    @Test fun lageGeaendertNurBeiOffenerKarte() = runTest {
        val attrappe = KartenAttrappe()
        val ansicht = ELKartenAnsicht(attrappe, backgroundScope)
        ansicht.lageGeaendert()
        runCurrent()
        assertEquals(0, attrappe.karten)
        ansicht.starten()
        runCurrent()
        ansicht.lageGeaendert()
        runCurrent()
        assertEquals(2, attrappe.karten)
        ansicht.anhalten()
    }
}
