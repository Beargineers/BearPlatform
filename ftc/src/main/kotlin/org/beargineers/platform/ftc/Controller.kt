package org.beargineers.platform.ftc

import com.qualcomm.robotcore.hardware.Gamepad
import org.beargineers.platform.component.RobotHost
import org.beargineers.platform.input.Axis
import org.beargineers.platform.input.Button
import org.beargineers.platform.input.Controls
import org.beargineers.platform.input.Stick

/**
 * A gamepad, read once per loop. See [Button] for reacting to presses.
 *
 * Stick [y][Stick.y] is positive when the stick is pushed forward; the SDK has it the other way around.
 * PlayStation-style buttons are the same as their Xbox-style ones: [a] is cross, [b] circle, [x] square, [y] triangle.
 */
class Controller(
    host: RobotHost,
    name: String,
    /**
     * Reads the opmode's gamepad field, like `{ gamepad1 }`. It is read on every use rather than kept, because the field
     * is still null while the opmode is being created, and the SDK assigns it again on every cycle of its event loop.
     */
    private val source: () -> Gamepad,
) : Controls(host, name) {
    /** The SDK gamepad, for anything this class doesn't cover, like rumble or the LED */
    val gamepad: Gamepad get() = source()

    val a: Button = button { gamepad.a }
    val b: Button = button { gamepad.b }
    val x: Button = button { gamepad.x }
    val y: Button = button { gamepad.y }

    val dpadUp: Button = button { gamepad.dpad_up }
    val dpadDown: Button = button { gamepad.dpad_down }
    val dpadLeft: Button = button { gamepad.dpad_left }
    val dpadRight: Button = button { gamepad.dpad_right }

    val leftBumper: Button = button { gamepad.left_bumper }
    val rightBumper: Button = button { gamepad.right_bumper }
    val leftStickButton: Button = button { gamepad.left_stick_button }
    val rightStickButton: Button = button { gamepad.right_stick_button }

    val back: Button = button { gamepad.back }
    val start: Button = button { gamepad.start }
    val guide: Button = button { gamepad.guide }
    val touchpad: Button = button { gamepad.touchpad }

    val leftTrigger: Axis = axis { gamepad.left_trigger.toDouble() }
    val rightTrigger: Axis = axis { gamepad.right_trigger.toDouble() }

    val leftStick: Stick = stick({ gamepad.left_stick_x.toDouble() }, { -gamepad.left_stick_y.toDouble() })
    val rightStick: Stick = stick({ gamepad.right_stick_x.toDouble() }, { -gamepad.right_stick_y.toDouble() })
}
