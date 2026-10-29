package io.github.elemirus1996.einsatzspur.kern

import java.time.Instant

/** Was die App als Nächstes tun soll (docs/APP_API.md im Server-Repo, Abschnitt 5). */
enum class Modus {
    TRACK, PAUSE, STOP;

    companion object {
        fun aus(text: String?): Modus? = when (text) {
            "track" -> TRACK
            "pause" -> PAUSE
            "stop" -> STOP
            else -> null
        }
    }
}

/** Antwort von `POST /positions` und `GET /status`. Zeiten in Unix-Millisekunden. */
data class SteuerAntwort(
    val mode: Modus,
    val intervalSec: Int? = null,
    val uploadEverySec: Int? = null,
    val pauseCheckSec: Int? = null,
    val expiresAtMs: Long? = null,
) {
    companion object {
        val STOP = SteuerAntwort(Modus.STOP)
    }
}

/** Eine Position, wie sie hochgeladen wird. `timestamp` ist die Aufnahmezeit in Unix-Millisekunden (UTC). */
data class Position(
    val lat: Double,
    val lng: Double,
    val accuracy: Double? = null,
    val timestamp: Long,
    val battery: Int? = null,
)

/** Zugangsdaten der Rolle Team. Liegen ausschließlich verschlüsselt mit einem Schlüssel aus dem Android Keystore. */
data class TeamSitzung(
    val server: ServerAdresse,
    val token: String,
    val teamId: String?,
    val teamName: String,
    val expiresAtMs: Long,
)

/** ISO 8601 vom Server (UTC mit `Z`, 0 bis 9 Nachkommastellen) in Unix-Millisekunden. */
fun isoZuMs(text: String?): Long? =
    text?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
