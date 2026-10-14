package nl.beargineers.platform.control

import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Turns a flickering condition into a steady one: the result changes only after the condition has stayed changed
 * for [delay]. Like "a target is in view" from a camera that misses it for a frame now and then.
 */
class Debouncer(
    private val delay: Duration,
    initial: Boolean = false,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    var value: Boolean = initial
        private set

    private var changedSince: TimeMark? = null

    fun update(condition: Boolean): Boolean {
        if (condition == value) {
            changedSince = null
        } else {
            val since = changedSince ?: timeSource.markNow().also { changedSince = it }
            if (since.elapsedNow() >= delay) {
                value = condition
                changedSince = null
            }
        }
        return value
    }
}

/**
 * An on/off decision that doesn't flicker around its threshold. With [on] above [off], it turns on at [on] or above
 * and back off only at [off] or below, like running a flywheel at full power while it is far below its target speed.
 * With [on] below [off], it works the other way around: on at [on] or below, off at [off] or above, like a ball being
 * in when a distance sensor sees it closer than 13 cm, and out only once it is farther than 17.
 */
class Hysteresis(val on: Double, val off: Double, initial: Boolean = false) {
    init {
        require(on != off) { "Hysteresis needs a gap between where it turns on and off, but both are $on" }
    }

    var value: Boolean = initial
        private set

    fun update(input: Double): Boolean {
        if (on > off) {
            if (input >= on) value = true else if (input <= off) value = false
        } else {
            if (input <= on) value = true else if (input >= off) value = false
        }
        return value
    }
}

/**
 * Limits how fast a value may change: at most [ratePerSecond] per second. Like motor power ramping up to a new
 * setting rather than jumping, which is easier on gears and keeps a robot from tipping.
 */
class SlewRateLimiter(
    private val ratePerSecond: Double,
    initial: Double = 0.0,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    var value: Double = initial
        private set

    private val start = timeSource.markNow()
    private var lastUpdate = Duration.ZERO

    /** Moves the value toward [target] as far as the rate allows since the last update */
    fun update(target: Double): Double {
        val now = start.elapsedNow()
        val maxChange = ratePerSecond * (now - lastUpdate).toDouble(DurationUnit.SECONDS)
        lastUpdate = now
        value += (target - value).coerceIn(-maxChange, maxChange)
        return value
    }
}
