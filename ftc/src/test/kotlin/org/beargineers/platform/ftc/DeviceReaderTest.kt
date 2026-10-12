package org.beargineers.platform.ftc

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class DeviceReaderTest {
    private var now = 0L
    private val reader = DeviceReader { now }
    private val done = mutableListOf<String>()

    private fun advance(ms: Long) {
        now += ms * 1_000_000
    }

    @Test
    fun eachReadAtItsOwnIntervalTheMostOverdueFirst() {
        reader.add("fast", { 10.milliseconds }) { done += "fast" }
        reader.add("slow", { 50.milliseconds }) { done += "slow" }

        // Both are due at the start
        assertEquals(0, reader.readNextDue())
        assertEquals(0, reader.readNextDue())
        assertEquals(listOf("fast", "slow"), done)
        assertEquals(10_000_000, reader.readNextDue(), "nothing due: wait for the fast one")

        done.clear()
        repeat(50) {
            advance(1)
            while (reader.readNextDue() == 0L) Unit
        }
        assertEquals(5, done.count { it == "fast" })
        assertEquals(1, done.count { it == "slow" })
    }

    @Test
    fun theIntervalIsAskedForAgainBeforeEveryRead() {
        var interval = 10.milliseconds
        reader.add("tunable", { interval }) { done += "read" }
        reader.readNextDue()
        interval = 30.milliseconds
        assertEquals(30_000_000, reader.readNextDue())
    }

    @Test
    fun theThreadReadsUntilStopped() {
        val reads = AtomicInteger()
        val reader = DeviceReader()
        reader.add("counter", { 2.milliseconds }) { reads.incrementAndGet() }
        reader.start()
        Thread.sleep(100)
        reader.stop()
        val counted = reads.get()
        assertTrue(counted in 10..60, "read $counted times in 100 ms")
        Thread.sleep(20)
        assertEquals(counted, reads.get(), "no reads after stop")
    }

    @Test
    fun aFailedReadStopsTheOpModeOnItsThread() {
        val reader = DeviceReader()
        reader.add("broken", { 2.milliseconds }) { error("I2C gone") }
        reader.start()
        Thread.sleep(50)
        val thrown = assertFailsWith<IllegalStateException> { reader.rethrowFailure() }
        assertTrue("I2C gone" in thrown.message!!, thrown.message)
        reader.stop()
    }

    @Test
    fun reportsHowBusyItIs() {
        reader.add("slow", { 10.milliseconds }) { advance(5) }
        advance(1)
        reader.readNextDue()
        advance(4)
        val graphs = mutableMapOf<String, Double>()
        reader.report { name, value -> graphs[name] = value }
        assertEquals(50.0, graphs.getValue("reader busy %"), 1e-6)
        assertEquals(5.0, graphs.getValue("reader slow ms"), 1e-6)
    }

    @Test
    fun pausedReadsWaitAndAreDueAtOnceWhenActiveAgain() {
        var active = true
        reader.add("paused", { 10.milliseconds }, { active }) { done += "paused" }
        reader.add("slow", { 50.milliseconds }) { done += "slow" }
        while (reader.readNextDue() == 0L) Unit
        assertEquals(listOf("paused", "slow"), done)

        active = false
        advance(20)
        assertEquals(30_000_000, reader.readNextDue(), "only the slow one counts")
        advance(30)
        while (reader.readNextDue() == 0L) Unit
        assertEquals(listOf("paused", "slow", "slow"), done)

        active = true
        assertEquals(0, reader.readNextDue(), "overdue, so at once")
        assertEquals("paused", done.last())
    }

    @Test
    fun nothingActiveSleepsUntilWokenOrSoon() {
        reader.add("paused", { 10.milliseconds }, { false }) { done += "paused" }
        assertTrue(reader.readNextDue() > 0)
        assertEquals(emptyList(), done)
    }

    private fun runFor(ms: Long) {
        val end = now + ms * 1_000_000
        while (now < end) {
            val wait = reader.readNextDue()
            if (wait > 0) now += minOf(wait, end - now)
        }
    }

    @Test
    fun warnsOfWhatLastedOverTheBudgetNamingTheBiggestReadsAndLogsOnlyWhenWorse() {
        var localizerMs = 4L
        reader.add("localizer", { 10.milliseconds }) { advance(localizerMs) }
        reader.add("ball", { 50.milliseconds }) { advance(5) }
        val logged = mutableListOf<String>()
        fun check() = reader.checkBudget(45.0) { logged += it }

        repeat(2) {
            runFor(1000)
            assertNull(check(), "not lasted yet")
        }
        runFor(1000)
        val warning = check()!!
        assertTrue(warning.startsWith("Device reads take 50% of the time, over the budget of 45%: localizer 40%, ball 10%."), warning)
        assertEquals(listOf(warning), logged)
        runFor(500)
        assertEquals(warning, reader.checkBudget(80.0), "within a second, what the last check found")

        runFor(500)
        assertEquals(warning, check())
        assertEquals(1, logged.size, "no worse than logged before")

        localizerMs = 5
        repeat(3) {
            runFor(1000)
            check()
        }
        assertTrue(check()!!.startsWith("Device reads take 60%"))
        assertEquals(2, logged.size, "logged again: clearly worse")

        localizerMs = 2
        runFor(1000)
        assertNull(check(), "under the budget at once")
    }

    @Test
    fun theReadsNamedAreThoseOfTheSecondThatLasted() {
        var ballMs = 400L
        reader.add("localizer", { 10.milliseconds }) { advance(4) }
        reader.add("ball", { 50.milliseconds }) { advance(ballMs) }
        // A slow first second, when the ball sensor holds the reader and the localizer is hardly read
        runFor(1000)
        reader.checkBudget(45.0)
        ballMs = 5
        runFor(1000)
        reader.checkBudget(45.0)
        runFor(1000)
        val warning = reader.checkBudget(45.0)!!
        assertTrue(warning.startsWith("Device reads take 50% of the time, over the budget of 45%: localizer 40%, ball 10%."), warning)
    }

    @Test
    fun aSlowReadNowAndThenIsNotOverTheBudget() {
        var ballMs = 5L
        reader.add("localizer", { 10.milliseconds }) { advance(4) }
        reader.add("ball", { 50.milliseconds }) { advance(ballMs) }
        repeat(10) { second ->
            ballMs = if (second % 3 == 1) 400 else 5
            runFor(1000)
            assertNull(reader.checkBudget(60.0), "second $second")
        }
    }
}
