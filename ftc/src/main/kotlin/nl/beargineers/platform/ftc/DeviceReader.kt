package nl.beargineers.platform.ftc

import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration

/**
 * Reads devices on a thread of its own, each at its own interval, so the robot's loop doesn't wait for them: I2C
 * sensors like the Pinpoint, whose reads take several commands to the hub.
 *
 * Every command to the hubs, motor powers, I2C and bulk reads alike, holds the hubs' one connection until the hub
 * answers, about 1.4 ms. All reads share this one thread, so they never compete with each other, and the loop's
 * commands get through between theirs. Reading more often than needed takes the connection from everything else: pick
 * intervals as long as the robot can do with, and pause reads while nothing needs them. When the reads take more of
 * the time than the budget, [checkBudget] names them; with `telemetry.profile` on, the graphs show each read's time.
 *
 * A component adds its reads in `init()` and keeps what each read returns in a `@Volatile` field for its `update()`.
 */
class DeviceReader internal constructor(private val nanoTime: () -> Long = System::nanoTime) {
    private val reads = CopyOnWriteArrayList<Read>()

    @Volatile
    private var running = false
    private var thread: Thread? = null

    @Volatile
    private var failure: Throwable? = null

    // Time spent reading in all; profiling and the budget check each look at what it grew by since they last looked
    private val busyNanos = AtomicLong()
    private val profiledBusy = Growth()
    private val checkedBusy = Growth()

    // The last few seconds' shares of the time, for the budget: all reads' together, and each read's in Read.shares
    private val busyShares = DoubleArray(LASTING_SECONDS)
    private var second = 0
    private var lastReport = nanoTime()
    private var lastCheck = nanoTime()
    private var overBudget: String? = null
    private var loggedBusy = 0.0

    /** One device's read, done every [interval] while [isActive] */
    inner class Read internal constructor(
        val name: String,
        private val interval: () -> Duration,
        private val isActive: () -> Boolean,
        private val read: () -> Unit,
    ) {
        internal var lastStart = Long.MIN_VALUE
        internal val dueAt: Long get() = if (lastStart == Long.MIN_VALUE) Long.MIN_VALUE else lastStart + interval().inWholeNanoseconds
        internal val active: Boolean get() = isActive()
        internal val nanos = AtomicLong()
        internal val times = AtomicInteger()
        internal val profiledNanos = Growth()
        internal val profiledTimes = Growth()
        internal val checkedNanos = Growth()
        internal val shares = DoubleArray(LASTING_SECONDS)

        internal fun run(now: Long) {
            lastStart = now
            read()
            val took = nanoTime() - now
            nanos.addAndGet(took)
            times.incrementAndGet()
            busyNanos.addAndGet(took)
        }
    }

    /**
     * Reads with [read] every [interval] while [isActive]. Both are asked for again before every read, so config
     * settings work, and a read that becomes active again after a pause is due at once.
     */
    fun add(name: String, interval: () -> Duration, isActive: () -> Boolean = { true }, read: () -> Unit): Read =
        Read(name, interval, isActive, read).also { reads += it }

    /** Starts the thread; the opmode does, once the robot's components have added their reads */
    internal fun start() {
        if (running) return
        running = true
        thread = Thread(::readLoop, "bear device reader").apply {
            isDaemon = true
            start()
        }
    }

    /** Stops the thread, letting a read in progress finish */
    internal fun stop() {
        running = false
        thread?.let {
            LockSupport.unpark(it)
            it.join(500)
        }
        thread = null
    }

    /** Throws what a read threw, on the loop's thread, so the opmode stops with the error like any other */
    fun rethrowFailure() {
        failure?.let { throw IllegalStateException("A device read failed: $it", it) }
    }

    private fun readLoop() {
        while (running) {
            try {
                val waitNanos = readNextDue()
                if (waitNanos > 0) LockSupport.parkNanos(minOf(waitNanos, MAX_SLEEP))
            } catch (e: Throwable) {
                failure = e
                running = false
            }
        }
    }

    /** Does the active read that is due first, if any is due; otherwise returns how long until one is */
    internal fun readNextDue(): Long {
        val now = nanoTime()
        var next: Read? = null
        var due = Long.MAX_VALUE
        // Reads are only ever added, so the indexes stay valid
        for (i in 0 until reads.size) {
            val read = reads[i]
            if (!read.active) continue
            val at = read.dueAt
            if (next == null || at < due) {
                next = read
                due = at
            }
        }
        if (next == null) return MAX_SLEEP
        if (due > now) return due - now
        next.run(now)
        return 0
    }

    /** Graphs the reader's work since the last report: how busy it was, and each read's time and rate */
    internal fun report(graph: (String, Double) -> Unit) {
        val now = nanoTime()
        val seconds = (now - lastReport) / 1e9
        if (seconds <= 0) return
        lastReport = now
        graph("reader busy %", profiledBusy.take(busyNanos.get()) / 1e9 / seconds * 100)
        for (read in reads) {
            val times = read.profiledTimes.take(read.times.get().toLong())
            val nanos = read.profiledNanos.take(read.nanos.get())
            graph("reader ${read.name} per s", times / seconds)
            if (times > 0) graph("reader ${read.name} ms", nanos / 1e6 / times)
        }
    }

    /**
     * Checks once a second what share of the time the reads took, and returns null when what lasted the last few
     * seconds was at most [budgetPercent], and otherwise what to tell the robot's programmers, naming the reads that
     * took the most. A slow read now and then, like a 2m distance sensor's first, doesn't count: only what every one
     * of those seconds took. Between checks, returns what the last one found. Gives [log] the first warning, and later
     * ones only when the reads take clearly more than in any warning before, so the log isn't flooded.
     */
    internal fun checkBudget(budgetPercent: Double, log: (String) -> Unit = {}): String? {
        val now = nanoTime()
        val nanos = now - lastCheck
        if (nanos < BUDGET_WINDOW) return overBudget
        lastCheck = now
        busyShares[second] = checkedBusy.take(busyNanos.get()) * 100.0 / nanos
        for (read in reads) read.shares[second] = read.checkedNanos.take(read.nanos.get()) * 100.0 / nanos
        second = (second + 1) % LASTING_SECONDS
        // What lasted is the least busy of those seconds, and its reads; zero until there were that many
        val least = busyShares.indices.minBy { busyShares[it] }
        val busy = busyShares[least]
        overBudget = if (busy <= budgetPercent) null else {
            val byShare = reads.map { it.name to it.shares[least] }.filter { it.second >= 0.5 }
                .sortedByDescending { it.second }.joinToString { (name, share) -> "$name ${percent(share)}" }
            "Device reads take ${percent(busy)} of the time, over the budget of ${percent(budgetPercent)}: $byShare. " +
                "Each read holds the hubs' connection that motors and other sensors need: read less often " +
                "(readInterval), or pause what nothing needs."
        }
        if (overBudget != null && busy >= loggedBusy + 5) {
            loggedBusy = busy
            log(overBudget!!)
        }
        return overBudget
    }

    private fun percent(value: Double) = String.format(Locale.US, "%.0f%%", value)

    /** What a running total grew by since the last look */
    internal class Growth {
        private var last = 0L

        fun take(total: Long): Long = (total - last).also { last = total }
    }

    private companion object {
        /** Sleeps at most this long, so stopping and newly added reads don't wait */
        val MAX_SLEEP = TimeUnit.MILLISECONDS.toNanos(20)

        /** The budget is checked a second at a time, for what lasted this many seconds */
        val BUDGET_WINDOW = TimeUnit.SECONDS.toNanos(1)
        const val LASTING_SECONDS = 3
    }
}
