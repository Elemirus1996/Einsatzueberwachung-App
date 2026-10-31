package io.github.elemirus1996.einsatzspur.kern

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject

/** Antwort von `GET /karte` der Einsatzleitung (APP_API.md „native App“ Abschnitt 6), wie iOS `ELKarte`. */
data class ELKarte(val suchgebiete: List<Suchgebiet>, val positionen: List<ELPosition>, val punkte: List<Punkt> = emptyList()) {
    /** Kartenpunkt (Variante C); `teamId` bei Punkten, die ein Team per App gesetzt hat */
    data class Punkt(
        val id: String,
        val name: String?,
        val beschreibung: String?,
        val farbe: String?,
        val lat: Double,
        val lng: Double,
        val zeitMs: Long?,
        val teamId: String?,
    )

    /** `abdeckungProzent` ist `null`, solange der Server nichts berechnet hat. */
    data class Suchgebiet(
        val id: String,
        val name: String?,
        val farbe: String?,
        val punkte: List<Koordinate>,
        val teamId: String?,
        val abdeckungProzent: Double?,
        val abgeschlossen: Boolean,
    )

    companion object {
        fun aus(json: JSONObject): ELKarte = try {
            val gebiete = json.getJSONArray("suchgebiete")
            val positionen = json.getJSONArray("positionen")
            ELKarte(
                suchgebiete = (0 until gebiete.length()).map { i ->
                    val g = gebiete.getJSONObject(i)
                    val punkte = g.getJSONArray("punkte")
                    Suchgebiet(
                        id = g.getString("id"),
                        name = g.textOderNull("name"),
                        farbe = g.textOderNull("farbe"),
                        punkte = (0 until punkte.length()).map {
                            val p = punkte.getJSONObject(it)
                            Koordinate(p.getDouble("lat"), p.getDouble("lng"))
                        },
                        teamId = g.textOderNull("teamId"),
                        abdeckungProzent = g.zahlOderNull("abdeckungProzent"),
                        abgeschlossen = g.optBoolean("abgeschlossen", false),
                    )
                },
                positionen = (0 until positionen.length()).map { ELPosition.aus(positionen.getJSONObject(it)) },
                // Ältere Server ohne Kartenpunkte: leer
                punkte = json.optJSONArray("punkte")?.let { a ->
                    (0 until a.length()).map { i ->
                        val p = a.getJSONObject(i)
                        Punkt(p.getString("id"), p.textOderNull("name"), p.textOderNull("beschreibung"), p.textOderNull("farbe"),
                            p.getDouble("lat"), p.getDouble("lng"), isoZuMs(p.textOderNull("zeit")), p.textOderNull("teamId"))
                    }
                } ?: emptyList(),
            )
        } catch (e: JSONException) {
            throw SchnittstellenFehler.AntwortUngueltig
        }
    }
}

/** Antwort von `GET /tracks` der Einsatzleitung: laufende Suchen je Team, abgeschlossene mit `teamId`. */
data class ELTracks(val serverZeitMs: Long?, val laufend: List<Laufend>, val abgeschlossen: List<Suche>) {
    data class Laufend(val teamId: String, val hund: List<TeamTracks.Punkt>, val mensch: List<TeamTracks.Punkt>)
    data class Suche(val sucheId: String?, val teamId: String?, val tracks: List<TeamTracks.Track>)

    /** Größter Zeitpunkt aller laufenden Suchen, für `?seit=`. */
    val letzterZeitpunkt: Long? get() = laufend.flatMap { it.hund + it.mensch }.mapNotNull { it.t }.maxOrNull()

    /** Neuere Punkte (`?seit=`) an die laufenden Suchen anhängen; `abgeschlossen` bleibt. */
    fun anhaengen(neu: ELTracks): ELTracks {
        val ergebnis = laufend.toMutableList()
        for (teil in neu.laufend) {
            val i = ergebnis.indexOfFirst { it.teamId == teil.teamId }
            if (i >= 0) {
                ergebnis[i] = ergebnis[i].copy(hund = ergebnis[i].hund + teil.hund, mensch = ergebnis[i].mensch + teil.mensch)
            } else {
                ergebnis += teil
            }
        }
        return copy(laufend = ergebnis)
    }

    companion object {
        fun aus(json: JSONObject): ELTracks = try {
            val laufend = json.getJSONArray("laufend")
            val abgeschlossen = json.optJSONArray("abgeschlossen")
            ELTracks(
                serverZeitMs = if (json.has("serverZeit") && !json.isNull("serverZeit")) json.getLong("serverZeit") else null,
                laufend = (0 until laufend.length()).map { i ->
                    val l = laufend.getJSONObject(i)
                    Laufend(l.getString("teamId"), TeamTracks.punkte(l.getJSONArray("hund")), TeamTracks.punkte(l.getJSONArray("mensch")))
                },
                abgeschlossen = abgeschlossen?.let { a ->
                    (0 until a.length()).map { i ->
                        val s = a.getJSONObject(i)
                        Suche(s.textOderNull("sucheId"), s.textOderNull("teamId"), TeamTracks.tracks(s.getJSONArray("tracks")))
                    }
                } ?: emptyList(),
            )
        } catch (e: JSONException) {
            throw SchnittstellenFehler.AntwortUngueltig
        }
    }
}

interface ELKartenSchnittstelle {
    suspend fun karte(): ELKarte
    suspend fun tracks(seit: Long?): ELTracks
}

/**
 * Lagekarte der Einsatzleitung wie iOS `ELKartenAnsicht`: Gebiete mit Abdeckung, Positionen (live über `position`),
 * Tracks. Läuft, solange die Karte sichtbar ist: alle 30 s neue Punkte (`?seit=`), alle 5 min alles ohne `seit`.
 */
class ELKartenAnsicht(
    private val schnittstelle: ELKartenSchnittstelle,
    private val bereich: CoroutineScope,
    private val jetzt: () -> Long = System::currentTimeMillis,
) {
    private val _karte = MutableStateFlow<ELKarte?>(null)
    val karte: StateFlow<ELKarte?> = _karte
    private val _tracks = MutableStateFlow<ELTracks?>(null)
    val tracks: StateFlow<ELTracks?> = _tracks

    private var aufgabe: Job? = null
    private var letzteVolleMs: Long? = null

    val laeuft: Boolean get() = aufgabe != null

    fun starten() {
        if (aufgabe != null) return
        aufgabe = bereich.launch {
            laden(voll = true)
            while (true) {
                delay(TRACK_TAKT_MS)
                laden(voll = false)
            }
        }
    }

    fun anhalten() {
        aufgabe?.cancel()
        aufgabe = null
    }

    /** Live-Ereignis `punkt`: nur die Karte neu laden, nicht die Tracks */
    suspend fun karteNeuLaden() {
        versuchen { schnittstelle.karte() }?.let { _karte.value = it }
    }

    /** `voll`: Karte und Tracks ohne `seit`; sonst nur neue Punkte, außer die letzte volle Abfrage ist zu alt. */
    suspend fun laden(voll: Boolean) {
        if (voll) versuchen { schnittstelle.karte() }?.let { _karte.value = it }
        val bisher = _tracks.value
        val vollNoetig = voll || bisher == null || letzteVolleMs?.let { jetzt() - it >= TRACK_VOLL_NACH_MS } ?: true
        if (vollNoetig) {
            versuchen { schnittstelle.tracks(null) }?.let {
                _tracks.value = it
                letzteVolleMs = jetzt()
            }
        } else if (bisher != null) {
            versuchen { schnittstelle.tracks(bisher.letzterZeitpunkt) }?.let { _tracks.value = bisher.anhaengen(it) }
        }
    }

    /** Lage geändert (Gebiet, Team, Einsatz): Gebiete und Positionen neu, solange die Karte offen ist. */
    fun lageGeaendert() {
        if (aufgabe == null) return
        bereich.launch { versuchen { schnittstelle.karte() }?.let { _karte.value = it } }
    }

    /** Live-Ereignis `position`: Marker bewegen bzw. neu aufnehmen. Der Name bleibt, wenn das Ereignis keinen trägt. */
    fun position(neu: ELPosition) {
        val k = _karte.value ?: return
        val i = k.positionen.indexOfFirst { it.schluessel == neu.schluessel }
        val positionen = k.positionen.toMutableList()
        if (i >= 0) positionen[i] = neu.copy(name = neu.name ?: positionen[i].name) else positionen += neu
        _karte.value = k.copy(positionen = positionen)
    }

    // Fehler beim Laden lassen den bisherigen Stand stehen; der nächste Takt versucht es erneut
    private suspend fun <T> versuchen(abruf: suspend () -> T): T? = try {
        abruf()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        const val TRACK_TAKT_MS = 30_000L
        const val TRACK_VOLL_NACH_MS = 300_000L
    }
}
