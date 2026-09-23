package org.beargineers.platform.geometry

import org.beargineers.platform.assertClose
import org.beargineers.platform.units.cm
import org.beargineers.platform.units.degrees
import org.beargineers.platform.units.perSecond
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

private const val EPS = 1e-9

class GeometryTest {
    @Test
    fun robotOffsetWhenFacingPlusX() {
        assertClose(Point(10.cm, 0.cm), (Pose.ZERO + RobotOffset(forward = 10.cm)).point)
        assertClose(Point(0.cm, (-10).cm), (Pose.ZERO + RobotOffset(right = 10.cm)).point)
    }

    @Test
    fun robotOffsetWhenFacingPlusY() {
        val facingY = Pose(0.cm, 0.cm, 90.degrees)
        assertClose(Point(0.cm, 10.cm), (facingY + RobotOffset(forward = 10.cm)).point)
        assertClose(Point(10.cm, 0.cm), (facingY + RobotOffset(right = 10.cm)).point)
    }

    @Test
    fun relativeToIsInverseOfPlus() {
        val start = Pose(25.cm, (-40).cm, 37.degrees)
        val offset = RobotOffset(forward = 18.cm, right = 7.cm, turn = 50.degrees)
        assertClose(offset, (start + offset).relativeTo(start))
    }

    @Test
    fun pointRelativeToRobot() {
        val robot = Pose(10.cm, 10.cm, 90.degrees)
        assertClose(RobotOffset(forward = 5.cm, right = 3.cm), Point(13.cm, 15.cm).relativeTo(robot))
    }

    @Test
    fun headingsWrapAround() {
        assertEquals(-170.0, Pose.ZERO.turnBy(190.degrees).heading.inDegrees, EPS)
        assertEquals(-170.0, (Pose(0.cm, 0.cm, 170.degrees) + RobotOffset(turn = 20.degrees)).heading.inDegrees, EPS)
    }

    @Test
    fun headingTo() {
        assertEquals(0.0, Point.ZERO.headingTo(Point(10.cm, 0.cm)).inDegrees, EPS)
        assertEquals(90.0, Point.ZERO.headingTo(Point(0.cm, 10.cm)).inDegrees, EPS)
        assertEquals(180.0, Point.ZERO.headingTo(Point((-10).cm, 0.cm)).inDegrees, EPS)
    }

    @Test
    fun rotateBy() {
        assertClose(Point(0.cm, 10.cm), Point(10.cm, 0.cm).rotateBy(90.degrees))
    }

    @Test
    fun lerpTurnsTheShortWay() {
        val from = Pose(0.cm, 0.cm, 170.degrees)
        val to = Pose(10.cm, 0.cm, (-170).degrees)
        assertClose(Pose(2.5.cm, 0.cm, 175.degrees), from.lerp(to, 0.25))
    }

    @Test
    fun extrapolate() {
        val velocity = PoseVelocity(10.cm.perSecond, (-20).cm.perSecond, 90.degrees.perSecond)
        assertClose(Pose(5.cm, (-10).cm, 45.degrees), Pose.ZERO.extrapolate(velocity, 0.5.seconds))
    }

    @Test
    fun speed() {
        assertClose(5.cm, PoseVelocity(3.cm.perSecond, 4.cm.perSecond, 0.degrees.perSecond).speed * 1.seconds)
    }
}
