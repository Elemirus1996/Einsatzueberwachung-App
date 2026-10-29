package io.github.elemirus1996.einsatzspur.kern

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** QR-Codes aus dem Helligkeitsbild der Kamera lesen (ZXing-Core, nur lokal, ohne Netz). */
object QrAuswertung {
    private val hinweise = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /** Text des QR-Codes im Bild oder `null`. [helligkeit] ist die Y-Ebene, Zeilenlänge [zeilenSchritt]. */
    fun lesen(helligkeit: ByteArray, breite: Int, hoehe: Int, zeilenSchritt: Int = breite): String? {
        val quelle = PlanarYUVLuminanceSource(helligkeit, zeilenSchritt, hoehe, 0, 0, breite, hoehe, false)
        return try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(quelle)), hinweise).text
        } catch (e: NotFoundException) {
            null
        } catch (e: ReaderException) {
            null
        }
    }

    /** Wie [lesen], aber nur Team-QR-Codes (`https://…/team/login/<Code>`). Fremde Codes → [Ergebnis.Fremd]. */
    fun teamCode(helligkeit: ByteArray, breite: Int, hoehe: Int, zeilenSchritt: Int = breite): Ergebnis {
        val text = lesen(helligkeit, breite, hoehe, zeilenSchritt) ?: return Ergebnis.Nichts
        return TeamQRCode.aus(text)?.let { Ergebnis.Team(it) } ?: Ergebnis.Fremd
    }

    sealed interface Ergebnis {
        data object Nichts : Ergebnis
        data object Fremd : Ergebnis
        data class Team(val zugang: TeamQRCode) : Ergebnis
    }
}
