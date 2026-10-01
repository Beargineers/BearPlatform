package org.beargineers.platform.ftc

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
 * intervals as long as the robot can do with. With `telemetry.profile` on, the graphs show how busy the reader is.
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

    // For profiling: time spent reading since the last report
    private val busyNanos = AtomicLong()
    private var lastReport = nanoTime()

    /** One device's read, done every [interval] */
    inner class Read internal constructor(val name: String, private val interval: () -> Duration, private val read: () -> Unit) {
        internal var lastStart = Long.MIN_VALUE
        internal val dueAt: Long get() = if (lastStart == Long.MIN_VALUE) Long.MIN_VALUE else lastStart + interval().inWholeNanoseconds
        internal val nanos = AtomicLong()
        internal val times = AtomicInteger()

        internal fun run(now: Long) {
            lastStart = now
            read()
            val took = nanoTime() - now
            nanos.addAndGet(took)
            times.incrementAndGet()
            busyNanos.addAndGet(took)
        }
    }

    /** Reads with [read] every [interval], which is asked for again before every read, so config settings work */
    fun add(name: String, interval: () -> Duration, read: () -> Unit): Read = Read(name, interval, read).also { reads += it }

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

    /** Does the read that is due first, if any is due; otherwise returns how long until one is */
    internal fun readNextDue(): Long {
        val now = nanoTime()
        val next = reads.minByOrNull { it.dueAt } ?: return MAX_SLEEP
        val due = next.dueAt
        if (due > now) return due - now
        next.run(now)
        return 0
    }

    /** Graphs the reader's work since the last report: how busy it was, and each read's time and rate */
    internal fun report(graph: (String, Double) -> Unit) {
        val now = nanoTime()
        val seconds = (now - lastReport) / 1e9
        lastReport = now
        if (seconds <= 0) return
        graph("reader busy %", busyNanos.getAndSet(0) / 1e9 / seconds * 100)
        for (read in reads) {
            val times = read.times.getAndSet(0)
            val nanos = read.nanos.getAndSet(0)
            graph("reader ${read.name} per s", times / seconds)
            if (times > 0) graph("reader ${read.name} ms", nanos / 1e6 / times)
        }
    }

    private companion object {
        /** Sleeps at most this long, so stopping and newly added reads don't wait */
        val MAX_SLEEP = TimeUnit.MILLISECONDS.toNanos(20)
    }
}
