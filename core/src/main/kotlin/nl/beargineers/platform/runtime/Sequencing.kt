package nl.beargineers.platform.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/** Waits until [condition] is true, checking it once per tick. Returns right away if it already is. */
suspend fun waitUntil(condition: () -> Boolean) {
    while (!condition()) nextTick()
}

/** Like [waitUntil], but gives up after [timeout]. Returns whether [condition] became true. */
suspend fun waitUntil(timeout: Duration, condition: () -> Boolean): Boolean =
    withTimeoutOrNull(timeout) { waitUntil(condition) } != null

/** Runs [block] once every tick until the coroutine is cancelled */
suspend fun everyTick(block: suspend () -> Unit): Nothing {
    while (true) {
        block()
        nextTick()
    }
}

/**
 * Runs all [contestants] at the same time. As soon as one of them finishes, the others are cancelled
 * and the winner's result is returned. If a contestant fails or gets cancelled, the whole race ends that way.
 *
 * `race({ driveTo(SPIKE_1) }, { waitUntil { intake.isFull } })` drives to the spike, but stops as soon as
 * the intake is full.
 */
suspend fun <T> race(vararg contestants: suspend CoroutineScope.() -> T): T = coroutineScope {
    val runners = contestants.map { async(block = it) }
    val result = select { runners.forEach { runner -> runner.onAwait { it } } }
    runners.forEach { it.cancel() }
    result
}

/**
 * Runs [block] as a named step. The name is written to the runtime's log when the step starts, finishes,
 * fails or gets cancelled, which makes match logs readable.
 */
suspend fun <T> withName(name: String, block: suspend CoroutineScope.() -> T): T {
    val log = currentCoroutineContext()[LoopRuntime]?.log ?: {}
    log("$name: started")
    try {
        val result = withContext(CoroutineName(name), block)
        log("$name: finished")
        return result
    } catch (e: CancellationException) {
        log("$name: cancelled" + (e.message?.let { ". $it" } ?: ""))
        throw e
    } catch (e: Throwable) {
        log("$name: failed. $e")
        throw e
    }
}
