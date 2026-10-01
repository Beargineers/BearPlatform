package org.beargineers.platform.drive

import org.beargineers.platform.component.Component
import org.beargineers.platform.config.config
import org.beargineers.platform.control.Pid
import org.beargineers.platform.control.PidGains
import org.beargineers.platform.geometry.Pose
import org.beargineers.platform.geometry.RobotOffset
import org.beargineers.platform.localization.Localizer
import org.beargineers.platform.runtime.nextTick
import org.beargineers.platform.units.Angle
import org.beargineers.platform.units.Distance
import org.beargineers.platform.units.abs
import org.beargineers.platform.units.cm
import org.beargineers.platform.units.degrees
import org.beargineers.platform.units.sin
import org.beargineers.platform.units.cos
import org.beargineers.platform.units.perSecond
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

/**
 * A point to drive to or through: where the robot's center should be and which way it should face. [speed] limits
 * the power, from 0.0 to 1.0. [tolerance] and [headingTolerance] say how close counts as there; when left out, the
 * follower's settings, which are looser for points the robot drives through than for where it stops.
 */
data class Waypoint(
    val pose: Pose,
    val speed: Double = 1.0,
    val tolerance: Distance? = null,
    val headingTolerance: Angle? = null,
)

/**
 * Drives the robot to poses on the field, and along paths of them, using where its [localizer] says it is. Three PIDs
 * turn how far the robot still has to go, forward, sideways and around, into powers for the [drive], which has to be
 * able to strafe. Create it after both: `val follower = PathFollower(this, drive, localizer)`.
 *
 * ```
 * follower.driveTo(SPIKE_1)                                     // drives there and stops
 * follower.drivePath(Waypoint(A), Waypoint(B, speed = 0.5), Waypoint(C))   // through A and B, stops at C
 * ```
 *
 * Driving takes over the drivetrain, so a new drive action, or the sticks pushed past halfway, stop it, and the motors
 * stop too. Settings start with `follower.`; tune the gains live while watching the `forward error cm`,
 * `strafe error cm` and `heading error deg` graphs, next to the powers they turn into (`forward power`, `strafe power`,
 * `turn power`). The log tells how each drive ended.
 */
class PathFollower(
    parent: Component,
    private val drive: Drivetrain,
    private val localizer: Localizer,
    name: String = "follower",
) : Component(parent, name) {
    // The defaults were tuned on a 12 kg mecanum robot, with drive.nominalVoltage = 12: 60 cm in 0.9 s and a quarter
    // turn in 0.7 s, within 1 cm and 1°, without overshooting

    /** Power per cm the robot still has to go forward */
    val forwardGains by config(PidGains(p = 0.03, i = 0.002, d = 0.005))

    /** Power per cm the robot still has to go sideways */
    val strafeGains by config(PidGains(p = 0.045, d = 0.006))

    /** Power per degree the robot still has to turn */
    val headingGains by config(PidGains(p = 0.04, i = 0.015, d = 0.004))

    /**
     * Power that just overcomes the wheels' friction when driving forward, added toward the target until the robot is
     * within tolerance, so the PIDs work as if there were no friction and the robot doesn't stop short. Measure it by
     * raising the power slowly: about where the robot starts to move. With a drive that has velocity control it can be
     * zero.
     */
    val frictionPower by config(0.08)

    /** Like [frictionPower], for driving sideways, which mecanum wheels need more of */
    val frictionStrafePower by config(0.14)

    /** Like [frictionPower], for turning */
    val frictionTurnPower by config(0.1)

    /** How close counts as arrived, where the robot stops */
    val tolerance by config(2.cm)
    val headingTolerance by config(1.degrees)

    /** How close counts as passed, at points the robot drives through */
    val passTolerance by config(8.cm)
    val passHeadingTolerance by config(5.degrees)

    /** Where it stops, the robot has arrived only once it is slower than this */
    val stoppedSpeed by config(10.cm.perSecond)

    /**
     * When the robot gets no closer for this long, by 0.5 cm or 0.5°, the follower gives up on the point it drives to,
     * like when pushing against a wall. Slow progress isn't a stall.
     */
    val stallTime by config(500.milliseconds)

    // Integral zones as in the DECODE follower: the integral only helps close to the target
    private val forwardPid = Pid(integralZone = 20.0, timeSource = runtime.timeSource) { forwardGains }
    private val strafePid = Pid(integralZone = 20.0, timeSource = runtime.timeSource) { strafeGains }
    private val headingPid = Pid(integralZone = 5.0, timeSource = runtime.timeSource) { headingGains }

    /** Drives to [target] and stops there. Returns false when it gave up on the way, stalled. */
    suspend fun driveTo(target: Pose, speed: Double = 1.0): Boolean = drivePath(listOf(Waypoint(target, speed)))

    /** Drives through [waypoints] in order and stops at the last. Returns false when it gave up on the last, stalled. */
    suspend fun drivePath(vararg waypoints: Waypoint): Boolean = drivePath(waypoints.asList())

    /**
     * Drives through [waypoints] in order and stops at the last one. With [stopAtEnd] false it drives through the last
     * one too and leaves the motors running, for whatever drives next. Returns false when it gave up on the last
     * point, stalled; true when it got there.
     */
    suspend fun drivePath(waypoints: List<Waypoint>, stopAtEnd: Boolean = true): Boolean {
        require(waypoints.isNotEmpty()) { "A path needs at least one waypoint" }
        check(drive.canStrafe) { "${drive.path} can't strafe, and PathFollower needs a drivetrain that can" }
        return drive.takeOver("drive to ${waypoints.last().pose}") {
            var finished = false
            try {
                follow(waypoints, stopAtEnd).also { finished = true }
            } finally {
                // Stopped by another action or the sticks, the motors stop too
                if (stopAtEnd || !finished) drive.stopMoving()
            }
        }
    }

    private suspend fun follow(waypoints: List<Waypoint>, stopAtEnd: Boolean): Boolean {
        val start = runtime.elapsed
        var index = 0
        var progress = Progress(start)
        resetPids()
        while (true) {
            val waypoint = waypoints[index]
            val isLast = index == waypoints.lastIndex
            val stopping = isLast && stopAtEnd
            val pose = localizer.pose
            val velocity = localizer.velocity
            val error = waypoint.pose.relativeTo(pose)
            val now = runtime.elapsed

            val tolerance = waypoint.tolerance ?: if (stopping) tolerance else passTolerance
            val headingTolerance = waypoint.headingTolerance ?: if (stopping) headingTolerance else passHeadingTolerance
            val closeEnough = error.length <= tolerance && abs(error.turn) <= headingTolerance
            val arrived = closeEnough && (!stopping || velocity.speed < stoppedSpeed)

            progress.update(error, now)
            val stalled = !arrived && now - progress.madeAt > stallTime

            if (arrived || stalled) {
                if (stalled) log("gave up on ${describe(index, waypoints)}: stalled at $pose, ${error.length} away")
                if (isLast) {
                    log(
                        (if (arrived) "reached" else "stopped short of") + " ${waypoint.pose} in " +
                            seconds(now - start) + ", ${error.length} and ${abs(error.turn)} off"
                    )
                    return arrived
                }
                index++
                progress = Progress(now)
                // The error jumps to the next point, which must not look like a sudden change to the PIDs
                resetPids()
                continue
            }

            steer(error, waypoint.speed, outsideTolerance = error.length > tolerance, turnOutsideTolerance = abs(error.turn) > headingTolerance)
            show("Driving to") { "${describe(index, waypoints)}, ${error.length} away" }
            nextTick()
        }
    }

    private fun steer(error: RobotOffset, speed: Double, outsideTolerance: Boolean, turnOutsideTolerance: Boolean) {
        // How fast the errors change, from the measured velocity: a derivative of the positions would jump whenever a
        // reading comes late. The target stands still; the robot's own turning swings it around the robot too.
        val velocity = localizer.velocity
        val heading = localizer.pose.heading
        val c = cos(heading)
        val s = sin(heading)
        val vx = velocity.x.inCmPerSecond
        val vy = velocity.y.inCmPerSecond
        val turning = velocity.turn.inRadiansPerSecond
        val forwardError = error.forward.inCm
        val rightError = error.right.inCm
        val forwardRate = -(vx * c + vy * s) - turning * rightError
        val rightRate = -(vx * s - vy * c) + turning * forwardError

        var forward = forwardPid.update(forwardError, forwardRate)
        var right = strafePid.update(rightError, rightRate)
        var turn = headingPid.update(error.turn.inDegrees, -velocity.turn.inDegreesPerSecond)

        graph("forward error cm", forwardError)
        graph("strafe error cm", rightError)
        graph("heading error deg", error.turn.inDegrees)

        // Friction power pushes toward the target, so it softens braking rather than doubling it. Mecanum wheels need
        // more of it sideways: in between, it blends along the way to the target.
        val distance = hypot(forwardError, rightError)
        if (outsideTolerance && distance > 0.0) {
            val towardForward = forwardError / distance
            val towardRight = rightError / distance
            val friction = hypot(frictionPower * towardForward, frictionStrafePower * towardRight)
            forward += friction * towardForward
            right += friction * towardRight
        }
        if (turnOutsideTolerance) turn += frictionTurnPower * sign(error.turn.inDegrees)

        // Forward and sideways are limited together, so the speed limit doesn't change the direction
        val translation = hypot(forward, right)
        if (translation > speed) {
            forward *= speed / translation
            right *= speed / translation
        }
        turn = turn.coerceIn(-speed, speed)

        graph("forward power", forward)
        graph("strafe power", right)
        graph("turn power", turn)
        drive.drive(forward, right, turn)
    }

    /** When the robot last got closer to the point it drives to, by at least 0.5 cm or 0.5° */
    private class Progress(var madeAt: Duration) {
        private var distance = Double.MAX_VALUE
        private var turn = Double.MAX_VALUE

        fun update(error: RobotOffset, now: Duration) {
            val distanceNow = error.length.inCm
            val turnNow = abs(error.turn).inDegrees
            if (distanceNow <= distance - 0.5 || turnNow <= turn - 0.5) {
                distance = minOf(distance, distanceNow)
                turn = minOf(turn, turnNow)
                madeAt = now
            }
        }
    }

    private fun resetPids() {
        forwardPid.reset()
        strafePid.reset()
        headingPid.reset()
    }

    private fun describe(index: Int, waypoints: List<Waypoint>): String =
        if (waypoints.size == 1) "${waypoints[index].pose}" else "point ${index + 1} of ${waypoints.size}, ${waypoints[index].pose}"

    private fun seconds(time: Duration) = String.format(Locale.US, "%.2fs", time.toDouble(DurationUnit.SECONDS))
}
