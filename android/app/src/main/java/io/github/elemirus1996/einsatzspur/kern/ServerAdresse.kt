package io.github.elemirus1996.einsatzspur.kern

import java.net.URI

/** Adresse eines Staffel-Servers. Nur HTTPS, ohne Benutzerdaten, Pfad oder Parameter. */
@JvmInline
value class ServerAdresse private constructor(val basis: String) {

    /** Anzeigename für Bestätigungsdialoge, z. B. „einsatz.example.org“. */
    val name: String get() = basis.removePrefix("https://")

    fun pfad(pfad: String): String = "$basis/${pfad.trimStart('/')}"

    companion object {
        fun aus(text: String): ServerAdresse? {
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            if (uri.rawUserInfo != null) return null
            val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            val port = if (uri.port > 0) ":${uri.port}" else ""
            return ServerAdresse("https://$host$port")
        }
    }
}

/** Inhalt des bestehenden Team-QR-Codes: `https://<server>/team/login/<Code>` (ältere Form `…/team/login?code=<Code>`). */
data class TeamQRCode(val server: ServerAdresse, val code: String) {
    companion object {
        fun aus(text: String): TeamQRCode? {
            val server = ServerAdresse.aus(text) ?: return null
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            val teile = (uri.path ?: "").split('/').filter { it.isNotEmpty() }
            if (teile.size < 2 || !teile[0].equals("team", true) || !teile[1].equals("login", true)) return null
            val roh = when (teile.size) {
                3 -> teile[2]
                2 -> uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("code=") }?.removePrefix("code=")
                else -> null
            }
            return bereinigt(roh)?.let { TeamQRCode(server, it) }
        }

        /** Wie auf der Anmeldeseite: Leerzeichen raus, Großbuchstaben. Nur Buchstaben und Ziffern. */
        fun bereinigt(code: String?): String? {
            val ergebnis = (code ?: "").filterNot { it.isWhitespace() }.uppercase()
            if (ergebnis.isEmpty() || ergebnis.length > 64) return null
            if (!ergebnis.all { it in 'A'..'Z' || it in '0'..'9' }) return null
            return ergebnis
        }
    }
}
