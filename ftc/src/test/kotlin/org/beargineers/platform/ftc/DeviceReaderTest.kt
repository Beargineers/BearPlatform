package org.beargineers.platform.ftc

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
}
