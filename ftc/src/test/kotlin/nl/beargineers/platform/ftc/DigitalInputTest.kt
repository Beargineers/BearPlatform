package nl.beargineers.platform.ftc

import com.qualcomm.robotcore.hardware.DigitalChannel
import nl.beargineers.platform.component.Robot
import nl.beargineers.platform.component.RobotHost
import nl.beargineers.platform.config.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private class IntakeRobot(host: RobotHost) : Robot(host) {
    // A beam break: its line goes low while something blocks the beam
    val beam = DigitalInput(this, "beam", onWhenLow = true)
    val limit = DigitalInput(this, "limit", debounce = 30.milliseconds)
}

class DigitalInputTest {
    private val host = TestFtcHost()
    private val beam = FakeDigitalChannel()
    private val limit = FakeDigitalChannel()
    private lateinit var robot: IntakeRobot

    @BeforeTest
    fun setUp() {
        Config.load("")
        host.hardware.add("beam", beam.device)
        host.hardware.add("limit", limit.device)
        limit.state = false
        robot = IntakeRobot(host)
        host.runner.init()
        tick()
    }

    private fun tick(time: Duration = 10.milliseconds) {
        repeat((time / 10.milliseconds).toInt()) {
            host.time += 10.milliseconds
            host.runner.tick()
        }
    }

    @Test
    fun readsAsAnInputWithOnWhenLow() {
        assertEquals(DigitalChannel.Mode.INPUT, beam.mode)
        assertFalse(robot.beam.isOn)

        beam.state = false
        tick()
        assertTrue(robot.beam.isOn)
        assertTrue(robot.beam.turnedOn)
        tick(50.milliseconds)
        assertFalse(robot.beam.turnedOn)
        assertEquals(50.milliseconds, robot.beam.onFor)

        Config.load("beam.onWhenLow = no")
        tick()
        assertFalse(robot.beam.isOn)
        assertTrue(robot.beam.turnedOff)
    }

    @Test
    fun handlersCountWhatPassesTheBeam() {
        var balls = 0
        host.runner.start { robot.beam.onTurnOn { balls++ } }
        tick()
        repeat(3) {
            beam.state = false
            tick(30.milliseconds)
            beam.state = true
            tick(30.milliseconds)
        }
        assertEquals(3, balls)
    }

    @Test
    fun awaitOnWaitsForTheNextTurnOn() {
        var done = false
        host.runner.start {
            robot.beam.awaitOn()
            done = true
        }
        tick(50.milliseconds)
        assertFalse(done)
        beam.state = false
        tick()
        assertTrue(done)
    }

    @Test
    fun debounceIgnoresFlickers() {
        limit.state = true
        tick(20.milliseconds)
        limit.state = false
        tick()
        assertFalse(robot.limit.isOn, "on for 20 ms only")

        limit.state = true
        tick(20.milliseconds)
        assertFalse(robot.limit.isOn)
        tick(20.milliseconds)
        assertTrue(robot.limit.isOn, "on for 30 ms and more")
    }
}
