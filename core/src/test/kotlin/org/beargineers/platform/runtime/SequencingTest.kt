package org.beargineers.platform.runtime

import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class SequencingTest {
    private val loop = TestLoop()

    @Test
    fun waitUntilReturnsRightAwayWhenConditionHolds() {
        var done = false
        loop.launch {
            waitUntil { true }
            done = true
        }

        loop.tick()
        assertTrue(done)
    }

    @Test
    fun waitUntilChecksOncePerTick() {
        var checks = 0
        var ready = false
        loop.launch {
            waitUntil {
                checks++
                ready
            }
        }

        loop.tick(3)
        assertEquals(3, checks)
        ready = true
        loop.tick()
        assertEquals(4, checks)
        loop.tick(3)
        assertEquals(4, checks)
    }

    @Test
    fun waitUntilWithTimeout() {
        var ready = false
        var result: Boolean? = null
        loop.launch { result = waitUntil(100.milliseconds) { ready } }
        loop.tick(5)
        assertNull(result)
        ready = true
        loop.tick()
        assertEquals(true, result)

        result = null
        loop.launch { result = waitUntil(100.milliseconds) { false } }
        loop.tick(10) // starts on the first of these ticks, so 100ms are not over yet
        assertNull(result)
        loop.tick()
        assertEquals(false, result)
    }

    @Test
    fun everyTickRunsOncePerTick() {
        var runs = 0
        val job = loop.launch { everyTick { runs++ } }

        loop.tick(5)
        job.cancel()
        loop.tick(5)
        assertEquals(5, runs)
    }

    @Test
    fun raceReturnsTheWinnerAndCancelsTheOthers() {
        var loserCleanedUp = false
        var result: String? = null
        loop.launch {
            result = race(
                {
                    try {
                        delay(1000.milliseconds)
                        "slow"
                    } finally {
                        loserCleanedUp = true
                    }
                },
                {
                    delay(30.milliseconds)
                    "fast"
                },
            )
        }

        loop.tick(3) // starts at 10ms, "fast" is done at 40ms
        assertNull(result)
        loop.tick()
        assertEquals("fast", result)
        assertTrue(loserCleanedUp)
    }

    @Test
    fun raceWithCondition() {
        var full = false
        var steps = 0
        var finished = false
        loop.launch {
            race({ everyTick { steps++ } }, { waitUntil { full } })
            finished = true
        }

        loop.tick(4)
        assertFalse(finished)
        full = true
        loop.tick()
        assertTrue(finished)

        val stepsAtFinish = steps
        loop.tick(3)
        assertEquals(stepsAtFinish, steps)
    }

    @Test
    fun withNameLogsLifecycle() {
        val job = loop.launch {
            withName("Collect") { nextTick() }
            withName("Shoot") { delay(1000.milliseconds) }
        }

        loop.tick(2)
        job.cancel()
        loop.tick()

        assertEquals(
            listOf("Collect: started", "Collect: finished", "Shoot: started"),
            loop.log.take(3)
        )
        assertTrue(loop.log[3].startsWith("Shoot: cancelled"), loop.log[3])
        assertFalse(loop.log.any { "failed" in it })
    }
}
