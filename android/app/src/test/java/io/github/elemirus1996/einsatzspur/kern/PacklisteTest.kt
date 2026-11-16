package io.github.elemirus1996.einsatzspur.kern

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime

/** Packliste der Staffel wie iOS `PacklisteTests`: nur lokal abhaken, Erinnerung (APP_API Team-Rolle Abschnitt 14) */
class PacklisteTest {
    private class MapAblage : Ablage {
        val werte = mutableMapOf<String, String>()
        override fun lesen(schluessel: String) = werte[schluessel]
        override fun schreiben(schluessel: String, wert: String?) { if (wert == null) werte.remove(schluessel) else werte[schluessel] = wert }
    }

    private var geplant: Long? = -1
    private fun liste(ablage: Ablage = MapAblage()) = Packliste(ablage) { geplant = it }

    private val vorlage = PacklisteVorlage("v1", listOf(
        PacklisteVorlage.Punkt("a1", "Stirnlampe", "Persönlich"),
        PacklisteVorlage.Punkt("b2", "Napf", "Hund"),
    ))

    @Test fun vorlageNurBeiNeuerVersion_bleibtNachNeustart() = runTest {
        val ablage = MapAblage()
        val l = liste(ablage)
        var geladen = 0
        l.aktualisieren("v1") { geladen++; vorlage }
        l.aktualisieren("v1") { geladen++; vorlage }
        assertEquals(1, geladen)
        assertEquals(vorlage, liste(ablage).vorlage.value)
    }

    @Test fun netzfehlerBehaeltVorlage_versionNullEntfernt() = runTest {
        val l = liste()
        l.aktualisieren("v1") { vorlage }
        l.aktualisieren("v2") { throw IOException("weg") }
        assertEquals("v1", l.vorlage.value?.version)
        l.aktualisieren(null) { vorlage }
        assertNull(l.vorlage.value)
    }

    @Test fun abhakenNurFuerVorhandenePunkte() = runTest {
        val l = liste()
        l.aktualisieren("v1") { vorlage }
        l.umschalten(Packliste.staffel("a1"))
        l.umschalten(Packliste.staffel("b2"))
        l.aktualisieren("v2") { PacklisteVorlage("v2", listOf(PacklisteVorlage.Punkt("a1", "Stirnlampe", "Persönlich"))) }
        assertEquals(setOf(Packliste.staffel("a1")), l.erledigt.value)
    }

    @Test fun eigenePunkte() {
        val l = liste()
        l.eigenenHinzufuegen("  Ersatzsocken  ")
        l.eigenenHinzufuegen("   ")
        assertEquals(listOf("Ersatzsocken"), l.eigene.value.map { it.text })
        val id = l.eigene.value[0].id
        l.umschalten(Packliste.eigen(id))
        l.eigenenLoeschen(id)
        assertEquals(0, l.anzahl)
        assertTrue(l.erledigt.value.isEmpty())
    }

    @Test fun durchgegangenLeertHakenUndPlant() = runTest {
        val l = liste()
        l.aktualisieren("v1") { vorlage }
        l.umschalten(Packliste.staffel("a1"))
        val jetzt = 1_800_000_000_000L
        l.durchgegangenMerken(jetzt)
        assertTrue(l.erledigt.value.isEmpty())
        assertEquals(Packliste.naechsteErinnerung(jetzt, 14, jetzt), geplant)
        l.erinnerungSetzen(0, jetzt)
        assertNull(geplant)
    }

    @Test fun erinnerungUm18UhrFruehestensMorgen() {
        val zone = ZoneId.of("Europe/Berlin")
        val jetzt = ZonedDateTime.of(2026, 11, 16, 21, 0, 0, 0, zone).toInstant().toEpochMilli()
        val zeit = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(Packliste.naechsteErinnerung(jetzt, 14, jetzt, zone)!!), zone)
        assertEquals(30, zeit.dayOfMonth); assertEquals(18, zeit.hour)
        val alt = ZonedDateTime.of(2026, 10, 1, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        val bald = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(Packliste.naechsteErinnerung(alt, 14, jetzt, zone)!!), zone)
        assertEquals(17, bald.dayOfMonth); assertEquals(18, bald.hour)
        assertNull(Packliste.naechsteErinnerung(null, 0, jetzt))
    }

    @Test fun vorlageAusJson() {
        val json = org.json.JSONObject("""{"version":"9c","stand":"2026-11-16T20:00:00Z","punkte":[{"id":"x1","text":"Napf","gruppe":null},{"id":"x2","text":"Leine","gruppe":"Hund"}]}""")
        val v = PacklisteVorlage.aus(json)!!
        assertEquals("9c", v.version)
        assertNull(v.punkte[0].gruppe)
        assertEquals("Hund", v.punkte[1].gruppe)
        assertEquals(v, PacklisteVorlage.aus(v.json()))
    }
}
