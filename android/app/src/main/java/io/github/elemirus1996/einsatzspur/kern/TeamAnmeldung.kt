package io.github.elemirus1996.einsatzspur.kern

import org.json.JSONException
import org.json.JSONObject

sealed class AnmeldeFehler(nachricht: String) : Exception(nachricht) {
    /** 401: Code falsch oder kein laufender Einsatz */
    data object CodeUngueltig : AnmeldeFehler("Code ungültig")
    /** 404: Team gibt es nicht mehr */
    data object TeamUnbekannt : AnmeldeFehler("Team unbekannt")
    /** 429: Limit, frühestens nach 30 s erneut */
    data object ZuVieleVersuche : AnmeldeFehler("zu viele Versuche")
    data class Server(val status: Int) : AnmeldeFehler("HTTP $status")
    data object AntwortUngueltig : AnmeldeFehler("Antwort ungültig")
}

data class TeamEintrag(val teamId: String, val teamName: String, val dogName: String?, val hundefuehrerName: String?)

data class TeamAuswahl(
    val einsatz: String?,
    val uebung: Boolean,
    val staffel: String?,
    val teams: List<TeamEintrag>,
    val server: ServerInfo? = null,
    /** Vor dem Token: Erklärung der Staffel (#148) */
    val datenschutz: DatenschutzAngabe = DatenschutzAngabe.Fehlt,
)

/** Native Anmeldung mit dem Anmelde-Code (`login/teams`, `login`), APP_API.md Abschnitt 1. */
class TeamAnmeldung(private val zugang: TeamQRCode) {

    suspend fun teams(): TeamAuswahl = auswahlAus(anfrage("login/teams", JSONObject().put("code", zugang.code)))

    suspend fun anmelden(teamId: String): TeamSitzung {
        val json = anfrage("login", JSONObject().put("code", zugang.code).put("teamId", teamId))
        return try {
            TeamSitzung(
                server = zugang.server,
                token = json.getString("token"),
                teamId = json.optString("teamId").ifEmpty { teamId },
                teamName = json.getString("teamName"),
                expiresAtMs = isoZuMs(json.getString("expiresAt")) ?: throw AnmeldeFehler.AntwortUngueltig,
            )
        } catch (e: JSONException) {
            throw AnmeldeFehler.AntwortUngueltig
        }
    }

    private suspend fun anfrage(endpunkt: String, inhalt: JSONObject): JSONObject {
        val (status, text) = http(zugang.server.pfad("api/team-mobile/app/v1/$endpunkt"), "POST", inhalt, null)
        when {
            status in 200..299 -> Unit
            status == 401 -> throw AnmeldeFehler.CodeUngueltig
            status == 404 && endpunkt == "login" -> throw AnmeldeFehler.TeamUnbekannt
            status == 429 -> throw AnmeldeFehler.ZuVieleVersuche
            else -> throw AnmeldeFehler.Server(status)
        }
        return try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw AnmeldeFehler.AntwortUngueltig
        }
    }

    companion object {
        /** Antwort von `login/teams`: Einsatz, Teams, Server-Info und Datenschutzerklärung (#148). */
        fun auswahlAus(json: JSONObject): TeamAuswahl = try {
            val liste = json.getJSONArray("teams")
            TeamAuswahl(
                einsatz = json.optString("einsatz").ifEmpty { null },
                uebung = json.optBoolean("uebung", false),
                staffel = json.optString("staffel").ifEmpty { null },
                teams = (0 until liste.length()).map { i ->
                    val t = liste.getJSONObject(i)
                    TeamEintrag(
                        teamId = t.getString("teamId"),
                        teamName = t.getString("teamName"),
                        dogName = t.optString("dogName").ifEmpty { null },
                        hundefuehrerName = t.optString("hundefuehrerName").ifEmpty { null },
                    )
                },
                server = ServerInfo.aus(json.optJSONObject("server")),
                datenschutz = DatenschutzAngabe.aus(json),
            )
        } catch (e: JSONException) {
            throw AnmeldeFehler.AntwortUngueltig
        }
    }
}
