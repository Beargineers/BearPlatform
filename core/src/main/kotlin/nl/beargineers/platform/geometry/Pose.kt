package nl.beargineers.platform.geometry

import nl.beargineers.platform.units.Angle
import nl.beargineers.platform.units.Distance
import nl.beargineers.platform.units.Heading
import nl.beargineers.platform.units.cm
import nl.beargineers.platform.units.cos
import nl.beargineers.platform.units.sin
import kotlin.time.Duration

/**
 * Where the robot is on the field and which way it faces, in field coordinates (see [Field]).
 */
data class Pose(val x: Distance, val y: Distance, val heading: Heading) {
    constructor(point: Point, heading: Heading) : this(point.x, point.y, heading)

    val point: Point get() = Point(x, y)

    fun shiftBy(dx: Distance = 0.cm, dy: Distance = 0.cm): Pose = Pose(x + dx, y + dy, heading)

    /** Same place, turned counterclockwise by [angle] */
    fun turnBy(angle: Angle): Pose = Pose(x, y, heading + angle)

    /** Pose after the robot moves by [offset], which is given relative to the robot's own position and heading */
    operator fun plus(offset: RobotOffset): Pose {
        val c = cos(heading)
        val s = sin(heading)
        return Pose(
            x + offset.forward * c + offset.right * s,
            y + offset.forward * s - offset.right * c,
            heading + offset.turn
        )
    }

    /** The robot-relative move that takes a robot at [origin] to this pose. The inverse of [plus]. */
    fun relativeTo(origin: Pose): RobotOffset = point.relativeTo(origin).copy(turn = heading - origin.heading)

    fun distanceTo(other: Point): Distance = point.distanceTo(other)
    fun distanceTo(other: Pose): Distance = point.distanceTo(other.point)

    /** Heading the robot needs in order to face [target] from where it stands */
    fun headingTo(target: Point): Heading = point.headingTo(target)

    /** Pose [t] of the way from this pose to [other], turning the shorter way around */
    fun lerp(other: Pose, t: Double): Pose = Pose(point.lerp(other.point, t), heading + (other.heading - heading) * t)

    /** Where a robot moving with field-relative [velocity] will be after [time] */
    fun extrapolate(velocity: PoseVelocity, time: Duration): Pose =
        Pose(x + velocity.x * time, y + velocity.y * time, heading + velocity.turn * time)

    override fun toString(): String = "($x, $y) $heading"

    companion object {
        val ZERO: Pose = Pose(0.cm, 0.cm, Heading.ZERO)
    }
}

/** A pose with the heading written as an angle from +x: `Pose(10.cm, 20.cm, 90.degrees)` */
fun Pose(x: Distance, y: Distance, heading: Angle): Pose = Pose(x, y, heading.toHeading())

/** A pose with the heading written as an angle from +x */
fun Pose(point: Point, heading: Angle): Pose = Pose(point, heading.toHeading())
