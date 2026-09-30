package org.beargineers.platform.drive

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.beargineers.platform.component.Component
import org.beargineers.platform.component.Robot
import org.beargineers.platform.component.RobotHost
import org.beargineers.platform.component.RobotRunner
import org.beargineers.platform.config.Config
import org.beargineers.platform.input.Controls
import org.beargineers.platform.runtime.everyTick
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

private class TestPad(host: RobotHost) : Controls(host, "pad") {
    var moveX = 0.0
    var moveY = 0.0
    var turnX = 0.0

    val move = stick({ moveX }, { moveY })
    val turn = axis { turnX }
}

private class FakeDrive(parent: Component) : Drivetrain(parent, "drive") {
    var driven: List<Double>? = null

    override val canStrafe: Boolean get() = true

    override fun drive(forward: Double, right: Double, turn: Double) {
        driven = listOf(forward, right, turn)
    }
}

private class TestRobot(host: RobotHost) : Robot(host) {
    val drive = FakeDrive(this)
}

class DrivetrainTest {
    private val time = TestTimeSource()
    private val runner = RobotRunner(time)
    private val pad = TestPad(runner)
    private val robot = TestRobot(runner)

    @BeforeTest
    fun setUp() {
        Config.load("pad.deadband = 0")
        runner.init()
    }

    private fun tick() {
        time += 10.milliseconds
        runner.tick()
    }

    private fun assertDrove(forward: Double, right: Double, turn: Double) {
        val driven = robot.drive.driven ?: error("didn't drive")
        assertEquals(forward, driven[0], 1e-9, "forward")
        assertEquals(right, driven[1], 1e-9, "right")
        assertEquals(turn, driven[2], 1e-9, "turn")
    }

    @Test
    fun mecanumWheelsForEachDirection() {
        assertEquals(MecanumPowers(0.5, 0.5, 0.5, 0.5), MecanumPowers.of(forward = 0.5, right = 0.0, turn = 0.0))
        assertEquals(MecanumPowers(0.5, -0.5, -0.5, 0.5), MecanumPowers.of(forward = 0.0, right = 0.5, turn = 0.0))
        assertEquals(MecanumPowers(-0.5, 0.5, -0.5, 0.5), MecanumPowers.of(forward = 0.0, right = 0.0, turn = 0.5))
    }

    @Test
    fun tooMuchPowerIsScaledDownKeepingTheDirection() {
        // Full forward and full turn: the right wheels would need 2.0
        assertEquals(MecanumPowers(0.0, 1.0, 0.0, 1.0), MecanumPowers.of(forward = 1.0, right = 0.0, turn = 1.0))
        // The right back wheel would need 2.0
        assertEquals(MecanumPowers(0.0, 0.5, -0.5, 1.0), MecanumPowers.of(forward = 0.5, right = 0.5, turn = 1.0))
        // What fits isn't scaled up
        assertEquals(MecanumPowers(0.75, 0.25, 0.25, 0.75), MecanumPowers.of(forward = 0.5, right = 0.25, turn = 0.0))
    }

    @Test
    fun sticksDriveAndTurnClockwiseWhenPushedRight() {
        pad.moveY = 1.0
        pad.moveX = 0.25
        pad.turnX = 0.5
        tick()
        robot.drive.driveWith(pad.move, pad.turn)
        assertDrove(forward = 1.0, right = 0.25, turn = -0.5)
    }

    @Test
    fun actionsKeepDrivingUntilAStickIsPushedFar() {
        runner.start {
            launch { robot.drive.takeOver("drive to target") { awaitCancellation() } }
            everyTick { robot.drive.driveWith(pad.move, pad.turn) }
        }
        tick()
        assertEquals("drive to target", robot.drive.currentAction)
        // In the first loop the sticks drove before the action started
        robot.drive.driven = null

        pad.moveX = 0.3
        tick()
        assertEquals("drive to target", robot.drive.currentAction)
        assertNull(robot.drive.driven)

        pad.moveX = 0.8
        tick()
        assertFalse(robot.drive.isBusy)
        assertDrove(forward = 0.0, right = 0.8, turn = 0.0)

        // Once the action is gone, small pushes drive too
        pad.moveX = 0.1
        tick()
        assertNull(robot.drive.currentAction)
        assertDrove(forward = 0.0, right = 0.1, turn = 0.0)
    }
}
