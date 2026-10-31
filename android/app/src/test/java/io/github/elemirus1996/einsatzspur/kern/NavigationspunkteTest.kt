package io.github.elemirus1996.einsatzspur.kern

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Navigationsziele und Punkte (APP_API.md Team-Rolle Abschnitt 13, Variante C) und Töne je Art, wie iOS `NavigationspunkteTests`. */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationspunkteTest {
    private val server = TestAnsichtSchnittstelle()
    private val ablage = MapAblage()
    private val modi = mutableListOf<Modus>()

    private fun TestScope.ansicht() = TeamAnsicht(server, "t1", ablage, backgroundScope, { 1_800_000_000_000L }) { modi += it }

    private val zielJson = """
        { "id": "m2", "text": "Ziel: Wegkreuzung Nord", "zeit": "2026-10-31T09:10:00Z",
          "ziel": { "punktId": "p1", "name": "Wegkreuzung Nord", "lat": 49.312345, "lng": 8.431234,
                    "links": { "geo": "geo:49.312345,8.431234?q=49.312345,8.431234(Wegkreuzung%20Nord)",
                               "appleKarten": "https://maps.apple.com/?ll=49.312345,8.431234&q=Wegkreuzung%20Nord",
                               "googleMaps": "comgooglemaps://?daddr=49.312345,8.431234&directionsmode=walking",
                               "waze": "waze://?ll=49.312345,8.431234&navigate=yes" } } }
    """

    // MARK: Ziel

    @Test fun mitteilungMitUndOhneZiel() {
        val mitZiel = TeamLage.mitteilung(JSONObject(zielJson))
        assertEquals("Wegkreuzung Nord", mitZiel.ziel?.name)
        assertEquals("geo:49.312345,8.431234?q=49.312345,8.431234(Wegkreuzung%20Nord)", mitZiel.ziel?.geo)
        val ohne = TeamLage.mitteilung(JSONObject("""{"id":"m1","text":"Hallo","zeit":"2026-10-31T09:10:00Z","ziel":null}"""))
        assertNull(ohne.ziel)
    }

    @Test fun neuesZielAlsEigeneMeldung() {
        val neu = BASIS_LAGE.copy(mitteilungen = BASIS_LAGE.mitteilungen + TeamLage.mitteilung(JSONObject(zielJson)))
        assertEquals(
            listOf(LageMeldung("Neues Ziel: Wegkreuzung Nord", "Zum Navigieren die App öffnen", Tonart.NAVIGATIONSZIEL)),
            LageMeldung.vergleichen(BASIS_LAGE, neu),
        )
    }

    // MARK: Punkt setzen

    @Test fun punktWirdMitUtcZeitGesendet() = runTest {
        val a = ansicht()
        a.punktSetzen("  Fund: Jacke ", 49.3, 8.4, 8.0, 1_800_000_000_000L)
        val punkt = server.punkte.single()
        assertEquals("Fund: Jacke", punkt.text)
        assertTrue(a.offenePunkte.value.isEmpty())
        assertEquals(true, a.punktHinweis.value?.second)
        val json = punkt.json()
        assertEquals("2027-01-15T08:00:00Z", json.getString("zeit"))
        assertEquals(8.0, json.getDouble("genauigkeit"), 0.0)
    }

    @Test fun punktImFunklochWartetUndGehtSpaeterRaus() = runTest {
        server.punktFehler = java.io.IOException("kein Netz")
        val a = ansicht()
        a.punktSetzen("Spur", 49.3, 8.4, null, 1_800_000_000_000L)
        assertEquals(1, a.offenePunkte.value.size)
        val id = a.offenePunkte.value.single().id
        // Nach Neustart der Ansicht noch da (gespeichert), dieselbe id beim zweiten Versuch
        val zweite = ansicht()
        assertEquals(listOf(id), zweite.offenePunkte.value.map { it.id })
        server.punktFehler = null
        zweite.punkteSenden()
        assertTrue(zweite.offenePunkte.value.isEmpty())
        assertEquals(listOf(id, id), server.punkte.map { it.id })
    }

    @Test fun abgelehnterPunktWirdVerworfenMitHinweis() = runTest {
        server.punktFehler = PunktAbgelehnt("Höchstens 50 Punkte je Team.")
        val a = ansicht()
        a.punktSetzen("Hinweis", 49.3, 8.4, null, 1_800_000_000_000L)
        assertTrue(a.offenePunkte.value.isEmpty())
        assertEquals("Höchstens 50 Punkte je Team.", a.punktHinweis.value?.first)
        assertEquals(false, a.punktHinweis.value?.second)
    }

    @Test fun punktBeiBeendetemEinsatzStopptOrtung() = runTest {
        server.punktFehler = SchnittstellenFehler.Beendet
        ansicht().punktSetzen("Fund", 49.3, 8.4, null, 1_800_000_000_000L)
        assertEquals(listOf(Modus.STOP), modi)
    }

    @Test fun leererTextWirdNichtGesendet() = runTest {
        ansicht().punktSetzen("   ", 49.3, 8.4, null, 1_800_000_000_000L)
        assertTrue(server.punkte.isEmpty())
    }

    // MARK: Einsatzleitung

    @Test fun ereignisPunktUndTonFuerTeamPunkt() = runTest {
        val ereignis = ELEreignis.aus(SseLeser.Ereignis("punkt", """{"id":"p1","teamId":"t1","aenderung":"neu","text":"Team Rex: Fund"}"""))
        assertEquals(ELEreignis.Punkt("p1", "t1", "neu", "Team Rex: Fund"), ereignis)

        val a = ELAnsicht(TestELLage(Result.success(ELLage.aus(JSONObject(LAGE_JSON)))), this) {}
        val toene = mutableListOf<Tonart>()
        var neuGeladen = 0
        a.beiTon = { toene += it }
        a.beiPunkt = { neuGeladen++ }
        a.verarbeiten(ereignis!!)
        a.verarbeiten(ELEreignis.Punkt("p2", null, "neu", null)) // Punkt der EL: kein Ton
        a.verarbeiten(ELEreignis.Punkt("p1", "t1", "geloescht", null))
        assertEquals(listOf(Tonart.PUNKT_VOM_TEAM), toene)
        assertEquals(3, neuGeladen)
        assertEquals("Team Rex: Fund", a.teamPunkt.value?.first)
    }

    @Test fun karteMitPunkten() {
        val karte = ELKarte.aus(JSONObject("""{"suchgebiete":[],"positionen":[],"punkte":[{"id":"p1","name":"Team Rex: Fund","beschreibung":"x","farbe":"#E67E22","lat":49.3,"lng":8.4,"zeit":"2026-10-31T09:12:03Z","teamId":"t1"}]}"""))
        assertEquals("t1", karte.punkte.single().teamId)
        assertTrue(ELKarte.aus(JSONObject("""{"suchgebiete":[],"positionen":[]}""")).punkte.isEmpty())
    }

    @Test fun zielAktion() {
        val ziel = ELAktion.ziel("t1", "Team Rex", "p1", "Nord")
        assertEquals("teams/t1/ziel", ziel.pfad)
        assertEquals("p1", ziel.koerper().getString("punktId"))
    }

    // MARK: Töne

    @Test fun toeneJeArtUndStufeZweiNichtAus() {
        val gespielt = mutableListOf<TonWahl>()
        val toene = Toene(ablage) { gespielt += it }
        assertEquals(Tonart.entries.size, Tonart.entries.map { toene.wahl(it) }.toSet().size) // ab Werk unterscheidbar
        assertFalse(TonWahl.Aus in toene.moeglich(Tonart.WARNUNG2))
        assertTrue(TonWahl.Aus in toene.moeglich(Tonart.WARNUNG1))

        toene.setzen(TonWahl.Aus, Tonart.WARNUNG2)
        assertEquals(Tonart.WARNUNG2.standard, toene.wahl(Tonart.WARNUNG2))
        toene.setzen(TonWahl.Vibration, Tonart.PUNKT_VOM_TEAM)
        toene.setzen(TonWahl.Aus, Tonart.FUNK)
        toene.spielen(Tonart.PUNKT_VOM_TEAM)
        assertEquals(listOf<TonWahl>(TonWahl.Vibration), gespielt)
        assertFalse(toene.mitTon(Tonart.FUNK))

        // Gespeichert je Gerät; ein manipuliertes „aus“ für Stufe 2 gilt nicht
        ablage.schreiben("ton.warnung2", "aus")
        val neu = Toene(ablage) {}
        assertEquals(TonWahl.Vibration, neu.wahl(Tonart.PUNKT_VOM_TEAM))
        assertEquals(Tonart.WARNUNG2.standard, neu.wahl(Tonart.WARNUNG2))
    }
}
