package nl.beargineers.platform.telemetry

import nl.beargineers.platform.config.ConfigScope
import nl.beargineers.platform.config.config
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
     * Graphs where each loop's time goes, as `time <part> ms`, per loop on average over each [updateInterval]: the
     * robot's parts' updates, coroutines, hub writes and so on. For finding what makes loops slow; it costs a few clock
     * readings per loop.
     */
    val profile by config(false)

    /** Whether this loop measures where its time goes ([profile], read once per loop) */
    var isProfiling: Boolean = false
        private set

    // Time in nanoseconds and how many times, by name, since the last report, and in this loop
    private val timings = LinkedHashMap<String, Timing>()
    private val timingList = ArrayList<Timing>()
    private var profiledLoops = 0

    private class Timing(val name: String, val counted: Boolean) {
        var nanos = 0L
        var times = 0
        var bytes = 0L
        var loopNanos = 0L
        var loopTimes = 0
        var loopBytes = 0L
    }

    /**
     * Bytes the calling thread has allocated so far, when the platform can count them; profiling then also graphs
     * what each part allocates, as `alloc <part> KB`. The FTC opmode sets it.
     */
    var allocationCounter: (() -> Long)? = null

    /**
     * Adds [nanos] and [bytes] allocated to what [name] took in this loop, when profiling. [counted]: also graph how many
     * times per loop.
     */
    fun time(name: String, nanos: Long, counted: Boolean = false, bytes: Long = 0) {
        if (!isProfiling) return
        val timing = timings[name] ?: Timing(name, counted).also {
            timings[name] = it
            timingList += it
        }
        timing.nanos += nanos
        timing.times++
        timing.bytes += bytes
        timing.loopNanos += nanos
        timing.loopTimes++
        timing.loopBytes += bytes
    }

    /** Where this loop's time went so far, when profiling: for logging a loop that took unusually long */
    fun loopBreakdown(): String = timingList.filter { it.loopTimes > 0 }.joinToString(", ") {
        String.format(Locale.US, "%s %.1f ms", it.name, it.loopNanos / 1e6) +
            (if (it.counted) " (${it.loopTimes} times)" else "") +
            (if (it.loopBytes > 0) String.format(Locale.US, " %.1f KB", it.loopBytes / 1024.0) else "")
    }

    /** Runs [block], adding how long it took and what it allocated to [name] when profiling. See [time]. */
    inline fun <T> timed(name: String, counted: Boolean = false, block: () -> T): T {
        if (!isProfiling) return block()
        val counter = allocationCounter
        val startBytes = counter?.invoke() ?: 0L
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            val nanos = System.nanoTime() - start
            var bytes = if (counter != null) counter() - startBytes else 0L
            // Android counts in an int, which wraps around
            if (bytes < 0) bytes += 1L shl 32
            time(name, nanos, counted, bytes)
        }
    }

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

    /** Starts a loop, deciding whether it collects lines and whether it measures where its time goes */
    fun startLoop() {
        val time = clock()
        val last = lastCollected
        isCollecting = last == null || time - last >= updateInterval
        if (isCollecting) {
            lastCollected = time
            labels.clear()
            values.clear()
            if (profiledLoops > 0) reportTimings()
        }
        isProfiling = profile
        if (isProfiling) {
            profiledLoops++
            for (i in timingList.indices) {
                val timing = timingList[i]
                timing.loopNanos = 0
                timing.loopTimes = 0
                timing.loopBytes = 0
            }
        }
    }

    private fun reportTimings() {
        for ((name, timing) in timings) {
            if (timing.times == 0) continue
            graph("time $name ms", timing.nanos / 1e6 / profiledLoops)
            if (timing.counted) graph("$name per loop", timing.times.toDouble() / profiledLoops)
            if (allocationCounter != null) graph("alloc $name KB", timing.bytes / 1024.0 / profiledLoops)
            timing.nanos = 0
            timing.times = 0
            timing.bytes = 0
        }
        profiledLoops = 0
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

        /**
         * The samples after [seq]. Called under the lock the loop's [graph] needs too, so it finds the first new sample by
         * binary search rather than looking at all of them, and copies only the new ones.
         */
        fun since(seq: Long): List<Sample> {
            if (samples.isEmpty() || samples.last().seq <= seq) return emptyList()
            var low = 0
            var high = samples.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (samples[middle].seq <= seq) low = middle + 1 else high = middle
            }
            return ArrayList<Sample>(samples.size - low).also { copy ->
                for (i in low until samples.size) copy += samples[i]
            }
        }
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
