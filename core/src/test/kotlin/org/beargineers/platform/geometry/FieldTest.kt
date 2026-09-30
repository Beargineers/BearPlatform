package org.beargineers.platform.geometry

import org.beargineers.platform.assertClose
import org.beargineers.platform.units.cm
import org.beargineers.platform.units.degrees
import org.beargineers.platform.units.inch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FieldTest {
    private val decode = Field(xAxis = FieldDirection.TOWARD_AUDIENCE, symmetry = FieldSymmetry.FLIP_Y)

    @Test
    fun tilesMatchDecodeSeasonCoordinates() {
        // Expected values are what tileLocation() returned in the DECODE season code
        assertClose(Point((-72).inch, 72.inch), decode.tile("F6TR"))
        assertClose(Point(72.inch, (-72).inch), decode.tile("A1BL"))
        assertClose(Point((-12).inch, 12.inch), decode.tile("D4"))
        assertClose(Point(48.inch, (-24).inch), decode.tile("B2BR"))
        assertClose(Point(0.inch, 36.inch), decode.tile("E4BC"))
    }

    @Test
    fun suffixLettersInAnyOrder() {
        assertEquals(decode.tile("F6TR"), decode.tile("f6rt"))
        assertEquals(decode.tile("C3CL"), decode.tile("C3L"))
        assertEquals(decode.tile("C3"), decode.tile("C3C"))
    }

    @Test
    fun otherAxisOrientation() {
        val field = Field(xAxis = FieldDirection.AWAY_FROM_AUDIENCE, symmetry = FieldSymmetry.ROTATE_180)
        // +x points away from the audience, so +y points to the audience's left
        assertClose(Point((-60).inch, 60.inch), field.tile("A1"))
    }

    @Test
    fun invalidTileCodes() {
        for (code in listOf("A", "G1", "A7", "A1TB", "A1LR", "A1X", "A1TLC")) {
            assertFailsWith<IllegalArgumentException>(code) { decode.tile(code) }
        }
    }

    @Test
    fun forAllianceUsesSymmetry() {
        val red = Pose(10.cm, 20.cm, 30.degrees)
        assertEquals(red, decode.forAlliance(red, Alliance.RED))
        assertClose(Pose(10.cm, (-20).cm, (-30).degrees), decode.forAlliance(red, Alliance.BLUE))

        val flipX = Field(FieldDirection.TOWARD_AUDIENCE, FieldSymmetry.FLIP_X)
        assertClose(Pose((-10).cm, 20.cm, 150.degrees), flipX.forAlliance(red, Alliance.BLUE))

        val rotated = Field(FieldDirection.TOWARD_AUDIENCE, FieldSymmetry.ROTATE_180)
        assertClose(Pose((-10).cm, (-20).cm, (-150).degrees), rotated.forAlliance(red, Alliance.BLUE))
    }

    @Test
    fun driversFaceAwayFromTheirWall() {
        // DECODE: red drivers on the audience's left pushed the stick forward to drive toward +y
        val field = Field(FieldDirection.TOWARD_AUDIENCE, FieldSymmetry.FLIP_Y, redStation = FieldDirection.AUDIENCE_LEFT)
        assertClose(90.degrees.toHeading(), field.driversFace(Alliance.RED))
        assertClose((-90).degrees.toHeading(), field.driversFace(Alliance.BLUE))

        val thrown = assertFailsWith<IllegalStateException> { decode.driversFace(Alliance.RED) }
        assertTrue("redStation" in thrown.message!!, thrown.message)
    }

    @Test
    fun bounds() {
        assertTrue(decode.contains(decode.tile("F6TR")))
        assertFalse(decode.contains(Point(73.inch, 0.cm)))
        assertClose(Point(72.inch, (-72).inch), decode.clamp(Point(100.inch, (-100).inch)))
    }
}
