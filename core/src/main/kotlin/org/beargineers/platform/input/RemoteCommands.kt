package org.beargineers.platform.input

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Actions started from outside the robot's program: by buttons on the robot's graphs page, or by a script over the
 * robot's WiFi. They are for testing and tuning, with someone at the Driver Station ready to stop the robot.
 *
 * An opmode offers them like gamepad handlers, in `onInit` or `onStart`: `command("forward 60 cm") { ... }`. A command
 * is offered as long as the coroutine that offered it runs, so commands offered in `onInit` go away at START, and all of
 * them with the opmode. Starting a command that still runs starts it over, like pressing a button again.
 *
 * Starting one only asks for it: the action starts in the next loop, on the loop's thread, like all robot code.
 */
class RemoteCommands internal constructor() {
    private val commands = CopyOnWriteArrayList<Command>()

    /**
     * A command as a page or a script sees it. Runs are numbered from 1; the command runs while [finished] is behind
     * [started]. A run that was started over counts as finished.
     */
    class Status(val name: String, val started: Int, val finished: Int)

    /** The commands offered now, in the order they were offered. Any thread. */
    val offered: List<Status> get() = commands.map { Status(it.name, it.started.get(), it.finished) }

    /**
     * Asks for the command called [name] to start. Returns the number of this run, to tell when it has finished, or
     * null when no such command is offered now. Any thread.
     */
    fun start(name: String): Int? {
        val command = commands.firstOrNull { it.name == name } ?: return null
        val run = command.started.incrementAndGet()
        // Resuming from this thread is safe: the coroutine continues on the loop's thread, in its next loop
        command.waiter.getAndSet(null)?.resume(Unit)
        return run
    }

    /** Offers [action] as the command [name], until the calling coroutine ends. Returns the job that handles it. */
    suspend fun offer(name: String, action: suspend CoroutineScope.() -> Unit): Job {
        check(commands.none { it.name == name }) { "A command named '$name' is offered already" }
        val command = Command(name)
        commands += command
        return CoroutineScope(currentCoroutineContext()).launch {
            try {
                var handled = 0
                var running: Job? = null
                while (true) {
                    if (command.started.get() <= handled) command.awaitStart(handled)
                    handled = command.started.get()
                    val run = handled
                    running?.cancel()
                    running = launch {
                        try {
                            action()
                        } finally {
                            command.finished = maxOf(command.finished, run)
                        }
                    }
                }
            } finally {
                commands -= command
            }
        }
    }

    private class Command(val name: String) {
        val started = AtomicInteger()

        @Volatile
        var finished = 0

        /** The handler waiting for a start; woken by [start], so waiting costs nothing in loops without one */
        val waiter = AtomicReference<CancellableContinuation<Unit>?>()

        suspend fun awaitStart(handled: Int) = suspendCancellableCoroutine { continuation ->
            waiter.set(continuation)
            continuation.invokeOnCancellation { waiter.compareAndSet(continuation, null) }
            // A start that came in just before this waiter was set found no one to wake
            if (started.get() > handled) waiter.getAndSet(null)?.resume(Unit)
        }
    }
}
