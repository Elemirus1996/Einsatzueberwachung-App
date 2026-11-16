package io.github.elemirus1996.einsatzspur.kern

import java.net.URI

/** Adresse eines Staffel-Servers. Nur HTTPS, ohne Benutzerdaten, Pfad oder Parameter. */
@JvmInline
value class ServerAdresse private constructor(val basis: String) {

    /** Anzeigename für Bestätigungsdialoge, z. B. „einsatz.example.org“. */
    val name: String get() = basis.removePrefix("https://")

    fun pfad(pfad: String): String = "$basis/${pfad.trimStart('/')}"

    /** Ausdrücklich angegebener Port, sonst `null` (443) */
    val port: Int? get() = basis.substringAfter("://").substringAfter(':', "").toIntOrNull()

    /**
     * IPv4- oder IPv6-Adresse statt Name, wie iOS `istIpAdresse`. Das Zertifikat des Servers gilt nur für den Namen;
     * mit der IP-Adresse scheitert die Verbindung, darum lehnt die EL-Anmeldung sie vorher ab (APP_API „native App“).
     */
    val istIpAdresse: Boolean get() {
        if (name.startsWith("[")) return true
        val teile = name.substringBefore(':').split('.')
        return teile.size == 4 && teile.all { t -> t.isNotEmpty() && t.length <= 3 && t.all(Char::isDigit) && t.toInt() <= 255 }
    }

    companion object {
        const val IP_ADRESSE_HINWEIS = "Bitte den Namen der internen Adresse eintragen, nicht die IP-Adresse. Das Zertifikat des Servers gilt nur für den Namen."

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

/**
 * QR-Code für die Anmeldung der Einsatzleitung aus den Server-Einstellungen: `https://<interne Adresse>/el`,
 * ohne Passwort (APP_API.md „native App“ Abschnitt 4), wie iOS `ELQRCode`. Nur der Server wird übernommen.
 */
data class ELQRCode(val server: ServerAdresse) {
    companion object {
        fun aus(text: String): ELQRCode? {
            val server = ServerAdresse.aus(text) ?: return null
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            val teile = (uri.path ?: "").split('/').filter { it.isNotEmpty() }
            return if (teile.size == 1 && teile[0].equals("el", ignoreCase = true)) ELQRCode(server) else null
        }
    }
}

/** Inhalt des bestehenden Team-QR-Codes: `https://<server>/team/login/<Code>` (ältere Form `…/team/login?code=<Code>`). */
data class TeamQRCode(val server: ServerAdresse, val code: String) {
    /** Für den Keystore-Speicher (Team wechseln ohne neuen Scan). */
    fun alsText(): String = "${server.basis}\n$code"

    companion object {
        fun ausText(text: String): TeamQRCode? {
            val teile = text.split('\n')
            if (teile.size != 2) return null
            val server = ServerAdresse.aus(teile[0]) ?: return null
            return bereinigt(teile[1])?.let { TeamQRCode(server, it) }
        }

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
