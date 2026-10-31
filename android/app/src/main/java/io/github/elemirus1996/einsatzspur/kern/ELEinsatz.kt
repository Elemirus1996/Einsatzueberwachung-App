package io.github.elemirus1996.einsatzspur.kern

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Kartenart des PDF-Berichts, wie iOS `ELKartenart`. */
enum class ELKartenart(val wert: String, val anzeige: String) {
    STREETS("Streets", "Straßenkarte"),
    TOPOGRAPHIC("Topographic", "Topografisch"),
    SATELLITE("Satellite", "Satellit"),
}

/** Eingaben für „Einsatz starten“, wie iOS `ELEinsatzStart`. */
data class ELEinsatzStart(
    val stichwort: String = "",
    val ort: String = "",
    val szenario: ELSzenario = ELSzenario.UNBESTIMMT,
    val uebung: Boolean = false,
    val einsatzleiter: String = "",
    val fuehrungsassistent: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    /** Divera-Alarm aus `GET /einsatz/vorlage`; dann übernimmt der Server Ort, Koordinate und Stichwort aus dem Alarm */
    val diveraAlarmId: Int? = null,
) {
    /** Szenario immer Pflicht (nicht „Unbestimmt“), der Ort nur ohne Divera-Alarm */
    val vollstaendig: Boolean get() = szenario != ELSzenario.UNBESTIMMT && (diveraAlarmId != null || ort.isNotBlank())
}

/** Erlaubtes Ergebnis für das Einsatzende (`GET /einsatz/ergebnisse`). */
data class ELErgebnis(val wert: String, val anzeige: String) {
    companion object {
        fun liste(a: JSONArray) = (0 until a.length()).map {
            val e = a.getJSONObject(it)
            ELErgebnis(e.getString("wert"), e.textOderNull("anzeige") ?: e.getString("wert"))
        }
    }
}

/** Auswahllisten für Start und Ende (`GET /einsatz/vorlage`, Server #143), wie iOS `ELVorlage`. */
data class ELVorlage(
    val aktiv: Boolean = false,
    val szenarien: List<String> = emptyList(),
    val divera: List<DiveraAlarm> = emptyList(),
    val einsatzleiter: List<String> = emptyList(),
    val fuehrungsassistenten: List<String> = emptyList(),
    val ergebnisse: List<ELErgebnis> = emptyList(),
    val kartenarten: List<Pair<String, String>> = emptyList(),
) {
    data class DiveraAlarm(val id: Int, val titel: String?, val ort: String?, val lat: Double?, val lng: Double?, val zeitMs: Long?)

    /** Szenarien zur Auswahl; ohne Liste vom Server alle außer „Unbestimmt“ */
    val szenarioWahl: List<ELSzenario>
        get() {
            val liste = szenarien.mapNotNull { w -> ELSzenario.entries.firstOrNull { it.wert == w } }.filter { it != ELSzenario.UNBESTIMMT }
            return liste.ifEmpty { ELSzenario.entries.filter { it != ELSzenario.UNBESTIMMT } }
        }

    /** Kartenarten für den Bericht (Wert, Anzeige); ohne Liste vom Server die bekannten drei */
    val kartenWahl: List<Pair<String, String>>
        get() = kartenarten.ifEmpty { ELKartenart.entries.map { it.wert to it.anzeige } }

    companion object {
        fun aus(json: JSONObject): ELVorlage = try {
            ELVorlage(
                aktiv = json.optBoolean("aktiv", false),
                szenarien = texte(json.optJSONArray("szenarien")),
                divera = json.optJSONArray("divera")?.let { a ->
                    (0 until a.length()).map { i ->
                        val d = a.getJSONObject(i)
                        DiveraAlarm(d.getInt("id"), d.textOderNull("titel"), d.textOderNull("ort"), d.zahlOderNull("lat"),
                            d.zahlOderNull("lng"), isoZuMs(d.textOderNull("zeit")))
                    }
                } ?: emptyList(),
                einsatzleiter = texte(json.optJSONArray("einsatzleiter")),
                fuehrungsassistenten = texte(json.optJSONArray("fuehrungsassistenten")),
                ergebnisse = json.optJSONArray("ergebnisse")?.let(ELErgebnis::liste) ?: emptyList(),
                kartenarten = json.optJSONArray("kartenarten")?.let { a ->
                    (0 until a.length()).map { i -> a.getJSONObject(i).let { it.getString("wert") to (it.textOderNull("anzeige") ?: it.getString("wert")) } }
                } ?: emptyList(),
            )
        } catch (e: JSONException) {
            throw SchnittstellenFehler.AntwortUngueltig
        }

        private fun texte(a: JSONArray?) = a?.let { (0 until it.length()).map(it::getString) } ?: emptyList()
    }
}

/** Antwort von `POST /einsatz/beenden`: Archiv-Eintrag und ob der PDF-Bericht erstellt wurde. */
data class ELEinsatzEnde(val archivId: String? = null, val einsatzNummer: String? = null, val pdfErstellt: Boolean? = null, val pdfFehler: String? = null) {
    /** Hinweis für die Einsatzleitung nach dem Beenden */
    val meldung: String
        get() = listOfNotNull(
            einsatzNummer?.let { "Einsatz $it beendet und archiviert." } ?: "Einsatz beendet und archiviert.",
            when (pdfErstellt) {
                true -> "Der Bericht (PDF) liegt im Archiv."
                false -> "Bericht (PDF) nicht erstellt: ${pdfFehler ?: "unbekannter Fehler"}. Im Monitor im Archiv neu erzeugen."
                null -> null
            },
        ).joinToString(" ")

    companion object {
        /** Kennungen als Text oder Zahl annehmen; unlesbare Antwort ergibt einen leeren Eintrag */
        fun aus(text: String): ELEinsatzEnde {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return ELEinsatzEnde()
            fun kennung(name: String) = if (json.has(name) && !json.isNull(name)) json.get(name).toString() else null
            val pdf = json.optJSONObject("pdf")
            return ELEinsatzEnde(kennung("archivId"), kennung("einsatzNummer"), pdf?.optBoolean("erstellt", false), pdf?.textOderNull("fehler"))
        }

        /** Zeitpunkt in UTC mit `Z`, ohne Sekundenbruchteile (z. B. `2026-10-31T17:45:00Z`) */
        fun utc(zeitMs: Long): String = Instant.ofEpochMilli(zeitMs).truncatedTo(ChronoUnit.SECONDS).toString()
    }
}

interface ELEinsatzSchnittstelle {
    suspend fun vorlage(): ELVorlage
    suspend fun ergebnisse(): List<ELErgebnis>
}

fun ELAktion.Companion.einsatzStart(d: ELEinsatzStart): ELAktion {
    val inhalt = JSONObject().put("szenario", d.szenario.wert).put("uebung", d.uebung)
    listOf("stichwort" to d.stichwort, "ort" to d.ort, "einsatzleiter" to d.einsatzleiter, "fuehrungsassistent" to d.fuehrungsassistent)
        .forEach { (feld, wert) -> wert.trim().takeIf { it.isNotEmpty() }?.let { inhalt.put(feld, it) } }
    if (d.lat != null && d.lng != null) inhalt.put("lat", d.lat).put("lng", d.lng)
    d.diveraAlarmId?.let { inhalt.put("diveraAlarmId", it) }
    return ELAktion.neu("einsatz/start", if (d.uebung) "Übung starten" else "Einsatz starten", inhalt)
}

fun ELAktion.Companion.einsatzBeenden(
    ergebnis: String,
    mitTracks: Boolean,
    kartenart: String = ELKartenart.STREETS.wert,
    teamIds: List<String>? = null,
    endzeitMs: Long? = null,
    bemerkungen: String? = null,
    mitPunkten: Boolean = false,
): ELAktion {
    val pdf = JSONObject().put("tracks", mitTracks).put("kartenart", kartenart)
        .put("teamIds", teamIds?.let { JSONArray(it) } ?: JSONObject.NULL).put("punkte", mitPunkten)
    val inhalt = JSONObject().put("ergebnis", ergebnis).put("ressourcenGeprueft", true).put("pdf", pdf)
    endzeitMs?.let { inhalt.put("endzeit", ELEinsatzEnde.utc(it)) }
    bemerkungen?.trim()?.takeIf { it.isNotEmpty() }?.let { inhalt.put("bemerkungen", it) }
    return ELAktion.neu("einsatz/beenden", "Einsatz beenden", inhalt)
}
