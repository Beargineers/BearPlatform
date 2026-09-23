package org.beargineers.platform.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LoopRuntimeTest {
    private val loop = TestLoop()

    @Test
    fun nextTickResumesOnTheFollowingTick() {
        var steps = 0
        loop.launch {
            while (true) {
                steps++
                nextTick()
            }
        }

        loop.tick()
        assertEquals(1, steps)
        loop.tick(4)
        assertEquals(5, steps)
    }

    @Test
    fun delayFollowsTheTimeSource() {
        var done = false
        loop.launch {
            delay(100.milliseconds)
            done = true
        }

        loop.tick() // starts at 10ms and waits until 110ms
        loop.tick(9) // 100ms
        assertFalse(done)
        loop.tick() // 110ms
        assertTrue(done)
    }

    @Test
    fun withTimeoutFollowsTheTimeSource() {
        var result: String? = "not yet"
        loop.launch {
            result = withTimeoutOrNull(50.milliseconds) {
                waitUntil { false }
                "finished"
            }
        }

        loop.tick(5) // starts at 10ms, times out at 60ms
        assertEquals("not yet", result)
        loop.tick()
        assertEquals(null, result)
    }

    @Test
    fun cancelledChildDoesNotResumeItsDelay() {
        var fuse = false
        var complete = false

        loop.launch {
            coroutineScope {
                val child = launch {
                    delay(100.seconds)
                    fuse = true
                }
                delay(50.milliseconds)
                child.cancel()
            }
            complete = true
        }

        loop.tick(20)
        assertFalse(fuse)
        assertTrue(complete)
    }

    @Test
    fun failureIsRethrownFromTick() {
        loop.launch {
            nextTick()
            throw RuntimeException("Test exception")
        }

        loop.tick()
        val thrown = assertFailsWith<RuntimeException> { loop.tick() }
        assertEquals("Test exception", thrown.message)
    }

    @Test
    fun immediateFailureIsRethrownFromFirstTick() {
        loop.launch { error("throw me") }

        val thrown = assertFailsWith<IllegalStateException> { loop.tick() }
        assertEquals("throw me", thrown.message)
    }

    @Test
    fun cancellationArrivesAtNextTick() {
        var started = 0
        var cancelled = 0

        repeat(5) {
            val job = loop.launch {
                try {
                    started++
                    nextTick()
                    error("nextTick() should throw CancellationException when the job is cancelled")
                } catch (e: CancellationException) {
                    cancelled++
                    throw e
                }
            }

            loop.tick()
            job.cancel()
            loop.tick()
            assertTrue(job.isCancelled)
        }

        assertEquals(5, started)
        assertEquals(5, cancelled)
    }

    @Test
    fun cleanupCanLaunchInNonCancellableContext() {
        var worked = false
        val job = loop.launch {
            try {
                nextTick()
            } finally {
                withContext(NonCancellable) {
                    coroutineScope {
                        launch { worked = true }
                    }
                }
            }
        }

        loop.tick()
        job.cancel()
        loop.tick(10)
        assertTrue(worked)
    }

    @Test
    fun cleanupCanLaunchNewTopLevelCoroutine() {
        var worked = false
        val job = loop.launch {
            try {
                nextTick()
            } finally {
                loop.launch { worked = true }
            }
        }

        loop.tick()
        job.cancel()
        loop.tick(10)
        assertTrue(worked)
    }

    @Test
    fun stopRunsCleanup() {
        var cleanupStarted = false
        var cleanupChildRan = false

        loop.launch {
            try {
                nextTick()
            } finally {
                cleanupStarted = true
                withContext(NonCancellable) {
                    coroutineScope {
                        launch { cleanupChildRan = true }
                    }
                }
            }
        }

        loop.tick()
        loop.runtime.stop()

        assertTrue(cleanupStarted)
        assertTrue(cleanupChildRan)
    }

    @Test
    fun stopRethrowsCleanupFailure() {
        loop.launch {
            try {
                nextTick()
            } finally {
                error("cleanup failed")
            }
        }

        loop.tick()
        val thrown = assertFailsWith<IllegalStateException> { loop.runtime.stop() }
        assertEquals("cleanup failed", thrown.message)
    }

    @Test
    fun nextTickOutsideOfLoopRuntimeExplains() {
        val thrown = assertFailsWith<IllegalStateException> {
            kotlinx.coroutines.runBlocking { nextTick() }
        }
        assertTrue("LoopRuntime" in thrown.message!!)
    }

    @Test
    fun concurrentDispatchFromBackgroundThread() {
        val runtime = LoopRuntime()
        val tasks = 10_000
        val executed = AtomicInteger()
        val producerDone = CountDownLatch(1)

        val producer = Thread {
            try {
                repeat(tasks) {
                    runtime.dispatcher.dispatch(EmptyCoroutineContext) { executed.incrementAndGet() }
                }
            } finally {
                producerDone.countDown()
            }
        }
        producer.start()

        while (producerDone.count > 0 || executed.get() < tasks) {
            runtime.tick()
        }

        assertTrue(producerDone.await(1, TimeUnit.SECONDS))
        producer.join(1_000)
        assertEquals(tasks, executed.get())
    }
}
