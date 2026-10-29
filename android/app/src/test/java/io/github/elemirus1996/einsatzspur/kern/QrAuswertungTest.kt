package io.github.elemirus1996.einsatzspur.kern

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Test

class QrAuswertungTest {
    /** QR-Code mit ZXing erzeugen und als Helligkeitsbild (wie die Y-Ebene der Kamera) wieder lesen. */
    private fun bild(text: String, groesse: Int = 300): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, groesse, groesse)
        return ByteArray(groesse * groesse) { i -> if (matrix[i % groesse, i / groesse]) 0 else 255.toByte() }
    }

    @Test
    fun teamQrCodeAusDemMonitor() {
        val ergebnis = QrAuswertung.teamCode(bild("https://einsatz.example.org/team/login/ABCDEFGH23456789"), 300, 300)
        assertEquals(
            QrAuswertung.Ergebnis.Team(TeamQRCode(ServerAdresse.aus("https://einsatz.example.org")!!, "ABCDEFGH23456789")),
            ergebnis,
        )
    }

    @Test
    fun fremderCodeUndLeeresBild() {
        assertEquals(QrAuswertung.Ergebnis.Fremd, QrAuswertung.teamCode(bild("http://einsatz.example.org/team/login/AB12"), 300, 300))
        assertEquals(QrAuswertung.Ergebnis.Fremd, QrAuswertung.teamCode(bild("WIFI:S:Staffel;P:geheim;;"), 300, 300))
        assertEquals(QrAuswertung.Ergebnis.Nichts, QrAuswertung.teamCode(ByteArray(300 * 300) { 127 }, 300, 300))
    }

    @Test
    fun zeilenSchrittGroesserAlsBreite() {
        val b = bild("https://einsatz.example.org/team/login/AB12CD", 200)
        val mitRand = ByteArray(256 * 200) { 255.toByte() }
        for (y in 0 until 200) System.arraycopy(b, y * 200, mitRand, y * 256, 200)
        assertEquals("AB12CD", (QrAuswertung.teamCode(mitRand, 200, 200, 256) as QrAuswertung.Ergebnis.Team).zugang.code)
    }
}
