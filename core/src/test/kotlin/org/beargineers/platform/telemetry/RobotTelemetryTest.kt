package org.beargineers.platform.telemetry

import org.beargineers.platform.component.Component
import org.beargineers.platform.component.Robot
import org.beargineers.platform.component.RobotHost
import org.beargineers.platform.component.RobotRunner
import org.beargineers.platform.config.Config
import org.beargineers.platform.units.degrees
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

private class Turret(parent: Component) : Component(parent) {
    override fun update() {
        show("Angle", 90.degrees)
        graph("turret error", 1.5)
    }

    fun reachedTarget() = log("reached target")
}

private class TestRobot(host: RobotHost) : Robot(host) {
    val turret = Turret(this)
}

class RobotTelemetryTest {
    private val time = TestTimeSource()
    private val start = time.markNow()
    private val logged = mutableListOf<String>()
    private val telemetry = RobotTelemetry({ start.elapsedNow() }) { logged += it }

    @BeforeTest
    fun emptyConfig() {
        Config.load("")
    }

    @Test
    fun linesOfOneLoop() {
        telemetry.startLoop()
        telemetry.show("Power", 0.5)
        telemetry.show("Ready")
        assertEquals(listOf("Power: 0.5", "Ready"), telemetry.finishLoop())
        assertEquals(listOf("Power: 0.5", "Ready"), telemetry.lines)

        time += 100.milliseconds
        telemetry.startLoop()
        assertEquals(emptyList(), telemetry.finishLoop())
    }

    @Test
    fun linesAreCollectedOncePerUpdateInterval() {
        var computed = 0
        val published = (0 until 25).map {
            telemetry.startLoop()
            telemetry.show("Loop", it)
            telemetry.show("Costly") { computed++ }
            telemetry.finishLoop().also { time += 10.milliseconds }
        }
        assertEquals(listOf(0, 10, 20), published.indices.filter { published[it] != null })
        assertEquals(listOf("Loop: 20", "Costly: 2"), telemetry.lines)
        assertEquals(3, computed)
    }

    private val timer = LoopTimer(telemetry)

    private fun loopsOf(vararg lengths: Int) {
        if (lengths.isEmpty()) timer.loopStarted(start.elapsedNow())
        for (ms in lengths) {
            time += ms.milliseconds
            timer.loopStarted(start.elapsedNow())
        }
    }

    @Test
    fun loopTimerShowsAverageAndLongest() {
        loopsOf()
        loopsOf(2, 3, 2)
        telemetry.startLoop()
        timer.report()
        assertEquals(listOf("Loop: 2.3 ms average, 3.0 ms longest"), telemetry.finishLoop())

        // Counting starts over after a report
        loopsOf(100)
        telemetry.startLoop()
        timer.report()
        assertEquals(listOf("Loop: 100.0 ms average, 100.0 ms longest"), telemetry.finishLoop())
    }

    @Test
    fun loopTimeIsGraphedEvery10msAndLongLoopsAlways() {
        loopsOf()
        loopsOf(3, 3, 3, 3, 15, 3, 3, 3, 3)
        assertEquals(listOf(3.0, 15.0, 3.0), telemetry.samplesSince(0).series.getValue("loop ms").map { it.value })
    }

    @Test
    fun numbersAreShownShort() {
        assertEquals("12", formatValue(12.0))
        assertEquals("0.333", formatValue(1.0 / 3))
        assertEquals("0.3", formatValue(0.1 + 0.2))
        assertEquals("0", formatValue(-0.0001))
        assertEquals("none", formatValue(null))
        assertEquals("90.0°", formatValue(90.degrees))
    }

    @Test
    fun linesGoToTheLogOncePerInterval() {
        repeat(25) {
            telemetry.startLoop()
            telemetry.show("Loop", it)
            telemetry.finishLoop()
            time += 100.milliseconds
        }
        assertEquals(listOf("Telemetry: Loop: 0", "Telemetry: Loop: 10", "Telemetry: Loop: 20"), logged)
    }

    @Test
    fun logIntervalZeroTurnsItOff() {
        Config.load("telemetry.logInterval = 0s")
        telemetry.startLoop()
        telemetry.show("Loop", 1)
        telemetry.finishLoop()
        assertEquals(emptyList(), logged)
    }

    @Test
    fun graphsKeepAtMostOneSamplePer10ms() {
        repeat(10) {
            telemetry.graph("error", it.toDouble())
            time += 2.milliseconds
        }
        time += 10.milliseconds
        telemetry.graph("error", 100.0)

        val samples = telemetry.samplesSince(0).series.getValue("error")
        assertEquals(listOf(0.0, 5.0, 100.0), samples.map { it.value })
    }

    @Test
    fun samplesSinceAskForWhatIsNew() {
        telemetry.graph("a", 1.0)
        time += 20.milliseconds
        telemetry.graph("b", 2.0)
        val first = telemetry.samplesSince(0)
        assertEquals(setOf("a", "b"), first.series.keys)

        time += 20.milliseconds
        telemetry.graph("b", 3.0)
        val next = telemetry.samplesSince(first.seq)
        assertEquals(mapOf("b" to listOf(3.0)), next.series.mapValues { (_, samples) -> samples.map { it.value } })
        assertEquals(0.04, next.lastTime!!, 1e-9)
    }

    @Test
    fun graphsForgetOldSamples() {
        repeat(3100) {
            telemetry.graph("error", it.toDouble())
            time += 10.milliseconds
        }
        val samples = telemetry.samplesSince(0).series.getValue("error")
        assertEquals(3000, samples.size)
        assertEquals(100.0, samples.first().value)
    }

    @Test
    fun graphsSkipValuesThatArentNumbers() {
        telemetry.graph("error", Double.NaN)
        assertTrue(telemetry.samplesSince(0).series.isEmpty())
        assertNull(telemetry.samplesSince(0).lastTime)
    }

    @Test
    fun componentsShowGraphAndLog() {
        val logged = mutableListOf<String>()
        val runner = RobotRunner(TestTimeSource()) { logged += it }
        val robot = TestRobot(runner)
        runner.init()
        runner.telemetry.startLoop()
        runner.tick()
        assertEquals(listOf("Angle: 90.0°"), runner.telemetry.finishLoop())
        assertEquals(setOf("turret error"), runner.telemetry.samplesSince(0).series.keys)

        robot.turret.reachedTarget()
        assertTrue("turret: reached target" in logged, "$logged")
    }
}
