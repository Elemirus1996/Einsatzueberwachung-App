package io.github.elemirus1996.einsatzspur.kern

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** Antwort von `GET /packliste` (APP_API.md, Team-Rolle Abschnitt 14). */
data class PacklisteVorlage(val version: String, val punkte: List<Punkt>) {
    data class Punkt(val id: String, val text: String, val gruppe: String?)

    fun json(): JSONObject = JSONObject().put("version", version).put("punkte", JSONArray().apply {
        punkte.forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("gruppe", it.gruppe ?: JSONObject.NULL)) }
    })

    companion object {
        fun aus(json: JSONObject): PacklisteVorlage? {
            val version = json.textOderNull("version") ?: return null
            val liste = json.optJSONArray("punkte") ?: JSONArray()
            val punkte = (0 until liste.length()).mapNotNull { i ->
                val p = liste.optJSONObject(i) ?: return@mapNotNull null
                val id = p.textOderNull("id") ?: return@mapNotNull null
                val text = p.textOderNull("text") ?: return@mapNotNull null
                Punkt(id, text, p.textOderNull("gruppe"))
            }
            return PacklisteVorlage(version, punkte)
        }
    }
}

/**
 * Packliste wie iOS `Packliste.swift`: Vorlage der Heimatstaffel (nur abhakbar) und eigene Punkte.
 * Alles bleibt auf dem Gerät, der Server erfährt nicht, was abgehakt ist. [planer] stellt die eine ausstehende
 * Erinnerung (Unix-ms) bzw. nimmt sie zurück (`null`).
 */
class Packliste(private val ablage: Ablage, private val planer: (Long?) -> Unit) {
    data class EigenerPunkt(val id: String, val text: String)

    private val _vorlage = MutableStateFlow(ablage.lesen(VORLAGE)?.let { runCatching { PacklisteVorlage.aus(JSONObject(it)) }.getOrNull() })
    val vorlage: StateFlow<PacklisteVorlage?> = _vorlage
    private val _eigene = MutableStateFlow(eigeneLesen())
    val eigene: StateFlow<List<EigenerPunkt>> = _eigene
    private val _erledigt = MutableStateFlow(ablage.lesen(ERLEDIGT)?.split('\n')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet())
    val erledigt: StateFlow<Set<String>> = _erledigt
    private val _durchgegangen = MutableStateFlow(ablage.lesen(DURCHGEGANGEN)?.toLongOrNull())
    val durchgegangen: StateFlow<Long?> = _durchgegangen
    private val _erinnerungTage = MutableStateFlow(ablage.lesen(TAGE)?.toIntOrNull() ?: STANDARD_TAGE)
    val erinnerungTage: StateFlow<Int> = _erinnerungTage

    val anzahl: Int get() = (_vorlage.value?.punkte?.size ?: 0) + _eigene.value.size

    /**
     * Nach `config` bzw. Anmeldung, nur für die Heimatstaffel: Vorlage nur laden, wenn sich `packlisteVersion` geändert hat.
     * [laden] liefert die Vorlage oder `null` bei 404. Netzfehler: beim nächsten Mal erneut.
     */
    suspend fun aktualisieren(version: String?, laden: suspend () -> PacklisteVorlage?) {
        if (version == _vorlage.value?.version) return
        if (version == null) {
            vorlageSetzen(null)
            return
        }
        try {
            vorlageSetzen(laden())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Netzfehler: beim nächsten Mal erneut versuchen
        }
    }

    /** Heimatstaffel entfernt: Ihre Vorlage gehört nicht mehr aufs Gerät, eigene Punkte bleiben. */
    fun vorlageEntfernen() {
        if (_vorlage.value != null) vorlageSetzen(null)
    }

    private fun vorlageSetzen(neu: PacklisteVorlage?) {
        _vorlage.value = neu
        ablage.schreiben(VORLAGE, neu?.json()?.toString())
        // Abhak-Stand nur für Punkte behalten, die es noch gibt
        val gueltig = (neu?.punkte ?: emptyList()).map { staffel(it.id) }.toSet() + _eigene.value.map { eigen(it.id) }
        erledigtSetzen(_erledigt.value intersect gueltig)
        erinnerungPlanen()
    }

    fun umschalten(schluessel: String) {
        erledigtSetzen(if (schluessel in _erledigt.value) _erledigt.value - schluessel else _erledigt.value + schluessel)
    }

    fun eigenenHinzufuegen(text: String) {
        val sauber = text.trim().take(HOECHSTE_TEXTLAENGE)
        if (sauber.isEmpty() || _eigene.value.size >= HOECHSTE_EIGENE) return
        eigeneSetzen(_eigene.value + EigenerPunkt(UUID.randomUUID().toString(), sauber))
    }

    fun eigenenLoeschen(id: String) {
        eigeneSetzen(_eigene.value.filterNot { it.id == id })
        erledigtSetzen(_erledigt.value - eigen(id))
    }

    /** „Liste durchgegangen“: Datum merken, Haken für die nächste Runde leeren, Erinnerung neu stellen. */
    fun durchgegangenMerken(jetzt: Long = System.currentTimeMillis()) {
        _durchgegangen.value = jetzt
        ablage.schreiben(DURCHGEGANGEN, jetzt.toString())
        erledigtSetzen(emptySet())
        erinnerungPlanen(jetzt)
    }

    fun erinnerungSetzen(tage: Int, jetzt: Long = System.currentTimeMillis()) {
        _erinnerungTage.value = tage.coerceAtLeast(0)
        ablage.schreiben(TAGE, _erinnerungTage.value.toString())
        erinnerungPlanen(jetzt)
    }

    /** Beim Start und nach jeder Änderung: genau eine ausstehende Erinnerung. */
    fun erinnerungPlanen(jetzt: Long = System.currentTimeMillis()) {
        planer(if (anzahl == 0) null else naechsteErinnerung(_durchgegangen.value, _erinnerungTage.value, jetzt))
    }

    private fun erledigtSetzen(neu: Set<String>) {
        _erledigt.value = neu
        ablage.schreiben(ERLEDIGT, neu.joinToString("\n"))
    }

    private fun eigeneSetzen(neu: List<EigenerPunkt>) {
        _eigene.value = neu
        ablage.schreiben(EIGENE, JSONArray().apply { neu.forEach { put(JSONObject().put("id", it.id).put("text", it.text)) } }.toString())
        erinnerungPlanen()
    }

    private fun eigeneLesen(): List<EigenerPunkt> = ablage.lesen(EIGENE)?.let { text ->
        runCatching {
            val liste = JSONArray(text)
            (0 until liste.length()).map { liste.getJSONObject(it).let { o -> EigenerPunkt(o.getString("id"), o.getString("text")) } }
        }.getOrNull()
    } ?: emptyList()

    companion object {
        const val STANDARD_TAGE = 14
        /** Auswahl in der Ansicht; 0 = keine Erinnerung */
        val TAGE_AUSWAHL = listOf(0, 7, 14, 30)
        const val HOECHSTE_EIGENE = 100
        const val HOECHSTE_TEXTLAENGE = 200
        private const val VORLAGE = "packliste.vorlage"
        private const val EIGENE = "packliste.eigene"
        private const val ERLEDIGT = "packliste.erledigt"
        private const val DURCHGEGANGEN = "packliste.durchgegangen"
        private const val TAGE = "packliste.erinnerungTage"

        /** Schlüssel für den Abhak-Stand, Staffel und eigene getrennt */
        fun staffel(id: String) = "s:$id"
        fun eigen(id: String) = "e:$id"

        /**
         * Zeitpunkt der nächsten Erinnerung: [tage] nach dem letzten Durchgehen um 18 Uhr, frühestens morgen 18 Uhr.
         * Ohne bisheriges Durchgehen zählt ab jetzt. `null`, wenn ausgeschaltet.
         */
        fun naechsteErinnerung(durchgegangen: Long?, tage: Int, jetzt: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
            if (tage <= 0) return null
            val basis = ZonedDateTime.ofInstant(Instant.ofEpochMilli(durchgegangen ?: jetzt), zone)
            val faellig = basis.plusDays(tage.toLong()).withHour(18).withMinute(0).withSecond(0).withNano(0)
            val frueheste = ZonedDateTime.ofInstant(Instant.ofEpochMilli(jetzt), zone).plusDays(1)
                .withHour(18).withMinute(0).withSecond(0).withNano(0)
            return maxOf(faellig, frueheste).toInstant().toEpochMilli()
        }
    }
}
