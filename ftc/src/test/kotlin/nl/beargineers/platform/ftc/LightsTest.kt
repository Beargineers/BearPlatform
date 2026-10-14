package nl.beargineers.platform.ftc

import nl.beargineers.platform.component.Robot
import nl.beargineers.platform.component.RobotHost
import nl.beargineers.platform.config.Config
import nl.beargineers.platform.indicator.Pattern
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

private class LightsRobot(host: RobotHost) : Robot(host) {
    val light = RgbLight(this)
    val leds = RevLeds(this)
}

class LightsTest {
    private val host = TestFtcHost()
    private val servo = FakeServo()
    private val red = List(3) { FakeLed() }
    private val green = List(3) { FakeLed() }
    private lateinit var robot: LightsRobot

    @BeforeTest
    fun setUp() {
        Config.load("")
        host.hardware.add("light", servo.device)
        for (i in 0..2) {
            host.hardware.add("led${i + 1}red", red[i].device)
            host.hardware.add("led${i + 1}green", green[i].device)
        }
        robot = LightsRobot(host)
        host.runner.init()
    }

    private fun tick() {
        host.time += 10.milliseconds
        host.runner.tick()
    }

    private fun shown() = (0..2).joinToString("") { i ->
        when {
            red[i].isOn && green[i].isOn -> "A"
            red[i].isOn -> "R"
            green[i].isOn -> "G"
            else -> "0"
        }
    }

    @Test
    fun theRgbLightTakesTheWholePulseRangeAndOnlyChangesAreSent() {
        assertEquals(500.0, servo.pwmRange.usPulseLower)
        assertEquals(2500.0, servo.pwmRange.usPulseUpper)
        assertEquals(RgbColor.OFF.position, servo.position, "off from init")

        val balls = robot.light.signal()
        repeat(3) {
            balls.show(RgbColor.GREEN)
            tick()
        }
        assertEquals(0.5, servo.position)
        assertEquals(2, servo.positionWrites)
    }

    @Test
    fun revLedsLightTheirColorsWritingOnlyTheLedsThatChange() {
        assertEquals("000", shown())
        assertEquals(6, red.sumOf { it.writes } + green.sumOf { it.writes }, "all off at init")

        val signal = robot.leds.signal()
        signal.show("GG0")
        tick()
        assertEquals("GG0", shown())
        assertEquals(2, green.sumOf { it.writes } - 3, "two greens on")
        assertEquals(3, red.sumOf { it.writes }, "reds untouched")

        signal.show("AR0")
        tick()
        assertEquals("AR0", shown())
    }

    @Test
    fun revLedPatterns() {
        val chase = Pattern("G00" to 100.milliseconds, "0G0" to 100.milliseconds, "00G" to 100.milliseconds)
        robot.leds.signal().show(chase)
        val seen = mutableListOf<String>()
        repeat(30) {
            tick()
            if (shown() != seen.lastOrNull()) seen += shown()
        }
        assertEquals(listOf("G00", "0G0", "00G", "G00"), seen)
    }

    @Test
    fun revLedsSayWhatTheyCanShow() {
        val signal = robot.leds.signal()
        val thrown = assertFailsWith<IllegalArgumentException> { signal.show("GY0") }
        assertEquals("leds shows one letter per LED, R, G, A or 0, like \"G00\": not \"GY0\"", thrown.message)
        assertFailsWith<IllegalArgumentException> { signal.show("GG") }
    }

    @Test
    fun offWhenStopped() {
        robot.light.signal().show(RgbColor.BLUE)
        robot.leds.signal().show("RRR")
        tick()
        host.runner.stop()
        assertEquals(RgbColor.OFF.position, servo.position)
        assertEquals("000", shown())
    }
}
