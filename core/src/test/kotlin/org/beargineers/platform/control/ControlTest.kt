package org.beargineers.platform.control

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

private const val EPS = 1e-9

class PidTest {
    private val time = TestTimeSource()
    private var gains = PidGains(p = 0.0)

    private fun pid(
        outputLimit: Double = 100.0,
        integralLimit: Double = outputLimit,
        integralZone: Double = Double.POSITIVE_INFINITY,
    ) = Pid(outputLimit, integralLimit, integralZone, timeSource = time) { gains }

    @Test
    fun proportional() {
        gains = PidGains(p = 0.5)
        val pid = pid(outputLimit = 1.0)
        assertEquals(0.5, pid.update(1.0), EPS)
        assertEquals(-0.25, pid.update(-0.5), EPS)
        assertEquals(1.0, pid.update(10.0), EPS, "clamped to the output limit")
    }

    @Test
    fun derivativeStartsAtZero() {
        gains = PidGains(p = 0.0, d = 1.0)
        val pid = pid()
        assertEquals(0.0, pid.update(5.0), EPS)
        time += 100.milliseconds
        assertEquals(10.0, pid.update(6.0), EPS, "error grew by 1 in 0.1 s")
    }

    @Test
    fun integralBuildsUpOverTime() {
        gains = PidGains(p = 0.0, i = 1.0)
        val pid = pid()
        assertEquals(0.0, pid.update(1.0), EPS)
        repeat(10) {
            time += 100.milliseconds
            pid.update(1.0)
        }
        assertEquals(1.0, pid.integralTerm, EPS)
    }

    @Test
    fun integralOnlyNearTheTarget() {
        gains = PidGains(p = 0.0, i = 1.0)
        val pid = pid(integralZone = 0.5)
        pid.update(1.0)
        repeat(10) {
            time += 100.milliseconds
            pid.update(1.0)
        }
        assertEquals(0.0, pid.integralTerm, EPS)
        repeat(10) {
            time += 100.milliseconds
            pid.update(0.25)
        }
        assertEquals(0.25, pid.integralTerm, EPS)
    }

    @Test
    fun integralIsLimited() {
        gains = PidGains(p = 0.0, i = 1.0)
        val pid = pid(integralLimit = 0.3)
        pid.update(1.0)
        repeat(20) {
            time += 100.milliseconds
            pid.update(1.0)
        }
        assertEquals(0.3, pid.integralTerm, EPS)
    }

    @Test
    fun noWindupWhileSaturated() {
        gains = PidGains(p = 1.0, i = 1.0)
        val pid = pid(outputLimit = 1.0)
        pid.update(2.0)
        repeat(50) {
            time += 100.milliseconds
            assertEquals(1.0, pid.update(2.0), EPS)
        }
        // Stuck for 5 s far from the target; once close, the output follows the error instead of an inflated integral
        time += 100.milliseconds
        assertTrue(pid.update(0.1) < 0.2)
    }

    @Test
    fun changingGainsClearsTheIntegral() {
        gains = PidGains(p = 0.0, i = 1.0)
        val pid = pid()
        pid.update(1.0)
        repeat(5) {
            time += 100.milliseconds
            pid.update(1.0)
        }
        assertEquals(0.5, pid.integralTerm, EPS)

        gains = PidGains(p = 0.0, i = 2.0)
        pid.update(1.0)
        assertEquals(0.0, pid.integralTerm, EPS)
    }

    @Test
    fun startsOverAfterAPause() {
        gains = PidGains(p = 0.0, i = 1.0, d = 1.0)
        val pid = pid()
        pid.update(0.0)
        time += 5.seconds
        assertEquals(0.0, pid.update(1.0), EPS, "no derivative or integral from the gap")
    }

    @Test
    fun derivativeSmoothing() {
        gains = PidGains(p = 0.0, d = 1.0)
        val pid = Pid(outputLimit = 100.0, derivativeSmoothing = 100.milliseconds, timeSource = time) { gains }
        pid.update(0.0)
        time += 10.milliseconds
        val first = pid.update(0.1) // the raw derivative is 10
        assertTrue(first in 0.5..1.5, "$first")
        repeat(100) {
            time += 10.milliseconds
            pid.update(0.1 * (it + 2))
        }
        assertEquals(10.0, pid.derivativeTerm, 0.01, "settles on the raw derivative when it holds steady")
    }
}

class FeedforwardTest {
    private val feedforward = Feedforward(kS = 0.05, kV = 0.01, kA = 0.001)

    @Test
    fun powerForVelocity() {
        assertEquals(0.0, feedforward.power(0.0), EPS)
        assertEquals(0.05 + 0.6, feedforward.power(60.0), EPS)
        assertEquals(-0.05 - 0.6, feedforward.power(-60.0), EPS)
    }

    @Test
    fun startingFromStandstill() {
        assertEquals(0.05 + 0.1, feedforward.power(0.0, acceleration = 100.0), EPS)
    }
}

class SignalsTest {
    private val time = TestTimeSource()

    @Test
    fun debouncerIgnoresFlicker() {
        val inView = Debouncer(50.milliseconds, timeSource = time)
        assertFalse(inView.update(true))
        time += 30.milliseconds
        assertFalse(inView.update(false), "went away before 50 ms")
        assertFalse(inView.update(true))
        time += 50.milliseconds
        assertTrue(inView.update(true))
        time += 10.milliseconds
        assertTrue(inView.update(false), "one missed frame doesn't count")
    }

    @Test
    fun hysteresis() {
        val fullPower = Hysteresis(on = 150.0, off = 50.0)
        assertFalse(fullPower.update(100.0))
        assertTrue(fullPower.update(200.0))
        assertTrue(fullPower.update(100.0), "stays on until at or below 50")
        assertFalse(fullPower.update(50.0))
        assertFailsWith<IllegalArgumentException> { Hysteresis(on = 1.0, off = 2.0) }
    }

    @Test
    fun slewRateLimiter() {
        val power = SlewRateLimiter(ratePerSecond = 2.0, timeSource = time)
        time += 100.milliseconds
        assertEquals(0.2, power.update(1.0), EPS)
        time += 100.milliseconds
        assertEquals(0.4, power.update(1.0), EPS)
        time += 1.seconds
        assertEquals(1.0, power.update(1.0), EPS)
        time += 100.milliseconds
        assertEquals(0.8, power.update(-1.0), EPS)
    }
}
