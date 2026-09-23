package org.beargineers.platform.ftc

import com.qualcomm.robotcore.hardware.Servo
import com.qualcomm.robotcore.hardware.Servo.Direction
import org.beargineers.platform.component.Component
import org.beargineers.platform.config.config

/**
 * A servo that turns to a position, as a part of [parent]: `val latch = PositionServo(this, "latch")`.
 *
 * Its direction is a config setting (`shooter.latch.direction = R`); the constructor argument is the default.
 * Keep the positions it moves to as config settings of the component that uses it:
 * `val latchOpen by config(0.3)`, then `latch.position = latchOpen`.
 */
class PositionServo(
    parent: Component,
    name: String,
    /** Name in the robot configuration, if it differs from [name] */
    device: String = name,
    direction: Direction = Direction.FORWARD,
) : Component(parent, name) {
    val direction by config(direction)

    /** The SDK servo, for anything this class doesn't cover */
    val device: Servo by hardware(device)

    private var appliedDirection: Direction? = null

    /**
     * Position from 0.0 to 1.0. Values outside are clamped. The servo holds still until the first position is set,
     * and it is only sent new values.
     */
    var position: Double = Double.NaN
        set(value) {
            val clamped = value.coerceIn(0.0, 1.0)
            if (clamped != field) {
                field = clamped
                device.position = clamped
            }
        }

    override fun init() {
        applyDirection()
    }

    override fun update() {
        applyDirection()
    }

    private fun applyDirection() {
        if (appliedDirection != direction) {
            device.direction = direction
            appliedDirection = direction
        }
    }
}
