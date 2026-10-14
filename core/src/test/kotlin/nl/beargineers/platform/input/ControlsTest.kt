package nl.beargineers.platform.input

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import nl.beargineers.platform.component.RobotHost
import nl.beargineers.platform.component.RobotRunner
import nl.beargineers.platform.config.Config
import nl.beargineers.platform.runtime.everyTick
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

private class TestPad(host: RobotHost) : Controls(host, "pad") {
    var aDown = false
    var triggerRaw = 0.0
    var stickX = 0.0
    var stickY = 0.0

    val a = button { aDown }
    val trigger = axis { triggerRaw }
    val stick = stick({ stickX }, { stickY })
}

class ControlsTest {
    private val time = TestTimeSource()
    private val runner = RobotRunner(time)
    private val pad = TestPad(runner)
    private val events = mutableListOf<String>()

    @BeforeTest
    fun emptyConfig() {
        Config.load("")
    }

    /** One loop, 10ms after the previous one */
    private fun tick(times: Int = 1) {
        repeat(times) {
            time += 10.milliseconds
            runner.tick()
        }
    }

    private fun press() {
        pad.aDown = true
        tick()
    }

    private fun release() {
        pad.aDown = false
        tick()
    }

    @Test
    fun pressAndReleaseLastOneLoop() {
        press()
        assertTrue(pad.a.isDown)
        assertTrue(pad.a.wasPressed)
        tick()
        assertTrue(pad.a.isDown)
        assertFalse(pad.a.wasPressed)
        release()
        assertTrue(pad.a.wasReleased)
        tick()
        assertFalse(pad.a.wasReleased)
    }

    @Test
    fun everythingInALoopSeesThePress() {
        runner.start {
            everyTick { if (pad.a.wasPressed) events += "first" }
        }
        runner.runtime.launch("second") {
            everyTick { if (pad.a.wasPressed) events += "second" }
        }
        tick()
        press()
        tick()
        assertEquals(listOf("first", "second"), events)
    }

    @Test
    fun heldForFollowsTheClock() {
        press()
        tick(5)
        assertEquals(50.milliseconds, pad.a.heldFor)
        release()
        assertEquals(0.milliseconds, pad.a.heldFor)
    }

    @Test
    fun awaitPressWaitsForTheNextPress() {
        runner.start {
            pad.a.awaitPress()
            events += "first"
            pad.a.awaitPress() // the press that ended the first wait doesn't count again
            events += "second"
        }
        tick()
        press()
        assertEquals(listOf("first"), events)
        release()
        press()
        assertEquals(listOf("first", "second"), events)
    }

    @Test
    fun onPressRunsOnEveryPress() {
        runner.start {
            pad.a.onPress { events += "pressed" }
        }
        tick()
        repeat(3) {
            press()
            release()
        }
        assertEquals(3, events.size)
    }

    @Test
    fun pressingAgainRestartsTheAction() {
        runner.start {
            pad.a.onPress {
                try {
                    events += "started"
                    awaitCancellation()
                } finally {
                    events += "stopped"
                }
            }
        }
        tick()
        press()
        release()
        press()
        assertEquals(listOf("started", "stopped", "started"), events)
    }

    @Test
    fun handlersFromInitStopAtStart() {
        runner.init {
            pad.a.onPress { events += "menu" }
        }
        tick()
        runner.start {
            pad.a.onPress { events += "match" }
        }
        tick()
        press()
        assertEquals(listOf("match"), events)
    }

    @Test
    fun tapAndHoldOnOneButton() {
        runner.start {
            pad.a.onTap(within = 100.milliseconds) { events += "tap" }
            pad.a.whileHeld(after = 100.milliseconds) {
                try {
                    events += "hold started"
                    awaitCancellation()
                } finally {
                    events += "hold stopped"
                }
            }
        }
        tick()

        press()
        tick(3)
        release()
        assertEquals(listOf("tap"), events)

        events.clear()
        press()
        tick(15)
        assertEquals(listOf("hold started"), events)
        release()
        assertEquals(listOf("hold started", "hold stopped"), events)

        events.clear()
        press()
        release()
        assertEquals(listOf("tap"), events, "a tap right after a hold still counts")
    }

    @Test
    fun whileHeldActionCanFinishEarly() {
        runner.start {
            pad.a.whileHeld {
                delay(20.milliseconds)
                events += "done"
            }
        }
        tick()
        press()
        tick(10)
        assertEquals(listOf("done"), events, "runs once per hold, not again while still held")
    }

    @Test
    fun axisDeadbandStretchesTheRest() {
        Config.load("pad.deadband = 0.2")
        pad.triggerRaw = 0.15
        tick()
        assertEquals(0.0, pad.trigger.value)
        pad.triggerRaw = 0.6
        tick()
        assertEquals(0.5, pad.trigger.value, 1e-9)
        pad.triggerRaw = -1.0
        tick()
        assertEquals(-1.0, pad.trigger.value, 1e-9)
    }

    @Test
    fun axisAsButton() {
        val pulled = pad.trigger.asButton(threshold = 0.3)
        pad.triggerRaw = 0.2
        tick()
        assertFalse(pulled.isDown)
        pad.triggerRaw = 0.4
        tick()
        assertTrue(pulled.wasPressed)
    }

    @Test
    fun stickMagnitude() {
        Config.load("pad.deadband = 0")
        pad.stickX = 0.6
        pad.stickY = 0.8
        tick()
        assertEquals(1.0, pad.stick.magnitude, 1e-9)
        pad.stickX = 1.0
        pad.stickY = 1.0
        tick()
        assertEquals(1.0, pad.stick.magnitude, 1e-9)
    }
}
