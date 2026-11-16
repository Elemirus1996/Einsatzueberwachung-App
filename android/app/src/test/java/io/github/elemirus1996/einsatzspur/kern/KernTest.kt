package io.github.elemirus1996.einsatzspur.kern

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KernTest {
    @Test
    fun qrCodeAusDemMonitor() {
        val qr = TeamQRCode.aus("https://Einsatz.Example.org/team/login/abcdefgh23456789")!!
        assertEquals("ABCDEFGH23456789", qr.code)
        assertEquals("einsatz.example.org", qr.server.name)
        assertEquals("AB12", TeamQRCode.aus("https://einsatz.example.org/team/login?code=ab12")!!.code)
    }

    @Test
    fun ungueltigeQrCodes() {
        listOf(
            "http://einsatz.example.org/team/login/AB12",
            "https://einsatz.example.org/el?code=AB12",
            "https://einsatz.example.org/team/login",
            "https://einsatz.example.org/team/login/AB-12",
            "https://einsatz.example.org/team/login/AB12/zu-viel",
            "https://nutzer:pw@einsatz.example.org/team/login/AB12",
            "kein QR-Code",
        ).forEach { assertNull(it, TeamQRCode.aus(it)) }
    }

    @Test
    fun serverMitPort() {
        val s = ServerAdresse.aus("https://einsatz.intern:8443/irgendwas?x=1")!!
        assertEquals("https://einsatz.intern:8443/api/team-mobile/app/v1/status", s.pfad("api/team-mobile/app/v1/status"))
    }

    @Test
    fun zeitenMitBeliebigenNachkommastellen() {
        listOf("", ".1", ".123", ".1234567").forEach { bruch ->
            val ms = isoZuMs("2026-10-28T20:51:03${bruch}Z")!!
            assertEquals(1_793_220_663_000L + ((("0$bruch").toDouble()) * 1000).toLong(), ms)
        }
        assertNull(isoZuMs("kaputt"))
    }

    @Test
    fun herstellerHinweise() {
        assertTrue(GeraeteHinweise.herstellerHinweis("samsung")!!.startsWith("Samsung"))
        assertTrue(GeraeteHinweise.herstellerHinweis("Xiaomi")!!.contains("Autostart"))
        assertTrue(GeraeteHinweise.herstellerHinweis(" HONOR ")!!.startsWith("Huawei/Honor"))
        assertTrue(GeraeteHinweise.herstellerHinweis("OnePlus")!!.startsWith("OPPO"))
        assertNull(GeraeteHinweise.herstellerHinweis("Google"))
        assertNull(GeraeteHinweise.herstellerHinweis(null))
    }

    @Test
    fun steuerAntwortAusDerBeschreibung() {
        val a = TeamClient.steuerAntwort(
            JSONObject("""{ "mode": "pause", "intervalSec": 20, "uploadEverySec": 60, "pauseCheckSec": 120, "expiresAt": "2026-10-29T10:00:00Z", "accepted": 2 }""")
        )
        assertEquals(SteuerAntwort(Modus.PAUSE, 20, 60, 120, 1_793_268_000_000L), a)
        assertEquals(SteuerAntwort(Modus.TRACK), TeamClient.steuerAntwort(JSONObject("""{ "mode": "track" }""")))
    }
}

class TeamWechselTest {
    @Test
    fun nurTeamwechselBehaeltDenAnmeldeCode() {
        assertTrue(io.github.elemirus1996.einsatzspur.AppModell.Abmeldung.TEAM_WECHSEL.behaeltZugang)
        assertTrue(!io.github.elemirus1996.einsatzspur.AppModell.Abmeldung.EINSATZ.behaeltZugang)
        assertTrue("Einsatzende/410 löscht den Code", !io.github.elemirus1996.einsatzspur.AppModell.Abmeldung.SERVER.behaeltZugang)
    }

    @Test
    fun nurServerendeMeldetEinsatzBeendet() {
        assertTrue(io.github.elemirus1996.einsatzspur.AppModell.Abmeldung.SERVER.meldetEinsatzende)
        assertTrue(!io.github.elemirus1996.einsatzspur.AppModell.Abmeldung.TEAM_WECHSEL.meldetEinsatzende)
        assertTrue(!io.github.elemirus1996.einsatzspur.AppModell.Abmeldung.EINSATZ.meldetEinsatzende)
    }

    @Test
    fun zugangAlsTextHinUndZurueck() {
        val zugang = TeamQRCode.aus("https://einsatz.example.org:8443/team/login/ABCDEFGH23456789")!!
        assertEquals(zugang, TeamQRCode.ausText(zugang.alsText()))
        assertNull(TeamQRCode.ausText("http://unsicher\nABC"))
        assertNull(TeamQRCode.ausText("kaputt"))
    }

    // Idee 6.3: QR-Code der Einsatzleitung aus den Server-Einstellungen
    @Test fun elQrCodeNurMitEl() {
        assertEquals("einsatz.intern", ELQRCode.aus("https://Einsatz.Intern/el")?.server?.name)
        assertEquals("10.10.0.1:8443", ELQRCode.aus("https://10.10.0.1:8443/EL/?x=1")?.server?.name)
        for (text in listOf("http://einsatz.intern/el", "https://einsatz.intern/", "https://einsatz.intern/el/start",
            "https://einsatz.intern/team/login/ABC123", "https://nutzer:pw@einsatz.intern/el", "kein Link")) {
            assertEquals(text, null, ELQRCode.aus(text))
        }
    }

    // Board el-qr-ip: Das Zertifikat gilt nur für den Namen, IP-Adressen lehnt die EL-Anmeldung ab
    @Test fun ipAdresseErkannt() {
        for (text in listOf("https://10.10.0.1", "https://10.10.0.1:8443/el", "https://[fd00::1]/el")) {
            assertTrue(text, ServerAdresse.aus(text)!!.istIpAdresse)
        }
        for (text in listOf("https://el.dein-einsatz.mywire.org", "https://einsatz.intern:8443", "https://10.10.0.1.nip.io")) {
            assertFalse(text, ServerAdresse.aus(text)!!.istIpAdresse)
        }
    }
}
