package org.beargineers.platform.input

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import org.beargineers.platform.component.RobotHost
import org.beargineers.platform.config.ConfigScope
import org.beargineers.platform.config.config
import org.beargineers.platform.runtime.LoopRuntime
import org.beargineers.platform.runtime.race
import org.beargineers.platform.runtime.waitUntil
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Inputs read together at the start of every loop, like one gamepad. All code running in a loop sees the same state,
 * so a press is noticed by everything that looks for it in the loop where it happened.
 *
 * Subclasses create their inputs with [button], [axis] and [stick].
 */
abstract class Controls(host: RobotHost, val name: String) : ConfigScope {
    override val configScope: String = name

    /** Axis values closer to zero than this read as zero, so a stick at rest doesn't creep */
    val deadband by config(0.05)

    internal val runtime: LoopRuntime = host.runner.runtime
    private val buttons = mutableListOf<Button>()
    private val axes = mutableListOf<Axis>()

    init {
        host.runner.readEveryTick { read() }
    }

    fun button(isDown: () -> Boolean): Button = Button(this, isDown).also { buttons += it }

    fun axis(value: () -> Double): Axis = Axis(this, value).also { axes += it }

    fun stick(x: () -> Double, y: () -> Double): Stick = Stick(axis(x), axis(y))

    private fun read() {
        axes.forEach { it.read() }
        buttons.forEach { it.read() }
    }
}

/**
 * A button, read once per loop. Check it directly (`if (a.isDown)`), wait for it in a coroutine (`a.awaitPress()`),
 * or have it start actions (`a.onPress { ... }`).
 *
 * The `on...` and [whileHeld] handlers keep running as long as the coroutine that set them up, so handlers set up
 * during init stop at START.
 */
class Button internal constructor(private val controls: Controls, private val sensor: () -> Boolean) {
    var isDown: Boolean = false
        private set

    /** Whether the button went down in this loop */
    var wasPressed: Boolean = false
        private set

    /** Whether the button went up in this loop */
    var wasReleased: Boolean = false
        private set

    private var presses = 0L
    private var releases = 0L
    private var pressedAt = Duration.ZERO

    /** How long the button has been down; zero when it is up */
    val heldFor: Duration get() = if (isDown) controls.runtime.elapsed - pressedAt else Duration.ZERO

    internal fun read() {
        val down = sensor()
        wasPressed = down && !isDown
        wasReleased = !down && isDown
        if (wasPressed) {
            presses++
            pressedAt = controls.runtime.elapsed
        }
        if (wasReleased) releases++
        isDown = down
    }

    /** Waits for the next press. A press in the current loop, before this was called, doesn't count. */
    suspend fun awaitPress() {
        val seen = presses
        waitUntil { presses > seen }
    }

    /** Waits for the next release. A release in the current loop, before this was called, doesn't count. */
    suspend fun awaitRelease() {
        val seen = releases
        waitUntil { releases > seen }
    }

    /** Starts [action] on every press. Pressing again while [action] still runs starts it over. */
    suspend fun onPress(action: suspend CoroutineScope.() -> Unit): Job = handle { restart ->
        while (true) {
            awaitPress()
            restart(action)
        }
    }

    /** Starts [action] on every release. Releasing again while [action] still runs starts it over. */
    suspend fun onRelease(action: suspend CoroutineScope.() -> Unit): Job = handle { restart ->
        while (true) {
            awaitRelease()
            restart(action)
        }
    }

    /**
     * Starts [action] when the button is released less than [within] after being pressed. Together with
     * `whileHeld(after = within)` the same button can do one thing on a tap and another while held.
     */
    suspend fun onTap(within: Duration = 300.milliseconds, action: suspend CoroutineScope.() -> Unit): Job =
        handle { restart ->
            while (true) {
                awaitPress()
                if (waitUntil(within) { !isDown }) restart(action) else if (isDown) awaitRelease()
            }
        }

    /**
     * Runs [action] while the button is down, starting once it has been down for [after].
     * Releasing the button cancels [action].
     */
    suspend fun whileHeld(after: Duration = Duration.ZERO, action: suspend CoroutineScope.() -> Unit): Job =
        handle {
            while (true) {
                awaitPress()
                if (after > Duration.ZERO && waitUntil(after) { !isDown }) continue
                race({ action() }, { waitUntil { !isDown } })
            }
        }

    /**
     * Runs [body] in a new coroutine that belongs to the caller's coroutine. [body] gets `restart`, which starts
     * an action in its own coroutine, cancelling the one it started before if it still runs.
     */
    private suspend fun handle(
        body: suspend CoroutineScope.(restart: (suspend CoroutineScope.() -> Unit) -> Unit) -> Unit,
    ): Job = CoroutineScope(currentCoroutineContext()).launch {
        var running: Job? = null
        body { action ->
            running?.cancel()
            running = launch(block = action)
        }
    }
}

/**
 * An analog input like a trigger or a stick axis, read once per loop. Values within the deadband read as zero,
 * and the rest of the range is stretched so the value still grows smoothly from zero.
 */
class Axis internal constructor(private val controls: Controls, private val sensor: () -> Double) {
    var value: Double = 0.0
        private set

    internal fun read() {
        val raw = sensor().coerceIn(-1.0, 1.0)
        val deadband = controls.deadband
        value = if (abs(raw) <= deadband) 0.0 else sign(raw) * (abs(raw) - deadband) / (1 - deadband)
    }

    /** A button that is down while this axis is pushed further than [threshold], like a trigger pulled halfway */
    fun asButton(threshold: Double = 0.5): Button = controls.button { abs(sensor()) > threshold }
}

/** A thumbstick: [x] is positive to the right, [y] is positive when pushed forward */
class Stick internal constructor(val x: Axis, val y: Axis) {
    /** How far the stick is pushed, from 0.0 to 1.0, in any direction */
    val magnitude: Double get() = hypot(x.value, y.value).coerceAtMost(1.0)
}
