package io.github.elemirus1996.einsatzspur.kern

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ELEinsatzTest {
    @Test fun vorlageLesen() {
        val v = ELVorlage.aus(JSONObject("""
            { "aktiv": false, "szenarien": ["Mantrailer", "Flaeche", "Unbestimmt"],
              "divera": [ { "id": 42, "titel": "Person vermisst", "ort": "Testwald", "lat": 49.3, "lng": 8.4, "zeit": "2026-10-31T17:00:00Z" } ],
              "einsatzleiter": ["Anna Berg"], "fuehrungsassistenten": [],
              "ergebnisse": [ { "wert": "Person gefunden", "anzeige": "Person gefunden" } ],
              "kartenarten": [ { "wert": "Streets", "anzeige": "Straßenkarte" } ] }
        """))
        assertFalse(v.aktiv)
        assertEquals(listOf(ELSzenario.MANTRAILER, ELSzenario.FLAECHE), v.szenarioWahl)
        assertEquals(42, v.divera.single().id)
        assertEquals(49.3, v.divera.single().lat!!, 0.0)
        assertEquals("Anna Berg", v.einsatzleiter.single())
        assertEquals(listOf("Streets" to "Straßenkarte"), v.kartenWahl)
        // Ohne Listen vom Server: Standardwerte
        val leer = ELVorlage.aus(JSONObject("{}"))
        assertEquals(4, leer.szenarioWahl.size)
        assertEquals(3, leer.kartenWahl.size)
        assertTrue(leer.divera.isEmpty())
    }

    @Test fun startVollstaendig() {
        assertFalse(ELEinsatzStart(ort = "Wald").vollstaendig)   // Szenario fehlt
        assertFalse(ELEinsatzStart(szenario = ELSzenario.FLAECHE, ort = "  ").vollstaendig)
        assertTrue(ELEinsatzStart(szenario = ELSzenario.FLAECHE, ort = "Wald").vollstaendig)
        assertTrue(ELEinsatzStart(szenario = ELSzenario.FLAECHE, diveraAlarmId = 42).vollstaendig)   // Ort aus dem Alarm
    }

    @Test fun startAktion() {
        val a = ELAktion.einsatzStart(ELEinsatzStart(stichwort = " ", ort = "Testwald ", szenario = ELSzenario.TRUEMMER,
            uebung = true, einsatzleiter = "Anna", lat = 49.3, lng = 8.4, diveraAlarmId = 42))
        val k = a.koerper()
        assertEquals("einsatz/start", a.pfad)
        assertEquals("Übung starten", a.beschreibung)
        assertEquals("Truemmer", k.getString("szenario"))
        assertTrue(k.getBoolean("uebung"))
        assertEquals("Testwald", k.getString("ort"))
        assertFalse(k.has("stichwort"))   // leer nicht senden
        assertFalse(k.has("fuehrungsassistent"))
        assertEquals(8.4, k.getDouble("lng"), 0.0)
        assertEquals(42, k.getInt("diveraAlarmId"))
    }

    @Test fun beendenAktion() {
        val a = ELAktion.einsatzBeenden("Person gefunden", mitTracks = false, kartenart = "Topographic", teamIds = listOf("t2", "t1"),
            endzeitMs = 1_793_468_700_123L, bemerkungen = "  ", mitPunkten = true)
        val k = a.koerper()
        assertEquals("einsatz/beenden", a.pfad)
        assertTrue(k.getBoolean("ressourcenGeprueft"))
        assertEquals("2026-10-31T17:45:00Z", k.getString("endzeit"))   // UTC, ohne Sekundenbruchteile
        assertFalse(k.has("bemerkungen"))
        val pdf = k.getJSONObject("pdf")
        assertFalse(pdf.getBoolean("tracks"))
        assertTrue(pdf.getBoolean("punkte"))
        assertEquals("Topographic", pdf.getString("kartenart"))
        assertEquals("t2", pdf.getJSONArray("teamIds").getString(0))
        // Alle Teams: teamIds null, ohne Endzeit
        val alle = ELAktion.einsatzBeenden("Person gefunden", mitTracks = true).koerper()
        assertTrue(alle.getJSONObject("pdf").isNull("teamIds"))
        assertFalse(alle.has("endzeit"))
    }

    @Test fun endeLesen() {
        val ok = ELEinsatzEnde.aus("""{ "archivId": 17, "einsatzNummer": "2026-031", "pdf": { "erstellt": true } }""")
        assertEquals("17", ok.archivId)
        assertEquals("Einsatz 2026-031 beendet und archiviert. Der Bericht (PDF) liegt im Archiv.", ok.meldung)
        val fehlt = ELEinsatzEnde.aus("""{ "archivId": "a1", "pdf": { "erstellt": false, "fehler": "Karte nicht geladen" } }""")
        assertTrue(fehlt.meldung.contains("nicht erstellt: Karte nicht geladen"))
        val kaputt = ELEinsatzEnde.aus("kein json")
        assertNull(kaputt.archivId)
        assertEquals("Einsatz beendet und archiviert.", kaputt.meldung)
    }
}
