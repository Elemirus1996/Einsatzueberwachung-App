package io.github.elemirus1996.einsatzspur.kern

import org.json.JSONObject

/**
 * Datenschutzerklärung der Staffel vom Server (APP_API.md Team-Rolle Abschnitt 12, Server #148), wie iOS `Datenschutz.swift`.
 * In `config`/`lage` kommt nur `version`, bei der Anmeldung und mit `GET datenschutz` das ganze Objekt.
 */
data class Datenschutz(val version: String, val titel: String? = null, val text: String? = null, val standMs: Long? = null) {
    val vollstaendig: Boolean get() = text != null

    fun alsJson(): JSONObject = JSONObject().apply {
        put("version", version)
        titel?.let { put("titel", it) }
        text?.let { put("text", it) }
        standMs?.let { put("standMs", it) }
    }

    companion object {
        fun aus(json: JSONObject): Datenschutz? {
            val version = json.textOderNull("version") ?: return null
            return Datenschutz(version, json.textOderNull("titel"), json.textOderNull("text"),
                isoZuMs(json.textOderNull("stand")) ?: if (json.has("standMs")) json.optLong("standMs") else null)
        }

        /** Nur die Version aus `config`/`lage`; `null`, wenn keine Erklärung hinterlegt ist. */
        fun version(json: JSONObject): String? = json.objektOderNull("datenschutz")?.textOderNull("version")
    }
}

/** Was der Server zur Erklärung sagt: Feld fehlt (älterer Server), `null` (keine hinterlegt) oder eine Erklärung. */
sealed class DatenschutzAngabe {
    data object Fehlt : DatenschutzAngabe()
    data object Keine : DatenschutzAngabe()
    data class Erklaerung(val datenschutz: Datenschutz) : DatenschutzAngabe()

    companion object {
        fun aus(json: JSONObject?): DatenschutzAngabe {
            if (json == null || !json.has("datenschutz")) return Fehlt
            if (json.isNull("datenschutz")) return Keine
            return json.optJSONObject("datenschutz")?.let(Datenschutz::aus)?.let(::Erklaerung) ?: Fehlt
        }
    }
}

/** Offene Abfrage „Zur Kenntnis genommen“; `erklaerung = null`: nur der Hinweis, dass die Staffel keine hinterlegt hat. */
data class DatenschutzFrage(val kennung: String, val erklaerung: Datenschutz?)

/** Bestätigungen und zuletzt gezeigte Erklärungen, nur auf dem Gerät (Entscheidung Paul: kein Protokoll auf dem Server). */
class DatenschutzSpeicher(private val ablage: Ablage) {
    private fun liste(): List<String> = ablage.lesen(BESTAETIGT)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()

    fun bestaetigt(kennung: String, version: String) = "$kennung|$version" in liste()

    fun bestaetigen(kennung: String, version: String) {
        val eintrag = "$kennung|$version"
        val alt = liste()
        if (eintrag !in alt) ablage.schreiben(BESTAETIGT, (alt + eintrag).joinToString("\n"))
    }

    /** Muss für diese Version gefragt werden? */
    fun offen(kennung: String, version: String?) = version != null && !bestaetigt(kennung, version)

    /** Zum Nachlesen in den Einstellungen: zuletzt gezeigte Erklärung je Server */
    fun merken(erklaerung: Datenschutz, kennung: String) {
        if (!erklaerung.vollstaendig) return
        val alle = JSONObject(ablage.lesen(TEXTE) ?: "{}")
        alle.put(kennung, erklaerung.alsJson())
        ablage.schreiben(TEXTE, alle.toString())
    }

    fun alle(): Map<String, Datenschutz> {
        val json = runCatching { JSONObject(ablage.lesen(TEXTE) ?: "{}") }.getOrElse { JSONObject() }
        return json.keys().asSequence().mapNotNull { k -> json.optJSONObject(k)?.let(Datenschutz::aus)?.let { k to it } }.toMap()
    }

    companion object {
        private const val BESTAETIGT = "datenschutz.bestaetigt"
        private const val TEXTE = "datenschutz.texte"
    }
}

/** Einfaches Markdown der Erklärung (Überschriften `#`/`##`, Absätze, Listen mit `-`) in Blöcke zerlegen. */
sealed class DatenschutzBlock {
    data class Ueberschrift(val text: String, val ebene: Int) : DatenschutzBlock()
    data class Absatz(val text: String) : DatenschutzBlock()
    data class Punkt(val text: String) : DatenschutzBlock()

    companion object {
        fun zerlegen(text: String): List<DatenschutzBlock> {
            val bloecke = mutableListOf<DatenschutzBlock>()
            val absatz = mutableListOf<String>()
            fun abschliessen() {
                if (absatz.isNotEmpty()) bloecke += Absatz(absatz.joinToString(" "))
                absatz.clear()
            }
            for (roh in text.replace("\r\n", "\n").split('\n')) {
                val zeile = roh.trim()
                when {
                    zeile.isEmpty() -> abschliessen()
                    zeile.startsWith("#") -> {
                        abschliessen()
                        val ebene = zeile.takeWhile { it == '#' }.length
                        bloecke += Ueberschrift(zeile.drop(ebene).trim(), ebene)
                    }
                    zeile.startsWith("- ") || zeile.startsWith("* ") -> {
                        abschliessen()
                        bloecke += Punkt(zeile.drop(2))
                    }
                    else -> absatz += zeile
                }
            }
            abschliessen()
            return bloecke
        }
    }
}
