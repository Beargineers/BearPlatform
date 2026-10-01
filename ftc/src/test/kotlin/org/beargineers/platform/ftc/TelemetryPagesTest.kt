package org.beargineers.platform.ftc

import com.qualcomm.robotcore.hardware.VoltageSensor
import org.beargineers.platform.config.Config
import org.beargineers.platform.telemetry.RobotTelemetry
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class BatteryMonitorTest {
    private val time = TestTimeSource()
    private val start = time.markNow()
    private val logged = mutableListOf<String>()
    private val telemetry = RobotTelemetry({ start.elapsedNow() }) { logged += it }
    private var volts = listOf(12.6)
    private var reads = 0
    private val battery = BatteryMonitor({ volts.map(::sensor) }, telemetry, time)

    private fun sensor(value: Double): VoltageSensor = fake { method, _ ->
        if (method == "getVoltage") value.also { reads++ } else Unanswered
    }

    @BeforeTest
    fun emptyConfig() {
        Config.load("")
    }

    private fun loop(): List<String>? {
        telemetry.startLoop()
        battery.update()
        return telemetry.finishLoop()
    }

    @Test
    fun showsTheVoltageAndTheLowest() {
        assertEquals(listOf("Battery: 12.6 V, lowest 12.6 V"), loop())
        volts = listOf(11.9)
        time += 250.milliseconds
        loop()
        volts = listOf(12.4)
        time += 250.milliseconds
        assertEquals(listOf("Battery: 12.4 V, lowest 11.9 V"), loop())
        assertEquals(listOf(12.6, 11.9, 12.4), telemetry.samplesSince(0).series.getValue("battery").map { it.value })
    }

    @Test
    fun readsOnlyFourTimesASecond() {
        repeat(100) {
            loop()
            time += 10.milliseconds
        }
        assertEquals(4, reads)
    }

    @Test
    fun theLowestHubCounts() {
        volts = listOf(12.8, 12.1)
        loop()
        assertEquals(12.1, battery.voltage)
    }

    @Test
    fun warnsWhenLowOrMissing() {
        loop()
        assertNull(battery.warning())

        Config.load("battery.low = 12.8")
        assertEquals("Battery at 12.6 V, below battery.low = 12.8 V: charge or swap it", battery.warning())

        volts = listOf(0.0)
        time += 250.milliseconds
        assertEquals(listOf("Battery: no reading"), loop())
        assertTrue(battery.warning()!!.startsWith("Battery: no voltage reading"))
    }

    @Test
    fun logsTheLowestAtTheEnd() {
        loop()
        battery.logLowest()
        assertEquals(listOf("Battery: lowest 12.6 V during the opmode"), logged.filter { it.startsWith("Battery") })
    }
}

class GraphsPageTest {
    private val time = TestTimeSource()
    private val start = time.markNow()
    private val telemetry = RobotTelemetry({ start.elapsedNow() }) {}

    @BeforeTest
    fun emptyConfig() {
        Config.load("")
    }

    @Test
    fun dataHasSamplesLinesAndTime() {
        time += 1500.milliseconds
        telemetry.graph("turn error", 12.5)
        telemetry.startLoop()
        telemetry.show("Action", "turn \"by\" 90°")
        telemetry.finishLoop()

        assertEquals(
            """{"run":3,"running":true,"seq":1,"time":1.5,"lines":["Action: turn \"by\" 90°"],""" +
                """"commands":[],"series":{"turn error":[[1.5,12.5]]}}""",
            GraphsPage.data(telemetry, run = 3, running = true, since = 0)
        )
    }

    @Test
    fun onlyNewSamples() {
        telemetry.graph("a", 1.0)
        time += 20.milliseconds
        telemetry.graph("a", 2.0)
        val data = GraphsPage.data(telemetry, run = 1, running = true, since = 1)
        assertTrue(""""series":{"a":[[0.02,2.0]]}""" in data, data)
    }

    @Test
    fun afterTheOpModeTimeStopsAtTheLastSample() {
        telemetry.graph("a", 1.0)
        time += 5000.milliseconds
        assertTrue(""""time":0.0""" in GraphsPage.data(telemetry, run = 1, running = false, since = 0))
    }

    @Test
    fun beforeAnyOpMode() {
        assertEquals(
            """{"run":0,"running":false,"seq":0,"time":0.0,"lines":[],"commands":[],"series":{}}""",
            GraphsPage.data(null, run = 0, running = false, since = 0)
        )
    }

    @Test
    fun pageLinksToConfigAndPolls() {
        val html = GraphsPage.html()
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue("""<a href="/bear/config">Config</a> · <strong>Graphs</strong>""" in html, html)
        assertTrue("fetch('/bear/graphs/data?since=' + since" in html)
    }
}
