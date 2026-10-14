package nl.beargineers.platform.ftc

import com.qualcomm.robotcore.hardware.Servo
import com.qualcomm.robotcore.hardware.Servo.Direction
import nl.beargineers.platform.component.Component
import nl.beargineers.platform.config.config
import nl.beargineers.platform.config.optionalConfig

/**
 * A servo that turns to a position, as a part of [parent]: `val latch = PositionServo(this, "latch")`.
 *
 * Its direction and pulse widths are config settings (`shooter.latch.direction = R`); the constructor argument is the
 * default. Keep the positions it moves to as config settings of the component that uses it:
 * `val latchOpen by config(0.3)`, then `latch.position = latchOpen`.
 *
 * Each new position is a command to the hub, so it is only sent when it changes.
 */
class PositionServo(
    parent: Component,
    name: String,
    /** Name in the robot configuration, if it differs from [name] */
    device: String = name,
    direction: Direction = Direction.FORWARD,
) : Component(parent, name) {
    val direction by config(direction)

    /**
     * Pulse widths in microseconds for positions 0 and 1, when the device needs other ones than the SDK's 600 and
     * 2400: goBILDA's RGB indicator light wants 500 and 2500
     */
    val pwmMin by optionalConfig<Double>()
    val pwmMax by optionalConfig<Double>()

    /** The SDK servo, for anything this class doesn't cover */
    val device: Servo by hardware(device)

    private var appliedDirection: Direction? = null
    private val pwmRange = PwmRangeSetting()

    /**
     * Position from 0.0 to 1.0. Values outside are clamped. The servo holds still until the first position is set,
     * and it is only sent new values.
     */
    var position: Double = Double.NaN
        set(value) {
            val clamped = value.coerceIn(0.0, 1.0)
            if (clamped != field) {
                field = clamped
                timed("servo writes", counted = true) { device.position = clamped }
            }
        }

    override fun init() {
        applySettings()
    }

    override fun update() {
        applySettings()
    }

    private fun applySettings() {
        if (appliedDirection != direction) {
            device.direction = direction
            appliedDirection = direction
        }
        pwmRange.apply(device, pwmMin, pwmMax)
    }
}
