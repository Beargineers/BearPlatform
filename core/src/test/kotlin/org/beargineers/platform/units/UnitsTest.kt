package org.beargineers.platform.units

import org.beargineers.platform.assertClose
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val EPS = 1e-9

class DistanceTest {
    @Test
    fun conversions() {
        assertEquals(2.54, 1.inch.inCm, EPS)
        assertEquals(10.0, 1.cm.inMm, EPS)
        assertEquals(1.0, 100.cm.inMeters, EPS)
        assertEquals(15.0, 150.mm.inCm, EPS)
        assertEquals(250.0, 2.5.meters.inCm, EPS)
        assertEquals(24.0, 1.tiles.inInches, EPS)
        assertEquals(1.5, 36.inch.inTiles, EPS)
    }

    @Test
    fun arithmetic() {
        assertClose(15.cm, 10.cm + 5.cm)
        assertClose(5.cm, 10.cm - 5.cm)
        assertClose((-3).cm, -(3.cm))
        assertClose(30.cm, 10.cm * 3)
        assertClose(30.cm, 3 * 10.cm)
        assertClose(2.5.cm, 10.cm / 4)
        assertEquals(4.0, 10.cm / 2.5.cm, EPS)
        assertClose(5.cm, abs(-5.cm))
        assertClose(5.cm, hypot(3.cm, 4.cm))
    }

    @Test
    fun comparison() {
        assertTrue(1.inch > 2.cm)
        assertTrue(2.cm < 1.inch)
        assertEquals(10.cm, maxOf(3.cm, 10.cm))
        assertEquals(5.cm, 12.cm.coerceIn(0.cm, 5.cm))
    }

    @Test
    fun printsCentimeters() {
        assertEquals("2.5cm", 1.inch.toString())
    }
}

class AngleTest {
    @Test
    fun conversions() {
        assertEquals(PI / 2, 90.degrees.inRadians, EPS)
        assertEquals(180.0, PI.radians.inDegrees, EPS)
        assertEquals(0.5, 180.degrees.inRotations, EPS)
        assertEquals(90.0, 0.25.rotations.inDegrees, EPS)
    }

    @Test
    fun arithmeticDoesNotWrap() {
        assertEquals(450.0, (270.degrees + 180.degrees).inDegrees, EPS)
        assertEquals(-720.0, (2 * (-360).degrees).inDegrees, EPS)
    }

    @Test
    fun normalized() {
        assertEquals(-179.0, 181.degrees.normalized().inDegrees, EPS)
        assertEquals(179.0, (-181).degrees.normalized().inDegrees, EPS)
        assertEquals(10.0, 370.degrees.normalized().inDegrees, EPS)
        assertEquals(-10.0, (-730).degrees.normalized().inDegrees, EPS)
        assertEquals(180.0, 180.degrees.normalized().inDegrees, EPS)
        assertEquals(180.0, (-180).degrees.normalized().inDegrees, EPS)
    }

    @Test
    fun wholeRotations() {
        // Plain angles keep counting turns; normalized() brings them back to a heading
        assertEquals(1080.0, 3.rotations.inDegrees, EPS)
        assertEquals(0.0, 3.rotations.normalized().inDegrees, EPS)
        assertEquals(0.0, (-2).rotations.normalized().inDegrees, EPS)
        assertEquals(0.0, 1080.degrees.normalized().inDegrees, EPS)
    }

    @Test
    fun shortestTurn() {
        // From -170° to 170° is 20° clockwise, not 340° counterclockwise
        assertEquals(-20.0, (170.degrees - (-170).degrees).normalized().inDegrees, EPS)
    }

    @Test
    fun trigonometry() {
        assertEquals(0.5, sin(30.degrees), EPS)
        assertEquals(0.5, cos(60.degrees), EPS)
        assertEquals(1.0, tan(45.degrees), EPS)
        assertClose(45.degrees, atan2(1.cm, 1.cm))
        assertClose(180.degrees, atan2(0.cm, (-1).cm))
    }

    @Test
    fun printsDegrees() {
        assertEquals("90.0°", 90.degrees.toString())
    }
}

class HeadingTest {
    @Test
    fun alwaysWithinHalfTurn() {
        assertEquals(90.0, 90.degrees.toHeading().inDegrees, EPS)
        assertEquals(-90.0, 270.degrees.toHeading().inDegrees, EPS)
        assertEquals(180.0, (-180).degrees.toHeading().inDegrees, EPS)
        assertEquals(0.0, 3.rotations.toHeading().inDegrees, EPS)
    }

    @Test
    fun turningWrapsAround() {
        assertEquals(-170.0, (170.degrees.toHeading() + 20.degrees).inDegrees, EPS)
        assertEquals(170.0, ((-170).degrees.toHeading() - 20.degrees).inDegrees, EPS)
        assertEquals(10.0, (170.degrees.toHeading() + 3.rotations + 200.degrees).inDegrees, EPS)
    }

    @Test
    fun differenceIsShortestTurn() {
        assertEquals(-20.0, (170.degrees.toHeading() - (-170).degrees.toHeading()).inDegrees, EPS)
        assertEquals(20.0, ((-170).degrees.toHeading() - 170.degrees.toHeading()).inDegrees, EPS)
        assertEquals(90.0, (90.degrees.toHeading() - Heading.ZERO).inDegrees, EPS)
    }

    @Test
    fun trigonometry() {
        assertEquals(1.0, sin(90.degrees.toHeading()), EPS)
        assertEquals(-1.0, cos(180.degrees.toHeading()), EPS)
    }
}

class VelocityTest {
    @Test
    fun linear() {
        assertEquals(60.0, (120.cm / 2.seconds).inCmPerSecond, EPS)
        assertEquals(10.0, 25.4.cm.perSecond.inInchesPerSecond, EPS)
        assertClose(30.cm, 60.cm.perSecond * 500.milliseconds)
    }

    @Test
    fun angular() {
        assertEquals(360.0, 60.rpm.inDegreesPerSecond, EPS)
        assertEquals(60.0, (1.rotations / 1.seconds).inRpm, EPS)
        assertClose(90.degrees, 180.degrees.perSecond * 0.5.seconds)
    }
}
