package nl.beargineers.platform.ftc

import com.qualcomm.robotcore.hardware.DigitalChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import nl.beargineers.platform.component.Component
import nl.beargineers.platform.config.config
import nl.beargineers.platform.input.Button
import kotlin.time.Duration

/**
 * A digital sensor that is on or off, like a limit switch, a touch sensor or a beam break, as a part of [parent]:
 * `val upper = DigitalInput(this, "upperBeam", onWhenLow = true)`.
 *
 * It is read at the start of every loop, with the gamepads, so everything in a loop sees the same state, and it
 * works like a gamepad button: check [isOn], wait with [awaitOn], or have it start actions with [onTurnOn]. Digital
 * inputs come with the hub's bulk read: the first one read in a loop costs one bulk read of its hub, about 2 ms, and
 * the others on that hub come with it.
 *
 * Its settings are config settings (`intake.upper.debounce = 20ms`); the constructor arguments are the defaults.
 */
class DigitalInput(
    parent: Component,
    name: String,
    /** Name in the robot configuration, if it differs from [name] */
    device: String = name,
    onWhenLow: Boolean = false,
    debounce: Duration = Duration.ZERO,
) : Component(parent, name) {
    /**
     * Whether the sensor is on when its line reads low, as a REV touch sensor when pressed and most beam breaks when
     * broken
     */
    val onWhenLow by config(onWhenLow)

    /** A change counts only once it lasted this long, for a switch that bounces or a beam that flickers */
    val debounce by config(debounce)

    /** The SDK channel, for anything this class doesn't cover */
    val device: DigitalChannel by hardware(device)

    private var steady = false
    private var changedAt: Duration? = null

    // Works like a gamepad button: down while on
    private val button: Button = robot.host.runner.button { sense() }

    val isOn: Boolean get() = button.isDown

    /** Whether it turned on in this loop */
    val turnedOn: Boolean get() = button.wasPressed

    /** Whether it turned off in this loop */
    val turnedOff: Boolean get() = button.wasReleased

    /** How long it has been on; zero while off */
    val onFor: Duration get() = button.heldFor

    /** Waits until it turns on. Turning on in the current loop, before this was called, doesn't count. */
    suspend fun awaitOn() = button.awaitPress()

    /** Waits until it turns off. Turning off in the current loop, before this was called, doesn't count. */
    suspend fun awaitOff() = button.awaitRelease()

    /** Starts [action] every time it turns on, starting it over if it still runs */
    suspend fun onTurnOn(action: suspend CoroutineScope.() -> Unit): Job = button.onPress(action)

    /** Starts [action] every time it turns off, starting it over if it still runs */
    suspend fun onTurnOff(action: suspend CoroutineScope.() -> Unit): Job = button.onRelease(action)

    /** Runs [action] while it is on, once it has been on for [after]; turning off cancels [action] */
    suspend fun whileOn(after: Duration = Duration.ZERO, action: suspend CoroutineScope.() -> Unit): Job =
        button.whileHeld(after, action)

    override fun init() {
        device.mode = DigitalChannel.Mode.INPUT
    }

    private fun sense(): Boolean {
        val reading = device.state != onWhenLow
        val delay = debounce
        if (reading == steady) {
            changedAt = null
        } else if (delay <= Duration.ZERO) {
            steady = reading
        } else {
            val now = runtime.elapsed
            val since = changedAt ?: now.also { changedAt = it }
            if (now - since >= delay) {
                steady = reading
                changedAt = null
            }
        }
        return steady
    }
}
