package io.github.elemirus1996.einsatzspur.kern

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/** Feste Kennung, Name und Logo-Fassung des Servers (APP_API.md Abschnitt 10, Server #121). */
data class ServerInfo(val kennung: String, val staffel: String?, val logoVersion: String?) {
    companion object {
        fun aus(json: JSONObject?): ServerInfo? {
            val kennung = json?.textOderNull("kennung") ?: return null
            return ServerInfo(kennung, json.textOderNull("staffel"), json.textOderNull("logoVersion"))
        }
    }
}

/**
 * Die eigene Staffel des Nutzers, wie iOS `HeimatStaffel.swift`: Nur ihr Name und Logo erscheinen auf der Startseite.
 * Erkannt wird sie an der festen Server-Kennung, nicht an der Adresse. Eine fremde Staffel im gemeinsamen Einsatz
 * ändert nichts. Nicht geheim, darum in den normalen Einstellungen; das Logo als Datei im App-Speicher.
 */
class HeimatStaffel(private val ablage: Ablage, private val logoDatei: File) {
    data class Eintrag(val kennung: String, val staffel: String, val logoVersion: String?)

    private val _eintrag = MutableStateFlow(lesen())
    val eintrag: StateFlow<Eintrag?> = _eintrag
    private val _logo = MutableStateFlow(if (_eintrag.value?.logoVersion != null) runCatching { logoDatei.readBytes() }.getOrNull() else null)
    /** PNG-Daten des Logos */
    val logo: StateFlow<ByteArray?> = _logo
    private var abgelehnt: Set<String> = ablage.lesen(ABGELEHNT)?.split('\n')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    /** Fragen nur, solange keine Heimatstaffel gewählt ist und dieser Server nicht schon abgelehnt wurde. */
    fun sollFragen(info: ServerInfo) = _eintrag.value == null && info.kennung !in abgelehnt

    fun istHeimat(info: ServerInfo) = _eintrag.value?.kennung == info.kennung

    fun uebernehmen(info: ServerInfo, name: String) {
        logoLoeschen()
        setzen(Eintrag(info.kennung, name, null))
    }

    fun ablehnen(info: ServerInfo) {
        abgelehnt = abgelehnt + info.kennung
        ablage.schreiben(ABGELEHNT, abgelehnt.joinToString("\n"))
    }

    /** In den Einstellungen: Heimatstaffel vergessen, beim nächsten Verbinden wieder fragen. */
    fun entfernen() {
        abgelehnt = emptySet()
        logoLoeschen()
        setzen(null)
        ablage.schreiben(ABGELEHNT, null)
    }

    /**
     * Nach `config` bzw. Anmeldung: Name übernehmen und das Logo nur laden, wenn sich `logoVersion` geändert hat.
     * [laden] liefert PNG-Daten oder `null`, wenn der Server kein Logo hat (404).
     */
    suspend fun aktualisieren(info: ServerInfo, laden: suspend () -> ByteArray?) {
        var neu = _eintrag.value?.takeIf { istHeimat(info) } ?: return
        info.staffel?.takeIf { it.isNotBlank() }?.let { neu = neu.copy(staffel = it) }
        if (info.logoVersion != neu.logoVersion) {
            if (info.logoVersion == null) {
                logoLoeschen()
                neu = neu.copy(logoVersion = null)
            } else {
                try {
                    val daten = laden()
                    if (daten != null && daten.size <= HOECHSTE_LOGO_GROESSE) {
                        runCatching { logoDatei.writeBytes(daten) }
                        _logo.value = daten
                    } else {
                        logoLoeschen()
                    }
                    // Auch ohne Bild merken, damit die App bei 404 nicht bei jedem Start neu fragt
                    neu = neu.copy(logoVersion = info.logoVersion)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Netzfehler: beim nächsten Mal erneut versuchen
                }
            }
        }
        // Während des Ladens entfernt oder gewechselt: nichts zurückschreiben
        if (_eintrag.value?.kennung != neu.kennung) return
        setzen(neu)
    }

    private fun logoLoeschen() {
        _logo.value = null
        logoDatei.delete()
    }

    private fun setzen(eintrag: Eintrag?) {
        _eintrag.value = eintrag
        ablage.schreiben(EINTRAG, eintrag?.let {
            JSONObject().put("kennung", it.kennung).put("staffel", it.staffel).apply { it.logoVersion?.let { v -> put("logoVersion", v) } }.toString()
        })
    }

    private fun lesen(): Eintrag? = ablage.lesen(EINTRAG)?.let { text ->
        runCatching {
            val json = JSONObject(text)
            Eintrag(json.getString("kennung"), json.getString("staffel"), json.textOderNull("logoVersion"))
        }.getOrNull()
    }

    companion object {
        const val HOECHSTE_LOGO_GROESSE = 2 * 1024 * 1024
        private const val EINTRAG = "heimat.eintrag"
        private const val ABGELEHNT = "heimat.abgelehnt"
    }
}
