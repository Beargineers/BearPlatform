package org.beargineers.platform.telemetry

import org.beargineers.platform.config.ConfigScope
import org.beargineers.platform.config.config
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * What the robot reports while it runs: lines for the Driver Station, numbers to graph, and log messages.
 * Components and opmodes use it through their `show()`, `graph()` and `log()`.
 *
 * Loops are much faster than anyone can read, so lines are only collected every [updateInterval]: in the loops
 * between, `show()` does nothing. Values are turned into text only in the loops that collect them. The host (the
 * opmode) calls [startLoop] and [finishLoop] around every loop, and publishes what [finishLoop] returns.
 *
 * Graphs keep the last 30 seconds or so of every value, at most one sample per 10 ms.
 */
class RobotTelemetry(
    private val clock: () -> Duration,
    private val logSink: (String) -> Unit,
) : ConfigScope {
    override val configScope: String = "telemetry"

    /** How often lines are collected for the Driver Station and the graphs page */
    val updateInterval by config(100.milliseconds)

    /**
     * How often the Driver Station lines are also written to the log, so match logs show what the drivers saw.
     * Zero turns it off.
     */
    val logInterval by config(1.seconds)

    /**
     * Whether lines shown in this loop are used. `show()` checks it itself; check it before building a line that is
     * expensive to make, or use the `show(label) { ... }` form.
     */
    var isCollecting: Boolean = true
        private set

    private var lastCollected: Duration? = null
    private var lastLogged: Duration? = null
    private val labels = ArrayList<String>()
    private val values = ArrayList<Any?>()

    /** The lines of the last loop that collected them */
    @Volatile
    var lines: List<String> = emptyList()
        private set

    // In the order values first came, for the graphs page. Changed only by the loop thread, and only under its own lock.
    private val series = LinkedHashMap<String, Series>()
    private var lastSeq = 0L

    val now: Duration get() = clock()

    /** Shows "label: value" on the Driver Station, for this loop. [value] becomes text only if this loop collects. */
    fun show(label: String, value: Any?) {
        if (!isCollecting) return
        labels += label
        values += value
    }

    /** Shows "label: value", computing the value only if this loop collects lines */
    inline fun show(label: String, value: () -> Any?) {
        if (isCollecting) show(label, value())
    }

    /** Shows a line on the Driver Station, for this loop */
    fun show(line: String) {
        if (!isCollecting) return
        labels += line
        values += WHOLE_LINE
    }

    /** Records [value] under [name] for the graphs. Values that aren't finite numbers are skipped. */
    fun graph(name: String, value: Double) {
        if (!value.isFinite()) return
        val time = clock()
        // Only this thread adds series, so looking one up without the lock is safe, and most samples stop here
        val existing = series[name]
        if (existing != null && time - existing.lastTime < MIN_SPACING) return
        synchronized(series) {
            val samples = existing ?: Series().also { series[name] = it }
            samples.add(Sample(++lastSeq, time.toDouble(DurationUnit.SECONDS), value), time)
        }
    }

    fun log(message: String) = logSink(message)

    /** Starts a loop, deciding whether it collects lines */
    fun startLoop() {
        val time = clock()
        val last = lastCollected
        isCollecting = last == null || time - last >= updateInterval
        if (isCollecting) {
            lastCollected = time
            labels.clear()
            values.clear()
        }
    }

    /**
     * Ends the loop. If it collected lines, they become [lines], go to the log if [logInterval] has passed, and are
     * returned for the host to publish; otherwise returns null.
     */
    fun finishLoop(): List<String>? {
        if (!isCollecting) return null
        val finished = labels.indices.map { i ->
            val value = values[i]
            if (value === WHOLE_LINE) labels[i] else "${labels[i]}: ${formatValue(value)}"
        }
        lines = finished

        val interval = logInterval
        if (interval > Duration.ZERO && finished.isNotEmpty()) {
            val time = clock()
            val last = lastLogged
            if (last == null || time - last >= interval) {
                lastLogged = time
                logSink("Telemetry: " + finished.joinToString(" | "))
            }
        }
        return finished
    }

    /** Samples recorded after sequence number [seq], by name */
    fun samplesSince(seq: Long): GraphSamples = synchronized(series) {
        GraphSamples(
            seq = lastSeq,
            series = series.mapValues { (_, samples) -> samples.since(seq) }.filterValues { it.isNotEmpty() },
            lastTime = series.values.maxOfOrNull { it.lastTime.toDouble(DurationUnit.SECONDS) },
        )
    }

    private class Series {
        private val samples = ArrayDeque<Sample>()

        @Volatile
        var lastTime: Duration = Duration.ZERO
            private set

        fun add(sample: Sample, time: Duration) {
            if (samples.size == CAPACITY) samples.removeFirst()
            samples.addLast(sample)
            lastTime = time
        }

        fun since(seq: Long): List<Sample> =
            if (samples.isEmpty() || samples.last().seq <= seq) emptyList() else samples.filter { it.seq > seq }
    }

    private companion object {
        val MIN_SPACING = 10.milliseconds
        const val CAPACITY = 3000

        /** Marks a line shown without a value */
        val WHOLE_LINE = Any()
    }
}

/** A graphed value: [time] in seconds since the opmode started */
data class Sample(val seq: Long, val time: Double, val value: Double)

/**
 * Samples by name, and [seq] to ask for next time. [lastTime] is when the latest sample was recorded, in seconds.
 */
class GraphSamples(val seq: Long, val series: Map<String, List<Sample>>, val lastTime: Double?)

/** Numbers with up to 3 decimals and no trailing zeros; anything else as it prints itself */
internal fun formatValue(value: Any?): String = when (value) {
    null -> "none"
    is Double, is Float -> {
        val number = (value as Number).toDouble()
        if (!number.isFinite()) number.toString()
        else String.format(Locale.US, "%.3f", number).trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }
    }
    else -> value.toString()
}
