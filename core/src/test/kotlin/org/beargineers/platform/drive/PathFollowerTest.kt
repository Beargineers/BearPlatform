package org.beargineers.platform.drive

import kotlinx.coroutines.launch
import org.beargineers.platform.component.Component
import org.beargineers.platform.component.Robot
import org.beargineers.platform.component.RobotHost
import org.beargineers.platform.component.RobotRunner
import org.beargineers.platform.config.Config
import org.beargineers.platform.geometry.Pose
import org.beargineers.platform.geometry.PoseVelocity
import org.beargineers.platform.geometry.RobotOffset
import org.beargineers.platform.localization.Localizer
import org.beargineers.platform.units.cm
import org.beargineers.platform.units.degrees
import org.beargineers.platform.units.perSecond
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

private val LOOP = 10.milliseconds

private class SimLocalizer(parent: Component) : Localizer(parent) {
    override var pose: Pose = Pose.ZERO
    override var velocity: PoseVelocity = PoseVelocity.ZERO
}

/**
 * A robot without inertia: every loop it moves by its powers, at 150 cm/s and 360°/s on full power. With [friction],
 * that much of each power goes into friction, like a real robot that doesn't move on small powers.
 */
private class SimDrive(parent: Component, private val localizer: SimLocalizer) : Drivetrain(parent, "drive") {
    var forward = 0.0
    var right = 0.0
    var turn = 0.0
    var strafes = true
    var stuck = false
    var friction = 0.0

    /** Every command, with where the robot was when it came */
    val commands = mutableListOf<Pair<Pose, List<Double>>>()

    override val canStrafe: Boolean get() = strafes

    override fun drive(forward: Double, right: Double, turn: Double) {
        this.forward = forward
        this.right = right
        this.turn = turn
        commands += localizer.pose to listOf(forward, right, turn)
    }

    override fun update() {
        if (stuck) {
            localizer.velocity = PoseVelocity.ZERO
            return
        }
        val seconds = LOOP.inWholeMilliseconds / 1000.0
        // Friction takes its share of the forward and sideways power together, and of turning
        val translation = hypot(forward, right)
        val moving = if (translation > friction) (translation - friction) / translation else 0.0
        val turning = sign(turn) * maxOf(0.0, abs(turn) - friction)
        val before = localizer.pose
        localizer.pose = before + RobotOffset(
            forward = (forward * moving * 150 * seconds).cm,
            right = (right * moving * 150 * seconds).cm,
            turn = (turning * 360 * seconds).degrees,
        )
        val moved = localizer.pose.point - before.point
        localizer.velocity = PoseVelocity(moved.x / LOOP, moved.y / LOOP, (turning * 360).degrees.perSecond)
    }
}

private class SimRobot(host: RobotHost) : Robot(host) {
    val localizer = SimLocalizer(this)
    val drive = SimDrive(this, localizer)
    val follower = PathFollower(this, drive, localizer)
}

class PathFollowerTest {
    private val time = TestTimeSource()
    private val logged = mutableListOf<String>()
    private val runner = RobotRunner(time) { logged += it }
    private val robot = SimRobot(runner)
    private val drive = robot.drive

    @BeforeTest
    fun setUp() {
        Config.load("")
        runner.init()
    }

    private fun tick() {
        time += LOOP
        runner.tick()
    }

    /** Runs [action] on the robot loop until it finishes; returns its result and how long it took */
    private fun <T> run(action: suspend () -> T): Pair<T, Duration> {
        var result: Result<T>? = null
        val start = time.markNow()
        runner.start { result = runCatching { action() } }
        repeat(3000) { if (result == null) tick() }
        return (result ?: error("still driving after 30 s")).getOrThrow() to start.elapsedNow()
    }

    private fun stopped() = drive.forward == 0.0 && drive.right == 0.0 && drive.turn == 0.0

    @Test
    fun drivesToATargetAndStops() {
        val target = Pose(100.cm, 50.cm, 90.degrees)
        val (reached, _) = run { robot.follower.driveTo(target) }
        assertTrue(reached)
        assertTrue(robot.localizer.pose.distanceTo(target) <= 2.cm, "${robot.localizer.pose}")
        assertTrue(abs((robot.localizer.pose.heading - target.heading).inDegrees) <= 1.0, "${robot.localizer.pose}")
        assertTrue(stopped())
        assertTrue(logged.any { it.startsWith("follower: reached") }, "$logged")
    }

    @Test
    fun drivesThroughPointsWithoutStopping() {
        val a = Pose(100.cm, 0.cm, 0.degrees)
        val b = Pose(100.cm, 100.cm, 0.degrees)
        val c = Pose(0.cm, 100.cm, 0.degrees)
        val (_, stopping) = run {
            robot.follower.driveTo(a)
            robot.follower.driveTo(b)
            robot.follower.driveTo(c)
        }
        robot.localizer.pose = Pose.ZERO
        val (reached, passing) = run { robot.follower.drivePath(Waypoint(a), Waypoint(b), Waypoint(c)) }
        assertTrue(reached)
        assertTrue(robot.localizer.pose.distanceTo(c) <= 2.cm, "${robot.localizer.pose}")
        assertTrue(passing < stopping, "through the points took $passing, stopping at each $stopping")
    }

    @Test
    fun speedLimitsThePower() {
        run { robot.follower.driveTo(Pose(200.cm, 100.cm, 180.degrees), speed = 0.3) }
        for ((_, powers) in drive.commands) {
            assertTrue(hypot(powers[0], powers[1]) <= 0.3 + 1e-9, "$powers")
            assertTrue(abs(powers[2]) <= 0.3 + 1e-9, "$powers")
        }
    }

    @Test
    fun frictionPowerPushesTowardTheTarget() {
        // Standing still, so the first powers are P and friction alone: p = 0.03 forward, 0.045 sideways
        drive.stuck = true
        run { robot.follower.driveTo(Pose(5.cm, 0.cm, 0.degrees)) }
        assertPowers(0.15 + 0.08, 0.0, 0.0, drive.commands.first().second)

        drive.commands.clear()
        run { robot.follower.driveTo(Pose(0.cm, 5.cm, 0.degrees)) }
        assertPowers(0.0, -(0.225 + 0.14), 0.0, drive.commands.first().second)

        // Diagonally, friction power blends between forward's and sideways'
        drive.commands.clear()
        run { robot.follower.driveTo(Pose(5.cm, -5.cm, 0.degrees)) }
        val friction = hypot(0.08, 0.14) / Math.sqrt(2.0)
        assertPowers(0.15 + friction / Math.sqrt(2.0), 0.225 + friction / Math.sqrt(2.0), 0.0, drive.commands.first().second)
    }

    private fun assertPowers(forward: Double, right: Double, turn: Double, powers: List<Double>) {
        assertEquals(forward, powers[0], 1e-9, "forward of $powers")
        assertEquals(right, powers[1], 1e-9, "right of $powers")
        assertEquals(turn, powers[2], 1e-9, "turn of $powers")
    }

    @Test
    fun givesUpWhenStalled() {
        drive.stuck = true
        val (reached, took) = run { robot.follower.driveTo(Pose(100.cm, 0.cm, 0.degrees)) }
        assertFalse(reached)
        // After stallTime without getting closer
        assertTrue(took in 500.milliseconds..600.milliseconds, "gave up after $took")
        assertTrue(stopped())
        assertTrue(logged.any { "gave up" in it && "stalled" in it }, "$logged")
        assertTrue(logged.any { it.startsWith("follower: stopped short of") }, "$logged")
    }

    @Test
    fun frictionPowerGetsARealRobotThere() {
        // Friction eats 0.07 of every power: the PIDs alone would stop short, and slow progress isn't a stall
        drive.friction = 0.07
        val target = Pose(60.cm, 20.cm, 90.degrees)
        val (reached, _) = run { robot.follower.driveTo(target) }
        assertTrue(reached, "$logged")
        assertTrue(robot.localizer.pose.distanceTo(target) <= 2.cm, "${robot.localizer.pose}")

        Config.load("follower.frictionPower = 0\nfollower.frictionTurnPower = 0")
        val (withoutFriction, _) = run { robot.follower.driveTo(Pose.ZERO) }
        assertFalse(withoutFriction, "stops short without friction power")
    }

    @Test
    fun stoppingTheActionStopsTheMotors() {
        var ended: Throwable? = null
        runner.start {
            launch {
                try {
                    robot.follower.driveTo(Pose(300.cm, 0.cm, 0.degrees))
                } catch (e: Throwable) {
                    ended = e
                    throw e
                }
            }
        }
        repeat(20) { tick() }
        assertTrue(drive.forward > 0.0)
        drive.cancelAction()
        tick()
        assertTrue(stopped())
        assertTrue(ended is kotlinx.coroutines.CancellationException, "$ended")
    }

    @Test
    fun keepsDrivingWhenNotStoppingAtTheEnd() {
        val (reached, _) = run { robot.follower.drivePath(listOf(Waypoint(Pose(100.cm, 0.cm, 0.degrees))), stopAtEnd = false) }
        assertTrue(reached)
        assertTrue(drive.forward > 0.0, "still driving at the end")
    }

    @Test
    fun needsADrivetrainThatCanStrafe() {
        drive.strafes = false
        val thrown = assertFailsWith<IllegalStateException> { run { robot.follower.driveTo(Pose.ZERO) } }
        assertEquals("drive can't strafe, and PathFollower needs a drivetrain that can", thrown.message)
    }
}
