package io.github.elemirus1996.einsatzspur.kern

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Abschluss beim Einsatzende, Rollen, Fund-Punkt (Server 1.17.0), wie die iOS-Tests */
class PolizeiberichtTest {
    private val json = """
        {"uebung":false,"alarmiertDurch":"Polizei","keinePolizei":false,"vorgangsnummer":"","melder":"PI Nord",
         "polizei":{"name":"","dienstnummer":"","telefon":""},
         "fund":{"zeit":null,"lat":null,"lng":null,"ort":"","uebergabeAn":"","teamId":null,
                 "vorschlag":{"lat":49.3,"lng":8.4,"zeit":"2026-11-17T10:00:00Z","teamId":"t1"}},
         "teams":[{"teamId":"t1","teamName":"Team Rex","einsatzart":null,"erlaubt":["Flaeche","Mantrailer"]}],
         "trails":[{"sucheId":"s1","teamId":"t1","teamName":"Team Rex","abbruchgrund":"","geruchsartikel":""}],
         "abbruchgruende":["Spur verloren"],"fehlend":["vorgangsnummer","polizei","teams","trails"]}
    """.trimIndent()

    @Test fun abschlussFehlendUndJson() {
        val a = ELAbschluss.aus(JSONObject(json))
        assertEquals(listOf(ELEinsatzart.FLAECHE, ELEinsatzart.MANTRAILER), a.teams.first().wahl)
        var e = ELAbschlussEingabe.aus(a)
        assertEquals(listOf("Vorgangsnummer der Polizei", "Polizeikontakt", "Fundzeit und Fundort", "„Eingesetzt als“ je Team",
            "Abbruchgrund und Geruchsartikel je Trail"), e.fehlend(a, fund = true))
        e = e.copy(keinePolizei = true).vorschlagUebernehmen(a.fund.vorschlag!!)
        e = e.copy(einsatzart = mapOf("t1" to ELEinsatzart.MANTRAILER), abbruchgrund = mapOf("s1" to "Spur verloren"),
            geruchsartikel = mapOf("s1" to "Schal"))
        assertTrue(e.fehlend(a, fund = true).isEmpty())
        assertEquals("t1", e.fundTeamId)

        val o = e.json(a, fund = true, jetztMs = Long.MAX_VALUE)
        assertTrue(o.getBoolean("keinePolizei"))
        assertEquals("Polizei", o.getString("alarmiertDurch"))
        assertEquals("2026-11-17T10:00:00Z", o.getJSONObject("fund").getString("zeit"))
        assertEquals("Mantrailer", o.getJSONArray("teams").getJSONObject(0).getString("einsatzart"))

        // Übung: nie Polizei, nie Art
        val uebung = ELAbschlussEingabe.aus(a).copy(uebung = true, alarmiertDurch = null)
        assertFalse(uebung.polizeiGefragt)
        assertFalse(uebung.fehlend(a, fund = false).contains("Alarmiert durch"))
    }

    @Test fun beendenMitAbschnittenUndAbfrage() {
        val a = ELAktion.einsatzBeenden("x", mitTracks = false, abschnitte = listOf("teams", "funk"), unvollstaendigBestaetigt = true)
        val inhalt = a.koerper().toString()
        assertTrue(ELEinsatzEnde.abfrage(inhalt).contains("abschnitte=teams,funk"))
        assertTrue(JSONObject(inhalt).getBoolean("unvollstaendigBestaetigt"))
    }

    @Test fun startMitAlarmierung() {
        val a = ELAktion.einsatzStart(ELEinsatzStart(alarmiertDurch = ELAlarmiertDurch.ANDERE_STAFFEL, melder = " RHS Nord "))
        val k = a.koerper()
        assertEquals("AndereStaffel", k.getString("alarmiertDurch"))
        assertEquals("RHS Nord", k.getString("melder"))
    }

    @Test fun rolleAusLage() {
        val lesen = ELLage.aus(JSONObject("""{"einsatz":{"aktiv":true},"teams":[],"ich":{"personId":"p1","name":"Carla","rolle":"lesen"}}"""))
        assertFalse(lesen.darfSteuern)
        assertEquals(ELRolle.LESEN, lesen.ich?.rollenWert)
        assertTrue(ELLage.aus(JSONObject("""{"einsatz":{"aktiv":true},"teams":[]}""")).darfSteuern)   // älterer Server
    }

    @Test fun meldungEinsatzAngelegtNurBeimWechsel() {
        val aus = ELLage.Einsatz(aktiv = false)
        val an = ELLage.Einsatz(aktiv = true, stichwort = "Vermisste Person", ort = "Stadtwald")
        assertNull(LageMeldung.einsatzAngelegt(null, an))
        assertNull(LageMeldung.einsatzAngelegt(an, an))
        assertEquals(LageMeldung("Einsatz angelegt", "Vermisste Person · Stadtwald"), LageMeldung.einsatzAngelegt(aus, an))
    }

    @Test fun fundPunktTyp() {
        assertEquals("fund", TeamPunkt("i", "Person gefunden", 1.0, 2.0, null, 0L, "fund").json().getString("typ"))
        assertEquals("hinweis", TeamPunkt("i", "Spur", 1.0, 2.0, null, 0L).json().getString("typ"))
    }
}
