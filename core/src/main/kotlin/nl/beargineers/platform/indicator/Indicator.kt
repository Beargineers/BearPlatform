package nl.beargineers.platform.indicator

import nl.beargineers.platform.component.Component
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Lights that tell the drivers what the robot is up to, like a goBILDA RGB light or a row of REV LEDs, showing states
 * of type [S]: a color, or the colors of a row.
 *
 * Many parts of the robot have something to say, so each asks through a [Signal] of its own, and the indicator shows
 * the one with the highest priority:
 * ```
 * val ballsSignal = robot.light.signal(priority = 10)
 * val tagSignal = robot.light.signal(priority = 40)
 * ...
 * ballsSignal.show(if (balls == 3) GREEN else OFF)   // every loop: only a change counts
 * tagSignal.blink(ORANGE, times = 3)                 // once, when it happens: hides the balls for a moment
 * ```
 * A signal shows a state until told otherwise, for a while, blinking, or in a [Pattern]. Asking again for what a
 * signal shows already changes nothing, so a blink asked for every loop keeps blinking instead of starting over.
 *
 * The hardware is only sent changes, as each is a command to the hub. Subclasses send them with [display].
 */
abstract class Indicator<S : Any>(parent: Component, name: String, val off: S) : Component(parent, name) {
    private val signals = mutableListOf<Signal>()
    private var requests = 0L
    private var displayed: S? = null

    /** What the indicator shows now */
    val state: S get() = displayed ?: off

    /**
     * A new way to ask the indicator to show something. Of the signals showing something, the highest [priority]
     * wins; of equal ones, the one asked last.
     */
    fun signal(priority: Int = 0): Signal = Signal(priority).also { signals += it }

    inner class Signal internal constructor(val priority: Int) {
        private var request: Request<S>? = null
        private var startedAt = Duration.ZERO
        internal var order = 0L

        /** Whether it has something to show, whether or not a signal of higher priority hides it */
        val isShowing: Boolean get() = request != null

        /** Shows [state] until told otherwise */
        fun show(state: S) {
            val current = request
            if (current is Steady && current.state == state && current.lasting.isInfinite()) return
            start(Steady(state, Duration.INFINITE))
        }

        /** Shows [state] for [lasting], then nothing */
        fun show(state: S, lasting: Duration) {
            require(lasting.isPositive()) { "Show for a positive time, not $lasting" }
            val current = request
            if (current is Steady && current.state == state && current.lasting == lasting) return
            start(Steady(state, lasting))
        }

        /** Blinks [state] and [off], each for [period], [times] over, or until told otherwise when null */
        fun blink(state: S, period: Duration = 250.milliseconds, times: Int? = null) {
            require(period.isPositive()) { "Blink with a positive period, not $period" }
            require(times == null || times > 0) { "Blink a positive number of times, not $times" }
            val current = request
            if (current is Blink && current.state == state && current.period == period && current.times == times) return
            start(Blink(state, off, period, times))
        }

        /** Shows [pattern] */
        fun show(pattern: Pattern<S>) {
            val current = request
            if (current is Patterned && current.pattern == pattern) return
            start(Patterned(pattern))
        }

        /** Stops showing anything */
        fun clear() {
            request = null
        }

        private fun start(request: Request<S>) {
            checkStates(request)
            this.request = request
            startedAt = runtime.elapsed
            order = ++requests
        }

        /** What it shows now, or null; clears itself when what it showed is over */
        internal fun stateAt(now: Duration): S? {
            val current = request ?: return null
            val state = current.stateAt(now - startedAt)
            if (state == null) request = null
            return state
        }
    }

    private fun checkStates(request: Request<S>) {
        when (request) {
            is Steady -> checkState(request.state)
            is Blink -> checkState(request.state)
            is Patterned -> request.pattern.phases.forEach { checkState(it.first) }
        }
    }

    /**
     * Fails with what is wrong when this indicator can't show [state]. Called when a signal is asked to show it, so
     * the failure points there rather than at the indicator's update.
     */
    protected open fun checkState(state: S) {}

    /** Sends [state] to the hardware; only called when it changes */
    protected abstract fun display(state: S)

    override fun init() {
        displayed = off
        display(off)
    }

    override fun update() {
        val now = runtime.elapsed
        var shown: S? = null
        var winner: Signal? = null
        for (i in signals.indices) {
            val signal = signals[i]
            val state = signal.stateAt(now) ?: continue
            val best = winner
            if (best == null || signal.priority > best.priority || signal.priority == best.priority && signal.order > best.order) {
                winner = signal
                shown = state
            }
        }
        val next = shown ?: off
        if (next != displayed) {
            displayed = next
            display(next)
        }
    }

    override fun stop() {
        if (displayed != off) {
            displayed = off
            display(off)
        }
    }
}

/**
 * States shown one after another, each for its time, [times] over, or until told otherwise when null:
 * `Pattern("G00" to 150.milliseconds, "0G0" to 150.milliseconds, "00G" to 150.milliseconds)`. Make it once, in a
 * property, rather than in every loop.
 */
class Pattern<S : Any>(vararg phases: Pair<S, Duration>, val times: Int? = null) {
    val phases: List<Pair<S, Duration>> = phases.toList()

    init {
        require(this.phases.isNotEmpty()) { "A pattern needs at least one state" }
        require(this.phases.all { it.second.isPositive() }) { "Every state of a pattern needs a positive time" }
        require(times == null || times > 0) { "Show a pattern a positive number of times, not $times" }
    }

    internal val cycleNanos: Long = this.phases.sumOf { it.second.inWholeNanoseconds }

    override fun equals(other: Any?) = other is Pattern<*> && phases == other.phases && times == other.times

    override fun hashCode() = phases.hashCode() * 31 + (times ?: 0)

    override fun toString() = phases.joinToString(prefix = "Pattern(", postfix = if (times == null) ")" else ", times=$times)") {
        "${it.first} for ${it.second}"
    }
}

/** What a signal was asked to show; [stateAt] says what it is, so long after it started, or null once over */
private sealed interface Request<S : Any> {
    fun stateAt(elapsed: Duration): S?
}

private class Steady<S : Any>(val state: S, val lasting: Duration) : Request<S> {
    override fun stateAt(elapsed: Duration) = if (elapsed < lasting) state else null
}

private class Blink<S : Any>(val state: S, val off: S, val period: Duration, val times: Int?) : Request<S> {
    override fun stateAt(elapsed: Duration): S? {
        val half = (elapsed.inWholeNanoseconds / period.inWholeNanoseconds)
        if (times != null && half >= 2L * times) return null
        return if (half % 2 == 0L) state else off
    }
}

private class Patterned<S : Any>(val pattern: Pattern<S>) : Request<S> {
    override fun stateAt(elapsed: Duration): S? {
        val nanos = elapsed.inWholeNanoseconds
        val times = pattern.times
        if (times != null && nanos >= pattern.cycleNanos * times) return null
        var offset = nanos % pattern.cycleNanos
        for (phase in pattern.phases) {
            val length = phase.second.inWholeNanoseconds
            if (offset < length) return phase.first
            offset -= length
        }
        return pattern.phases.last().first
    }
}
