package com.navigator.app.notifications

import com.navigator.app.ble.BccuProtocol.TurnIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub issue #8: a newer Google Maps puts the whole instruction in the title,
 * tints the arrow black, and leaves the text line empty.
 */
class Issue8MapsLayoutTest {

    // ---- distance / road split ----

    @Test fun classicLayoutIsUnchanged() {
        val s = NavFields.split("110 m", "towards 1st Cross Rd", null)
        assertEquals("110 m", s.distance)
        assertEquals("towards 1st Cross Rd", s.road)
    }

    @Test fun newerLayoutSplitsTheTitle() {
        val s = NavFields.split("400 m · Turn left onto 1st Main Rd", null, "400 m")
        assertEquals("400 m", s.distance)
        assertEquals("Turn left onto 1st Main Rd", s.road)
    }

    @Test fun newerLayoutWithoutShortCriticalText() {
        val s = NavFields.split("1.2 km • Keep right", "", null)
        assertEquals("1.2 km", s.distance)
        assertEquals("Keep right", s.road)
    }

    @Test fun shortCriticalTextPrefixWithoutSeparator() {
        val s = NavFields.split("400 m Turn left onto MG Rd", null, "400 m")
        assertEquals("400 m", s.distance)
        assertEquals("Turn left onto MG Rd", s.road)
    }

    @Test fun hyphenatedRoadIsNotSplit() {
        val s = NavFields.split("Continue on NH-44", null, "2 km")
        assertEquals("2 km", s.distance)
        assertEquals("Continue on NH-44", s.road)
    }

    @Test fun bareDistanceTitle() {
        val s = NavFields.split("300 ft", null, null)
        assertEquals("300 ft", s.distance)
        assertNull(s.road)
    }

    @Test fun distanceFitsTheEightCharSlot() {
        val s = NavFields.split("400 m · Turn left onto 1st Main Rd", null, "400 m")
        assertTrue(s.distance!!.length <= 8)
    }

    // ---- keyword matching ----

    private fun maneuver(vararg fields: String): TurnIcon? =
        TurnIconHeuristic.parseManeuverFromText(
            fields.joinToString(" ") { TurnIconHeuristic.withoutRoadName(it) }.lowercase()
        )

    @Test fun newerTitleGivesALeftTurn() {
        assertEquals(TurnIcon.QUITE_LEFT, maneuver("400 m · Turn left onto 1st Main Rd"))
    }

    @Test fun etaSubtextIsNotArrival() {
        // The subtext is not part of the parsed text at all; and even if it
        // were, "Arrive 8:15 pm" is an ETA, not an arrival.
        assertEquals(TurnIcon.QUITE_LEFT, maneuver("400 m · Turn left onto 1st Main Rd"))
        assertNull(maneuver("Arrive 8:15 pm"))
    }

    @Test fun roadNameCannotMakeASharpTurn() {
        assertEquals(TurnIcon.QUITE_LEFT, maneuver("Turn left onto Harding Rd"))
    }

    @Test fun roadNameCannotAddADirection() {
        assertEquals(TurnIcon.GO_STRAIGHT, maneuver("Continue onto Right Bank Rd"))
    }

    @Test fun classicTowardsLineGivesNoManeuver() {
        // Classic layout: no maneuver words, so the arrow image decides.
        assertNull(maneuver("110 m", "towards Left Bank Rd"))
    }

    @Test fun wordsInsideOtherWordsDontCount() {
        assertNull(maneuver("Cleft Hollow"))
    }

    @Test fun specificManeuversStillWork() {
        assertEquals(TurnIcon.HEAVY_RIGHT, maneuver("Sharp right onto Ring Rd"))
        assertEquals(TurnIcon.LIGHT_LEFT, maneuver("Slight left towards Mysore"))
        assertEquals(TurnIcon.KEEP_RIGHT, maneuver("Keep right at the fork"))
        assertEquals(TurnIcon.RAB_SECT_3_RH, maneuver("At the roundabout, take the 3rd exit onto MG Rd"))
        assertEquals(TurnIcon.UTURN_LEFT, maneuver("Make a U-turn"))
        assertEquals(TurnIcon.GO_STRAIGHT, maneuver("Head north on Church St"))
    }

    @Test fun realArrivalIsStillArrival() {
        assertEquals(TurnIcon.END, maneuver("You have arrived at your destination"))
    }

    // ---- black-tinted arrow ----

    private fun glyph(color: Int): IntArray {
        // 10x10, transparent background, a 4x10 opaque bar of [color].
        return IntArray(100) { i -> if (i % 10 in 3..6) color else 0x00000000 }
    }

    @Test fun blackTintedArrowIsDarkInk() {
        assertTrue(ManeuverClassifier.isDarkInk(glyph(0xFF000000.toInt())))
    }

    @Test fun whiteArrowIsLeftAlone() {
        assertFalse(ManeuverClassifier.isDarkInk(glyph(0xFFFFFFFF.toInt())))
    }

    @Test fun colouredArrowIsLeftAlone() {
        assertFalse(ManeuverClassifier.isDarkInk(glyph(0xFF4285F4.toInt())))
    }

    @Test fun emptyBitmapIsNotDarkInk() {
        assertFalse(ManeuverClassifier.isDarkInk(IntArray(100)))
    }
}
