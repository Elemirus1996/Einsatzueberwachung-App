package io.github.elemirus1996.einsatzspur.kern

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

private const val ERKLAERUNG = """{ "version": "3f9c0a7b12de4e55", "titel": "Datenschutzerklärung RHS Musterstadt",
  "text": "## 1. Wer?\nDie Staffel.\n\n- Standort\n- Akku", "stand": "2026-10-31T09:00:00Z" }"""

class DatenschutzTest {
    private fun angabe(feld: String?) = DatenschutzAngabe.aus(JSONObject("""{ "teams": [] ${feld?.let { ", \"datenschutz\": $it" } ?: ""} }"""))

    @Test fun dreiFaelle() {
        assertEquals("älterer Server: nicht fragen", DatenschutzAngabe.Fehlt, angabe(null))
        assertEquals("keine hinterlegt: Hinweis", DatenschutzAngabe.Keine, angabe("null"))
        val d = (angabe(ERKLAERUNG) as DatenschutzAngabe.Erklaerung).datenschutz
        assertEquals("3f9c0a7b12de4e55", d.version)
        assertTrue(d.vollstaendig && d.standMs != null)
    }

    @Test fun loginTeamsUndLage() = runTest {
        val auswahl = TeamAnmeldung.auswahlAus(JSONObject("""{ "teams": [], "server": { "kennung": "k1", "staffel": "RHS", "logoVersion": "a1" }, "datenschutz": $ERKLAERUNG }"""))
        assertEquals("k1", auswahl.server?.kennung)
        assertTrue(auswahl.datenschutz is DatenschutzAngabe.Erklaerung)
        val lage = ELLage.aus(JSONObject("""{ "einsatz": { "aktiv": false }, "teams": [], "datenschutz": { "version": "v2" } }"""))
        assertEquals("v2", lage.datenschutzVersion)
        assertNull(ELLage.aus(JSONObject("""{ "einsatz": { "aktiv": false }, "teams": [], "datenschutz": null }""")).datenschutzVersion)
    }

    @Test fun bestaetigungJeKennungUndVersion() {
        val s = DatenschutzSpeicher(MapAblage())
        assertTrue(s.offen("k1", "v1"))
        assertFalse("ohne Version nichts fragen", s.offen("k1", null))
        s.bestaetigen("k1", "v1")
        assertFalse(s.offen("k1", "v1"))
        assertTrue("neue Version", s.offen("k1", "v2"))
        assertTrue("anderer Server", s.offen("k2", "v1"))
        s.merken(Datenschutz.aus(JSONObject(ERKLAERUNG))!!, "k1")
        s.merken(Datenschutz("nur-version"), "k2")
        assertEquals(setOf("k1"), s.alle().keys)
        assertEquals("Datenschutzerklärung RHS Musterstadt", s.alle()["k1"]?.titel)
    }

    @Test fun markdownZerlegen() {
        val b = DatenschutzBlock.zerlegen("# Titel\nErste Zeile\nzweite Zeile\n\n## 2. Daten\n- **Standort** nur bei Suche\n* Akku\n\nEnde")
        assertEquals(listOf(
            DatenschutzBlock.Ueberschrift("Titel", 1), DatenschutzBlock.Absatz("Erste Zeile zweite Zeile"),
            DatenschutzBlock.Ueberschrift("2. Daten", 2), DatenschutzBlock.Punkt("**Standort** nur bei Suche"),
            DatenschutzBlock.Punkt("Akku"), DatenschutzBlock.Absatz("Ende"),
        ), b)
    }

    @Test fun elAnmeldungLiefertErklaerungUndKennung() = runTest {
        val a = ELAnmeldung(ServerAdresse.aus("https://einsatz.intern")!!) { _, _, _, _ ->
            200 to """{ "token": "e3.x", "expiresAt": "2026-11-01T10:00:00Z", "server": { "kennung": "k1" }, "datenschutz": $ERKLAERUNG }"""
        }.anmelden("p", null)
        assertEquals("k1", a.sitzung.kennung)
        assertEquals("3f9c0a7b12de4e55", (a.datenschutz as DatenschutzAngabe.Erklaerung).datenschutz.version)
        assertEquals(a.sitzung, ELSitzung.ausText(a.sitzung.alsText()))
    }

    @Test fun abrufen404HeisstKeine() = runTest {
        assertNull(datenschutzAbrufen("https://x/datenschutz", "t") { _, _, _, _ -> 404 to "" })
        assertEquals("v", datenschutzAbrufen("https://x/datenschutz", "t") { _, _, _, _ -> 200 to """{ "version": "v", "text": "x" }""" }?.version)
        assertEquals(SchnittstellenFehler.Beendet,
            runCatching { datenschutzAbrufen("https://x/datenschutz", "t") { _, _, _, _ -> 410 to "" } }.exceptionOrNull())
    }
}

class HeimatStaffelTest {
    private val ablage = MapAblage()
    private val datei = File.createTempFile("logo", ".png").also { it.delete() }
    private val info = ServerInfo("k1", "RHS Musterstadt", "a1")

    @Test fun fragenUebernehmenAblehnen() {
        val h = HeimatStaffel(ablage, datei)
        assertTrue(h.sollFragen(info))
        h.ablehnen(ServerInfo("fremd", null, null))
        assertFalse("abgelehnter Server", h.sollFragen(ServerInfo("fremd", null, null)))
        h.uebernehmen(info, "RHS Musterstadt")
        assertFalse("schon gewählt", h.sollFragen(ServerInfo("k2", null, null)))
        assertEquals("RHS Musterstadt", HeimatStaffel(ablage, datei).eintrag.value?.staffel)
        h.entfernen()
        assertNull(h.eintrag.value)
        assertTrue("nach Entfernen wieder fragen", h.sollFragen(ServerInfo("fremd", null, null)))
    }

    @Test fun logoNurBeiNeuerVersion() = runTest {
        val h = HeimatStaffel(ablage, datei)
        h.uebernehmen(info, "RHS")
        var abrufe = 0
        val png = byteArrayOf(1, 2, 3)
        h.aktualisieren(info) { abrufe++; png }
        h.aktualisieren(info) { abrufe++; png }
        assertEquals(1, abrufe)
        assertArrayEquals(png, h.logo.value)
        assertEquals("Name vom Server", "RHS Musterstadt", h.eintrag.value?.staffel)
        assertArrayEquals("bleibt nach Neustart", png, HeimatStaffel(ablage, datei).logo.value)
        // Fremde Staffel ändert nichts
        h.aktualisieren(ServerInfo("k2", "Andere", "b")) { abrufe++; png }
        assertEquals(1, abrufe)
        // Netzfehler: Version nicht merken, beim nächsten Mal erneut
        h.aktualisieren(info.copy(logoVersion = "a2")) { throw IOException() }
        assertEquals("a1", h.eintrag.value?.logoVersion)
        // Logo entfernt
        h.aktualisieren(info.copy(logoVersion = null)) { png }
        assertNull(h.logo.value)
        assertFalse(datei.exists())
    }
}
