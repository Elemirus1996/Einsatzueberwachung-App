package io.github.elemirus1996.einsatzspur.kern

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val VERMISSTE_JSON = """
{ "szenario": "Mantrailer", "mehrereErlaubt": false,
  "profile": [ {
    "profilId": "p1", "version": "v1", "vollerName": "Erna Muster", "zuletztAktualisiert": "2026-10-29T08:12:00Z",
    "vorname": "Erna", "nachname": "Muster", "alter": "82", "kleidung": "rote Jacke",
    "orientierung": "Eingeschraenkt", "mobilitaet": "Rollator", "suizidrisiko": "Nein", "bewaffnet": "Unbekannt",
    "polizeiVermisstenmeldungAufgenommen": true, "bosRessourcenBesprochen": false,
    "checkliste": { "name": "Mantrailer", "punkte": [
      { "id": "c1", "text": "Geruchsstück gesichert", "typ": "jaNein", "pflicht": true, "auswahl": [], "wert": "true", "erledigt": true },
      { "id": "c2", "text": "Art", "typ": "auswahl", "pflicht": false, "auswahl": ["Kleidung", "Bettwäsche"], "wert": null, "erledigt": false } ] }
  } ] }
"""

private class VermissteAttrappe : ELVermissteSchnittstelle {
    val gesendet = mutableListOf<Triple<String?, String?, JSONObject>>()
    var abrufe = 0
    var fehler: Exception? = null
    override suspend fun vermisste(): ELVermisste {
        abrufe++
        return ELVermisste.aus(JSONObject(VERMISSTE_JSON))
    }
    override suspend fun vermissteSpeichern(id: String, profilId: String?, version: String?, felder: JSONObject): Pair<String, String> {
        fehler?.let { throw it }
        gesendet += Triple(profilId, version, felder)
        return (profilId ?: "neu1") to "v2"
    }
}

class ELVermissteTest {
    @Test fun lesen() {
        val v = ELVermisste.aus(JSONObject(VERMISSTE_JSON))
        assertEquals(ELSzenario.MANTRAILER, v.szenario)
        assertFalse(v.mehrereErlaubt)
        val p = v.profile.single()
        assertEquals("Erna", p.text("vorname"))
        assertEquals("", p.text("medikamente"))   // fehlende Texte leer
        assertTrue(p.schalter("polizeiVermisstenmeldungAufgenommen"))
        assertEquals("Eingeschraenkt", p.auswahl("orientierung"))
        assertEquals(2, p.checkliste!!.punkte.size)
        assertTrue(p.checkliste!!.punkte[0].erledigt)
        assertNull(p.checkliste!!.punkte[1].wert)
        assertEquals(ELSzenario.UNBESTIMMT, ELSzenario.aus("Quatsch"))
    }

    @Test fun nurAenderungenSenden() {
        val alt = ELVermisste.aus(JSONObject(VERMISSTE_JSON)).profile.single()
        val neu = alt.mitText("kleidung", "").mitSchalter("bosRessourcenBesprochen", true).mitAuswahl("mobilitaet", "ZuFuss")
        val a = neu.aenderungen(alt)
        assertEquals(3, a.length())
        assertEquals("", a.getString("kleidung"))   // "" leert das Feld
        assertTrue(a.getBoolean("bosRessourcenBesprochen"))
        assertEquals("ZuFuss", a.getString("mobilitaet"))
        assertEquals(0, alt.aenderungen(alt).length())
    }

    @Test fun neuesProfilOhneKennung() = runTest {
        val attrappe = VermissteAttrappe()
        val ansicht = ELVermissteAnsicht(attrappe)
        val leer = ELVermisstenprofil()
        val gespeichert = ansicht.speichern(leer.mitText("vorname", "Max"), leer)
        val (profilId, version, felder) = attrappe.gesendet.single()
        assertNull(profilId)
        assertNull(version)
        assertEquals("Max", felder.getString("vorname"))
        assertFalse(felder.has("orientierung"))   // unverändert „Unbekannt“
        assertEquals("neu1", gespeichert.profilId)
        assertEquals(1, attrappe.abrufe)   // danach neu geladen
    }

    @Test fun aendernMitVersionUndKonflikt() = runTest {
        val attrappe = VermissteAttrappe()
        val ansicht = ELVermissteAnsicht(attrappe)
        val alt = ELVermisste.aus(JSONObject(VERMISSTE_JSON)).profile.single()
        ansicht.speichern(alt.mitText("alter", "83"), alt)
        assertEquals("p1" to "v1", attrappe.gesendet.single().let { it.first to it.second })

        attrappe.fehler = ELVermissteGeaendert(alt.copy(version = "v9"))
        val e = runCatching { ansicht.speichern(alt.mitText("alter", "84"), alt) }.exceptionOrNull()
        assertEquals("v9", (e as ELVermissteGeaendert).profil.version)
    }

    @Test fun aktionen() {
        val punkt = ELVermisste.aus(JSONObject(VERMISSTE_JSON)).profile.single().checkliste!!.punkte[1]
        val c = ELAktion.checkliste("p1", punkt, wert = "Kleidung")
        assertEquals("vermisste/p1/checkliste", c.pfad)
        assertEquals("c2", c.koerper().getString("punktId"))
        assertEquals("Kleidung", c.koerper().getString("wert"))
        assertFalse(c.koerper().has("erledigt"))
        assertEquals("vermisste/p1/entfernen", ELAktion.vermisstenEntfernen("p1", "Erna").pfad)
        assertEquals("Flaeche", ELAktion.szenario(ELSzenario.FLAECHE).koerper().getString("szenario"))
    }
}
