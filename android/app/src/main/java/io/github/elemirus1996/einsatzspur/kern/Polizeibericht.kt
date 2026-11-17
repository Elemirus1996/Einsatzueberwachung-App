package io.github.elemirus1996.einsatzspur.kern

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** „Eingesetzt als“ je Team (APP_API EL Abschnitt 10, Server 1.17.0), wie iOS `ELEinsatzart`. */
enum class ELEinsatzart(val wert: String, val anzeige: String) {
    FLAECHE("Flaeche", "Fläche"),
    TRUEMMER("Truemmer", "Trümmer"),
    MANTRAILER("Mantrailer", "Mantrailer"),
    WASSER("Wasser", "Wasser"),
    LAWINE("Lawine", "Lawine");

    companion object {
        fun aus(wert: String?) = entries.firstOrNull { it.wert == wert }

        /** Anzeige zum Wert aus der Schnittstelle; unbekannte Werte so, wie sie kommen */
        fun anzeige(wert: String?): String? = wert?.takeIf { it.isNotEmpty() }?.let { aus(it)?.anzeige ?: it }
    }
}

/** Wer die Staffel alarmiert hat (Pflicht beim Anlegen ab Server 1.17.0). */
enum class ELAlarmiertDurch(val wert: String, val anzeige: String) {
    POLIZEI("Polizei", "Polizei"),
    FEUERWEHR("Feuerwehr", "Feuerwehr"),
    RETTUNGSDIENST("Rettungsdienst", "Rettungsdienst"),
    ANDERE_STAFFEL("AndereStaffel", "andere Rettungshundestaffel");

    companion object {
        fun aus(wert: String?) = entries.firstOrNull { it.wert == wert }
    }
}

/** Abwählbare Abschnitte des Berichts (`pdf.abschnitte`); Kopf, Ergebnis und Polizei sind immer dabei. */
enum class ELBerichtAbschnitt(val wert: String, val anzeige: String) {
    VERMISSTE("vermisste", "Vermisste Person"),
    ZEITLEISTE("zeitleiste", "Zeitlicher Ablauf"),
    TEAMS("teams", "Suche je Team und Trails"),
    KARTEN("karten", "Karten"),
    FUNK("funk", "Funk und Notizen"),
    VORORT("vorort", "Vor Ort"),
}

/** Rolle der EL-App im laufenden Einsatz (APP_API EL Abschnitt 4a). */
enum class ELRolle(val wert: String, val anzeige: String) {
    EINSATZLEITER("einsatzleiter", "Einsatzleiter"),
    FUEHRUNGSASSISTENT("fuehrungsassistent", "Führungsassistent"),
    LESEN("lesen", "Nur lesen");

    companion object {
        fun aus(wert: String?) = entries.firstOrNull { it.wert == wert }
    }
}

/** `ich` aus `/lage` und `POST /ich` */
data class ELIch(val personId: String?, val name: String?, val rolle: String?) {
    val rollenWert: ELRolle? get() = ELRolle.aus(rolle)

    companion object {
        fun aus(j: JSONObject) = ELIch(j.textOderNull("personId"), j.textOderNull("name"), j.textOderNull("rolle"))
    }
}

/** Eintrag aus `GET /personen` */
data class ELPerson(val personId: String, val name: String, val einsatzleiter: Boolean, val fuehrungsassistent: Boolean) {
    companion object {
        fun liste(json: JSONObject): List<ELPerson> = try {
            val a = json.optJSONArray("personen") ?: JSONArray()
            (0 until a.length()).map { i ->
                val p = a.getJSONObject(i)
                ELPerson(p.getString("personId"), p.optString("name"), p.optBoolean("einsatzleiter"), p.optBoolean("fuehrungsassistent"))
            }
        } catch (e: JSONException) {
            throw SchnittstellenFehler.AntwortUngueltig
        }
    }
}

/** `GET /einsatz/abschluss`: alles, was der Beenden-Dialog abfragt, mit den aktuellen Werten. */
data class ELAbschluss(
    val uebung: Boolean = false,
    val alarmiertDurch: String? = null,
    val keinePolizei: Boolean = false,
    val vorgangsnummer: String = "",
    val melder: String = "",
    val polizeiName: String = "",
    val dienstnummer: String = "",
    val telefon: String = "",
    val fund: Fund = Fund(),
    val teams: List<Team> = emptyList(),
    val trails: List<Trail> = emptyList(),
    val abbruchgruende: List<String> = emptyList(),
) {
    data class Vorschlag(val lat: Double, val lng: Double, val zeitMs: Long?, val teamId: String?)
    data class Fund(
        val zeitMs: Long? = null, val lat: Double? = null, val lng: Double? = null, val ort: String = "",
        val uebergabeAn: String = "", val teamId: String? = null, val vorschlag: Vorschlag? = null,
    )
    data class Team(val teamId: String, val teamName: String, val einsatzart: String?, val erlaubt: List<String>) {
        /** Spezialisierungen des Hundes, ohne Angabe alle fünf; eine schon gesetzte Art bleibt wählbar */
        val wahl: List<ELEinsatzart>
            get() {
                val liste = erlaubt.mapNotNull(ELEinsatzart::aus).ifEmpty { ELEinsatzart.entries }.toMutableList()
                ELEinsatzart.aus(einsatzart)?.let { if (it !in liste) liste += it }
                return liste
            }
    }
    data class Trail(val sucheId: String, val teamName: String, val beginnMs: Long?, val endeMs: Long?,
                     val abbruchgrund: String, val geruchsartikel: String)

    companion object {
        fun aus(json: JSONObject): ELAbschluss = try {
            val p = json.objektOderNull("polizei")
            val f = json.objektOderNull("fund")
            ELAbschluss(
                uebung = json.optBoolean("uebung", false),
                alarmiertDurch = json.textOderNull("alarmiertDurch"),
                keinePolizei = json.optBoolean("keinePolizei", false),
                vorgangsnummer = json.textOderNull("vorgangsnummer") ?: "",
                melder = json.textOderNull("melder") ?: "",
                polizeiName = p?.textOderNull("name") ?: "",
                dienstnummer = p?.textOderNull("dienstnummer") ?: "",
                telefon = p?.textOderNull("telefon") ?: "",
                fund = Fund(
                    zeitMs = isoZuMs(f?.textOderNull("zeit")), lat = f?.zahlOderNull("lat"), lng = f?.zahlOderNull("lng"),
                    ort = f?.textOderNull("ort") ?: "", uebergabeAn = f?.textOderNull("uebergabeAn") ?: "", teamId = f?.textOderNull("teamId"),
                    vorschlag = f?.objektOderNull("vorschlag")?.let {
                        Vorschlag(it.getDouble("lat"), it.getDouble("lng"), isoZuMs(it.textOderNull("zeit")), it.textOderNull("teamId"))
                    },
                ),
                teams = json.optJSONArray("teams")?.let { a ->
                    (0 until a.length()).map { i ->
                        val t = a.getJSONObject(i)
                        Team(t.getString("teamId"), t.optString("teamName"), t.textOderNull("einsatzart"),
                            t.optJSONArray("erlaubt")?.let { e -> (0 until e.length()).map(e::getString) } ?: emptyList())
                    }
                } ?: emptyList(),
                trails = json.optJSONArray("trails")?.let { a ->
                    (0 until a.length()).map { i ->
                        val t = a.getJSONObject(i)
                        Trail(t.getString("sucheId"), t.optString("teamName"), isoZuMs(t.textOderNull("beginn")), isoZuMs(t.textOderNull("ende")),
                            t.textOderNull("abbruchgrund") ?: "", t.textOderNull("geruchsartikel") ?: "")
                    }
                } ?: emptyList(),
                abbruchgruende = json.optJSONArray("abbruchgruende")?.let { a -> (0 until a.length()).map(a::getString) } ?: emptyList(),
            )
        } catch (e: JSONException) {
            throw SchnittstellenFehler.AntwortUngueltig
        }
    }
}

/** Eingaben des Beenden-Dialogs; daraus wird `abschluss` für `POST /einsatz/beenden`, wie iOS `ELAbschlussEingabe`. */
data class ELAbschlussEingabe(
    val uebung: Boolean = false,
    val alarmiertDurch: ELAlarmiertDurch? = null,
    val melder: String = "",
    val keinePolizei: Boolean = false,
    val vorgangsnummer: String = "",
    val polizeiName: String = "",
    val dienstnummer: String = "",
    val telefon: String = "",
    val fundZeitMs: Long? = null,
    val fundOrt: String = "",
    val fundLat: Double? = null,
    val fundLng: Double? = null,
    val fundTeamId: String? = null,
    val uebergabeAn: String = "",
    val einsatzart: Map<String, ELEinsatzart> = emptyMap(),
    val abbruchgrund: Map<String, String> = emptyMap(),
    val geruchsartikel: Map<String, String> = emptyMap(),
) {
    /** Polizeiangaben gefragt: Einsatz, von der Polizei alarmiert, nicht „Keine Polizei beteiligt“ (wie der Server) */
    val polizeiGefragt: Boolean get() = !uebung && alarmiertDurch == ELAlarmiertDurch.POLIZEI && !keinePolizei

    fun vorschlagUebernehmen(v: ELAbschluss.Vorschlag) = copy(
        fundLat = v.lat, fundLng = v.lng, fundZeitMs = fundZeitMs ?: v.zeitMs, fundTeamId = v.teamId ?: fundTeamId,
    )

    /** Fehlende Angaben in Worten, gleiche Regeln wie `fehlend` des Servers */
    fun fehlend(a: ELAbschluss, fund: Boolean): List<String> = buildList {
        if (!uebung && alarmiertDurch == null) add("Alarmiert durch")
        if (polizeiGefragt && vorgangsnummer.isBlank()) add("Vorgangsnummer der Polizei")
        if (polizeiGefragt && polizeiName.isBlank()) add("Polizeikontakt")
        if (fund && (fundZeitMs == null || (fundLat == null && fundOrt.isBlank()))) add("Fundzeit und Fundort")
        if (a.teams.any { einsatzart[it.teamId] == null }) add("„Eingesetzt als“ je Team")
        if (a.trails.any { abbruchgrund[it.sucheId].isNullOrBlank() || geruchsartikel[it.sucheId].isNullOrBlank() }) {
            add("Abbruchgrund und Geruchsartikel je Trail")
        }
    }

    /** `abschluss` für `POST /einsatz/beenden`; Fund nur bei einem Ergebnis mit Fund */
    fun json(a: ELAbschluss, fund: Boolean, jetztMs: Long = System.currentTimeMillis()): JSONObject {
        val o = JSONObject()
            .put("keinePolizei", keinePolizei)
            .put("melder", melder.trim())
            .put("vorgangsnummer", vorgangsnummer.trim())
            .put("polizei", JSONObject().put("name", polizeiName).put("dienstnummer", dienstnummer).put("telefon", telefon))
            .put("teams", JSONArray(a.teams.map {
                JSONObject().put("teamId", it.teamId).put("einsatzart", einsatzart[it.teamId]?.wert ?: JSONObject.NULL)
            }))
            .put("trails", JSONArray(a.trails.map {
                JSONObject().put("sucheId", it.sucheId).put("abbruchgrund", abbruchgrund[it.sucheId] ?: "")
                    .put("geruchsartikel", geruchsartikel[it.sucheId] ?: "")
            }))
        alarmiertDurch?.let { o.put("alarmiertDurch", it.wert) }
        if (fund) {
            val f = JSONObject().put("ort", fundOrt).put("uebergabeAn", uebergabeAn).put("teamId", fundTeamId ?: "")
            fundZeitMs?.let { f.put("zeit", ELEinsatzEnde.utc(minOf(it, jetztMs))) }
            if (fundLat != null && fundLng != null) f.put("lat", fundLat).put("lng", fundLng)
            o.put("fund", f)
        }
        return o
    }

    companion object {
        fun aus(a: ELAbschluss) = ELAbschlussEingabe(
            uebung = a.uebung, alarmiertDurch = ELAlarmiertDurch.aus(a.alarmiertDurch), melder = a.melder, keinePolizei = a.keinePolizei,
            vorgangsnummer = a.vorgangsnummer, polizeiName = a.polizeiName, dienstnummer = a.dienstnummer, telefon = a.telefon,
            fundZeitMs = a.fund.zeitMs, fundOrt = a.fund.ort, fundLat = a.fund.lat, fundLng = a.fund.lng, fundTeamId = a.fund.teamId,
            uebergabeAn = a.fund.uebergabeAn,
            einsatzart = a.teams.mapNotNull { t -> ELEinsatzart.aus(t.einsatzart)?.let { t.teamId to it } }.toMap(),
            abbruchgrund = a.trails.associate { it.sucheId to it.abbruchgrund },
            geruchsartikel = a.trails.associate { it.sucheId to it.geruchsartikel },
        )
    }
}
