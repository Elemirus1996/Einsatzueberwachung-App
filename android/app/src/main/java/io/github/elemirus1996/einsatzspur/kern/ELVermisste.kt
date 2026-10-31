package io.github.elemirus1996.einsatzspur.kern

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/** Szenario des Einsatzes (`/szenario`), wie iOS `ELSzenario`. */
enum class ELSzenario(val wert: String, val anzeige: String) {
    UNBESTIMMT("Unbestimmt", "Unbestimmt"),
    MANTRAILER("Mantrailer", "Mantrailer"),
    FLAECHE("Flaeche", "Fläche"),
    TRUEMMER("Truemmer", "Trümmer"),
    SONSTIGE("Sonstige", "Sonstige");

    companion object {
        fun aus(wert: String?) = entries.firstOrNull { it.wert == wert } ?: UNBESTIMMT
    }
}

/**
 * Vermisstenprofil (APP_API.md „native App“ Abschnitt 7, Server Stufe 4a), wie iOS `ELVermisstenprofil`.
 * Texte nie `null`, leer = "". Ohne `profilId` ist es ein neues Profil.
 */
data class ELVermisstenprofil(
    val profilId: String? = null,
    val version: String? = null,
    val vollerName: String? = null,
    val zuletztAktualisiertMs: Long? = null,
    val checkliste: Checkliste? = null,
    val texte: Map<String, String> = emptyMap(),
    val schalter: Map<String, Boolean> = emptyMap(),
    val auswahlen: Map<String, String> = AUSWAHL_FELDER.keys.associateWith { "Unbekannt" },
) {
    data class Checkliste(val name: String?, val punkte: List<Punkt>) {
        /** `typ`: `jaNein` · `auswahl` · `text` */
        data class Punkt(
            val id: String,
            val text: String,
            val typ: String,
            val pflicht: Boolean,
            val auswahl: List<String>,
            val wert: String?,
            val erledigt: Boolean,
        )
    }

    fun text(feld: String) = texte[feld] ?: ""
    fun schalter(feld: String) = schalter[feld] ?: false
    fun auswahl(feld: String) = auswahlen[feld] ?: "Unbekannt"

    fun mitText(feld: String, wert: String) = copy(texte = texte + (feld to wert))
    fun mitSchalter(feld: String, wert: Boolean) = copy(schalter = schalter + (feld to wert))
    fun mitAuswahl(feld: String, wert: String) = copy(auswahlen = auswahlen + (feld to wert))

    /** Nur geänderte Felder (der Server übernimmt nur mitgeschickte; "" leert ein Textfeld). */
    fun aenderungen(alt: ELVermisstenprofil): JSONObject = JSONObject().also { j ->
        TEXT_FELDER.filter { text(it) != alt.text(it) }.forEach { j.put(it, text(it)) }
        SCHALTER_FELDER.filter { schalter(it) != alt.schalter(it) }.forEach { j.put(it, schalter(it)) }
        AUSWAHL_FELDER.keys.filter { auswahl(it) != alt.auswahl(it) }.forEach { j.put(it, auswahl(it)) }
    }

    companion object {
        // Feldnamen wie im Server-Modell
        val TEXT_FELDER = listOf(
            "vorname", "nachname", "alter", "geburtsdatum", "beschreibung", "kleidung", "besonderheiten",
            "zuletztGesehenOrt", "zuletztGesehenZeit", "zuletztGesehenVon", "vorerkrankungen", "medikamente",
            "polizeiKontaktName", "polizeiDienstnummer", "polizeiTelefon",
            "bosEinheit", "bosZugfuehrer", "bosFunkrufname", "bosAufgabenteilung",
        )
        val SCHALTER_FELDER = listOf(
            "polizeiVermisstenmeldungAufgenommen", "polizeiKoordinationBesprochen", "polizeiSuchabschnittAbgestimmt",
            "polizeiRueckmeldepflichtVereinbart", "polizeiDatenschutzGeklaert", "bosAbschnittAbgestimmt", "bosRessourcenBesprochen",
        )
        val AUSWAHL_FELDER = mapOf(
            "orientierung" to listOf("Unbekannt", "Gut", "Eingeschraenkt"),
            "mobilitaet" to listOf("Unbekannt", "ZuFuss", "Rollator", "Rollstuhl", "Fahrzeug"),
            "suizidrisiko" to listOf("Unbekannt", "Nein", "Ja"),
            "bewaffnet" to listOf("Unbekannt", "Nein", "Ja"),
        )

        fun anzeige(wert: String) = when (wert) {
            "Eingeschraenkt" -> "Eingeschränkt"
            "ZuFuss" -> "Zu Fuß"
            else -> wert
        }

        fun aus(j: JSONObject) = ELVermisstenprofil(
            profilId = j.textOderNull("profilId"),
            version = j.textOderNull("version"),
            vollerName = j.textOderNull("vollerName"),
            zuletztAktualisiertMs = isoZuMs(j.textOderNull("zuletztAktualisiert")),
            checkliste = j.optJSONObject("checkliste")?.let { c ->
                val punkte = c.getJSONArray("punkte")
                Checkliste(c.textOderNull("name"), (0 until punkte.length()).map { i ->
                    val p = punkte.getJSONObject(i)
                    val auswahl = p.optJSONArray("auswahl") ?: JSONArray()
                    Checkliste.Punkt(
                        p.getString("id"), p.getString("text"), p.getString("typ"), p.optBoolean("pflicht", false),
                        (0 until auswahl.length()).map { auswahl.getString(it) }, p.textOderNull("wert"), p.optBoolean("erledigt", false),
                    )
                })
            },
            texte = TEXT_FELDER.associateWith { j.textOderNull(it) ?: "" },
            schalter = SCHALTER_FELDER.associateWith { j.optBoolean(it, false) },
            auswahlen = AUSWAHL_FELDER.keys.associateWith { j.textOderNull(it) ?: "Unbekannt" },
        )
    }
}

/** Antwort von `GET /vermisste`. */
data class ELVermisste(val szenario: ELSzenario, val mehrereErlaubt: Boolean, val profile: List<ELVermisstenprofil>) {
    companion object {
        fun aus(json: JSONObject): ELVermisste = try {
            val profile = json.getJSONArray("profile")
            ELVermisste(
                ELSzenario.aus(json.textOderNull("szenario")), json.optBoolean("mehrereErlaubt", false),
                (0 until profile.length()).map { ELVermisstenprofil.aus(profile.getJSONObject(it)) },
            )
        } catch (e: JSONException) {
            throw SchnittstellenFehler.AntwortUngueltig
        }
    }
}

/** 409 mit neuem Stand: inzwischen geändert (auch durch `/el` im Browser). 400/409 ohne Profil → [ELAktionAbgelehnt]. */
class ELVermissteGeaendert(val profil: ELVermisstenprofil) : Exception("Profil geändert")

interface ELVermissteSchnittstelle {
    suspend fun vermisste(): ELVermisste
    /** Anlegen (ohne `profilId`) oder ändern (mit `profilId` und `version`); Antwort neue Kennung und Version */
    suspend fun vermissteSpeichern(id: String, profilId: String?, version: String?, felder: JSONObject): Pair<String, String>
}

fun ELAktion.Companion.checkliste(profilId: String, punkt: ELVermisstenprofil.Checkliste.Punkt, erledigt: Boolean? = null, wert: String? = null) =
    ELAktion.neu("vermisste/$profilId/checkliste", "Checkliste: ${punkt.text}", JSONObject().put("punktId", punkt.id).also { j ->
        erledigt?.let { j.put("erledigt", it) }
        wert?.let { j.put("wert", it) }
    })

fun ELAktion.Companion.vermisstenEntfernen(profilId: String, name: String) = ELAktion.neu("vermisste/$profilId/entfernen", "$name entfernen")

fun ELAktion.Companion.szenario(szenario: ELSzenario) =
    ELAktion.neu("szenario", "Szenario ${szenario.anzeige}", JSONObject().put("szenario", szenario.wert))

/** Vermisste der Einsatzleitung, wie iOS `ELVermissteAnsicht`: Liste laden (live über `vermisste`), Profil speichern mit Versionsprüfung. */
class ELVermissteAnsicht(private val schnittstelle: ELVermissteSchnittstelle) {
    private val _stand = MutableStateFlow<ELVermisste?>(null)
    val stand: StateFlow<ELVermisste?> = _stand
    private val _fehler = MutableStateFlow<String?>(null)
    val fehler: StateFlow<String?> = _fehler

    suspend fun laden() {
        try {
            _stand.value = schnittstelle.vermisste()
            _fehler.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _fehler.value = "Vermisste konnten nicht geladen werden."
        }
    }

    /** Speichert nur die Änderungen. Bei Konflikt [ELVermissteGeaendert] mit dem aktuellen Stand zum erneuten Bearbeiten. */
    suspend fun speichern(neu: ELVermisstenprofil, original: ELVermisstenprofil): ELVermisstenprofil {
        val (profilId, version) = schnittstelle.vermissteSpeichern(
            UUID.randomUUID().toString(), original.profilId, original.version, neu.aenderungen(original))
        laden()
        return neu.copy(profilId = profilId, version = version)
    }
}
