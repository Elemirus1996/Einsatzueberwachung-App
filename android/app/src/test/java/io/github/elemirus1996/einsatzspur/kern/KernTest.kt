package io.github.elemirus1996.einsatzspur.kern

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun steuerAntwortAusDerBeschreibung() {
        val a = TeamClient.steuerAntwort(
            JSONObject("""{ "mode": "pause", "intervalSec": 20, "uploadEverySec": 60, "pauseCheckSec": 120, "expiresAt": "2026-10-29T10:00:00Z", "accepted": 2 }""")
        )
        assertEquals(SteuerAntwort(Modus.PAUSE, 20, 60, 120, 1_793_268_000_000L), a)
        assertEquals(SteuerAntwort(Modus.TRACK), TeamClient.steuerAntwort(JSONObject("""{ "mode": "track" }""")))
    }
}
