package org.beargineers.platform.runtime

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Runs coroutines on the robot loop, one step per [tick].
 *
 * The opmode calls [tick] once per loop iteration. Coroutines only ever run inside [tick], on the thread that calls it,
 * so robot code never needs locks. [nextTick] waits for the following tick. `delay` and `withTimeout` resume on the
 * first tick after their time is up, as measured by [timeSource]; tests pass a `TestTimeSource` to control time.
 *
 * If a coroutine fails, its exception is rethrown from [tick], so the opmode stops with a visible error.
 */
@OptIn(InternalCoroutinesApi::class)
class LoopRuntime(
    /** The clock of this runtime; controllers that measure time, like [Pid][org.beargineers.platform.control.Pid], use it too */
    val timeSource: TimeSource = TimeSource.Monotonic,
    /** Receives messages about named steps (see [withName]). The ftc module sends them to the robot log. */
    val log: (String) -> Unit = {},
) : AbstractCoroutineContextElement(LoopRuntime) {
    companion object Key : CoroutineContext.Key<LoopRuntime>

    private val startMark = timeSource.markNow()
    private val ready = ConcurrentLinkedQueue<Runnable>()
    private val nextTickWaiters = ConcurrentLinkedQueue<CancellableContinuation<Unit>>()
    private val timers = PriorityQueue<Timer>() // guarded by itself
    private var timerCount = 0L
    private val failures = ConcurrentLinkedQueue<Throwable>()
    private val rootJob = SupervisorJob()

    internal val dispatcher: CoroutineDispatcher = LoopDispatcher()

    val scope: CoroutineScope = CoroutineScope(
        rootJob + dispatcher + this + CoroutineExceptionHandler { _, throwable -> failures += throwable }
    )

    /** Time since this runtime was created, according to its time source */
    val elapsed: Duration get() = startMark.elapsedNow()

    /** Starts a coroutine that runs on this loop. It runs until it finishes, fails, or the runtime [stop]s. */
    fun launch(name: String, block: suspend CoroutineScope.() -> Unit): Job =
        scope.launch(CoroutineName(name), block = block)

    /**
     * One loop iteration: resumes coroutines whose delay is over and those waiting in [nextTick],
     * then runs everything that is ready. Returns how many steps ran.
     */
    fun tick(): Int {
        rethrowFailure()
        runDueTimers()

        // Resuming only queues the coroutines in `ready`, so whatever calls nextTick() while they run below
        // lands in nextTickWaiters again and resumes on the following tick, not this one.
        while (true) {
            val waiter = nextTickWaiters.poll() ?: break
            if (waiter.isActive) waiter.resume(Unit)
        }

        var ran = 0
        while (true) {
            val task = ready.poll() ?: break
            task.run()
            ran++
            rethrowFailure()
        }
        return ran
    }

    /** Cancels all coroutines and lets them run their `finally` blocks. Rethrows the first failure of those. */
    fun stop() {
        rootJob.cancel()
        try {
            while (true) {
                val task = ready.poll() ?: break
                try {
                    task.run()
                } catch (throwable: Throwable) {
                    failures += throwable
                }
            }
            rethrowFailure()
        } finally {
            failures.clear()
            nextTickWaiters.clear()
            ready.clear()
            synchronized(timers) { timers.clear() }
        }
    }

    internal suspend fun awaitNextTick(): Unit = suspendCancellableCoroutine { continuation ->
        nextTickWaiters += continuation
        continuation.invokeOnCancellation { nextTickWaiters.remove(continuation) }
    }

    private fun rethrowFailure() {
        throw failures.poll() ?: return
    }

    private fun runDueTimers() {
        val now = elapsed
        while (true) {
            val timer = synchronized(timers) {
                val first = timers.peek()
                if (first != null && first.deadline <= now) timers.poll() else null
            } ?: break
            timer.action.run()
        }
    }

    private fun schedule(timeMillis: Long, action: Runnable): Timer {
        synchronized(timers) {
            val timer = Timer(elapsed + timeMillis.milliseconds, timerCount++, action)
            timers += timer
            return timer
        }
    }

    private fun cancelTimer(timer: Timer) {
        synchronized(timers) { timers.remove(timer) }
    }

    private class Timer(val deadline: Duration, val order: Long, val action: Runnable) : Comparable<Timer> {
        override fun compareTo(other: Timer): Int =
            compareValuesBy(this, other, { it.deadline }, { it.order })
    }

    private inner class LoopDispatcher : CoroutineDispatcher(), Delay {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            ready += block
        }

        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            val timer = schedule(timeMillis) { continuation.resume(Unit) }
            continuation.invokeOnCancellation { cancelTimer(timer) }
        }

        override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
            val timer = schedule(timeMillis, block)
            return DisposableHandle { cancelTimer(timer) }
        }
    }
}

/** Waits until the next loop iteration */
suspend fun nextTick() {
    val runtime = currentCoroutineContext()[LoopRuntime]
        ?: error("nextTick() only works in coroutines started by a LoopRuntime")
    runtime.awaitNextTick()
}
