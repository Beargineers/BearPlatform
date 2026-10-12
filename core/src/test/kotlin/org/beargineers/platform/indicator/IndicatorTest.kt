package org.beargineers.platform.indicator

import org.beargineers.platform.component.Robot
import org.beargineers.platform.component.RobotHost
import org.beargineers.platform.component.RobotRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

private class Light(robot: Robot) : Indicator<String>(robot, "light", "off") {
    val sent = mutableListOf<String>()

    override fun checkState(state: String) {
        require(state != "ultraviolet") { "can't show $state" }
    }

    override fun display(state: String) {
        sent += state
    }
}

private class LightRobot(host: RobotHost) : Robot(host) {
    val light = Light(this)
}

class IndicatorTest {
    private val time = TestTimeSource()
    private val runner = RobotRunner(time)
    private val robot = LightRobot(runner)
    private val light = robot.light

    init {
        runner.init()
    }

    private fun tick(time: Duration = 10.milliseconds) {
        repeat((time / 10.milliseconds).toInt()) {
            this.time += 10.milliseconds
            runner.tick()
        }
    }

    @Test
    fun offUntilASignalShowsSomethingAndOnlyChangesAreSent() {
        val signal = light.signal()
        tick()
        assertEquals(listOf("off"), light.sent, "off at init")

        signal.show("green")
        tick()
        repeat(5) {
            signal.show("green")
            tick()
        }
        assertEquals("green", light.state)
        assertEquals(listOf("off", "green"), light.sent)

        signal.clear()
        tick()
        assertEquals(listOf("off", "green", "off"), light.sent)
    }

    @Test
    fun theHighestPriorityWinsAndEqualOnesTheLastAsked() {
        val balls = light.signal(priority = 10)
        val tag = light.signal(priority = 40)
        val other = light.signal(priority = 10)
        balls.show("green")
        tag.show("orange")
        tick()
        assertEquals("orange", light.state)

        tag.clear()
        tick()
        assertEquals("green", light.state)
        other.show("blue")
        tick()
        assertEquals("blue", light.state, "asked after balls")
        balls.show("red")
        tick()
        assertEquals("red", light.state)
    }

    @Test
    fun showingForAWhile() {
        val balls = light.signal(priority = 10)
        val tag = light.signal(priority = 40)
        balls.show("green")
        tag.show("orange", lasting = 2.seconds)
        tick()
        assertEquals("orange", light.state)
        tick(1.seconds)
        tag.show("orange", lasting = 2.seconds)
        tick(1.seconds)
        assertEquals("green", light.state, "asking again doesn't start it over")
        assertFalse(tag.isShowing)
    }

    @Test
    fun blinkingKeepsItsRhythmWhenAskedEveryLoop() {
        val signal = light.signal()
        val seen = mutableListOf<String>()
        repeat(100) {
            signal.blink("red", period = 250.milliseconds)
            tick()
            seen += light.state
        }
        // Started when first asked, at 0 ms: on until 250 ms, off until 500 ms, and so on; the loops are 10 ms apart
        assertEquals(List(24) { "red" } + List(25) { "off" } + List(25) { "red" } + List(25) { "off" } + "red", seen)
    }

    @Test
    fun blinkingSomeTimesThenNothing() {
        val signal = light.signal()
        signal.blink("red", period = 100.milliseconds, times = 2)
        tick(1.seconds)
        assertEquals(listOf("off", "red", "off", "red", "off"), light.sent)
        assertFalse(signal.isShowing)
    }

    @Test
    fun patterns() {
        val chase = Pattern("100" to 100.milliseconds, "010" to 100.milliseconds, "001" to 100.milliseconds, times = 2)
        light.signal().show(chase)
        tick(1.seconds)
        assertEquals(listOf("off", "100", "010", "001", "100", "010", "001", "off"), light.sent)
        assertEquals(chase, Pattern("100" to 100.milliseconds, "010" to 100.milliseconds, "001" to 100.milliseconds, times = 2))
    }

    @Test
    fun whatItCantShowFailsWhereItIsAsked() {
        val signal = light.signal()
        val thrown = assertFailsWith<IllegalArgumentException> { signal.show("ultraviolet") }
        assertEquals("can't show ultraviolet", thrown.message)
        assertFailsWith<IllegalArgumentException> { signal.show(Pattern("red" to 1.seconds, "ultraviolet" to 1.seconds)) }
        assertFailsWith<IllegalArgumentException> { Pattern("red" to Duration.ZERO) }
        assertFalse(signal.isShowing)
    }

    @Test
    fun offWhenStopped() {
        light.signal().show("green")
        tick()
        runner.stop()
        assertEquals("off", light.sent.last())
        assertTrue(light.sent.size == 3)
    }
}
