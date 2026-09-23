package org.beargineers.platform.geometry

import org.beargineers.platform.units.Angle
import org.beargineers.platform.units.Distance
import org.beargineers.platform.units.Heading
import org.beargineers.platform.units.atan2
import org.beargineers.platform.units.cm
import org.beargineers.platform.units.cos
import org.beargineers.platform.units.hypot
import org.beargineers.platform.units.sin

/**
 * A point on the field in field coordinates (see [Field]).
 *
 * Also works as a field-relative vector: `target - current` is the displacement from `current` to `target`.
 */
data class Point(val x: Distance, val y: Distance) {
    operator fun plus(other: Point): Point = Point(x + other.x, y + other.y)
    operator fun minus(other: Point): Point = Point(x - other.x, y - other.y)
    operator fun unaryMinus(): Point = Point(-x, -y)
    operator fun times(factor: Double): Point = Point(x * factor, y * factor)
    operator fun div(divisor: Double): Point = Point(x / divisor, y / divisor)

    /** Distance from the field origin, i.e. the length of this point as a vector */
    val length: Distance get() = hypot(x, y)

    /** Direction from the field origin to this point, i.e. the direction of this point as a vector */
    val direction: Heading get() = atan2(y, x).toHeading()

    fun distanceTo(other: Point): Distance = (other - this).length

    /** Heading a robot standing here needs in order to face [other] */
    fun headingTo(other: Point): Heading = (other - this).direction

    fun shiftBy(dx: Distance = 0.cm, dy: Distance = 0.cm): Point = Point(x + dx, y + dy)

    /** This point rotated counterclockwise around the field origin */
    fun rotateBy(angle: Angle): Point {
        val c = cos(angle)
        val s = sin(angle)
        return Point(x * c - y * s, x * s + y * c)
    }

    /** Point [t] of the way from this point to [other]: 0.0 gives this point, 1.0 gives [other] */
    fun lerp(other: Point, t: Double): Point = this + (other - this) * t

    fun withHeading(heading: Heading): Pose = Pose(x, y, heading)

    /** This point with the heading written as an angle from +x: `point.withHeading(90.degrees)` */
    fun withHeading(heading: Angle): Pose = Pose(x, y, heading.toHeading())

    /** Where this point is as seen by a robot standing at [pose] */
    fun relativeTo(pose: Pose): RobotOffset {
        val dx = x - pose.x
        val dy = y - pose.y
        val c = cos(pose.heading)
        val s = sin(pose.heading)
        return RobotOffset(forward = dx * c + dy * s, right = dx * s - dy * c)
    }

    override fun toString(): String = "($x, $y)"

    companion object {
        val ZERO: Point = Point(0.cm, 0.cm)
    }
}
