package org.beargineers.platform.ftc

import com.qualcomm.robotcore.hardware.PwmControl
import com.qualcomm.robotcore.hardware.Servo
import org.beargineers.platform.component.Component
import org.beargineers.platform.indicator.Indicator

/** Colors of goBILDA's RGB indicator light, by servo position with pulses from 500 to 2500 µs */
enum class RgbColor(internal val position: Double) {
    OFF(0.2),
    RED(0.277),
    ORANGE(0.333),
    YELLOW(0.388),
    SAGE(0.444),
    GREEN(0.5),
    AZURE(0.555),
    BLUE(0.611),
    INDIGO(0.666),
    VIOLET(0.722),
    WHITE(1.0),
}

/**
 * goBILDA's RGB indicator light, plugged into a servo port and configured as a servo, as a part of [parent]:
 * `val light = RgbLight(this)`. Parts of the robot show colors on it through signals, see [Indicator]:
 * `light.signal(priority = 10).show(RgbColor.GREEN)`.
 *
 * Each change of color is one command to the hub, like a servo's position.
 */
class RgbLight(
    parent: Component,
    name: String = "light",
    /** Name in the robot configuration, if it differs from [name] */
    device: String = name,
) : Indicator<RgbColor>(parent, name, RgbColor.OFF) {
    /** The SDK servo, for anything this class doesn't cover */
    val device: Servo by hardware(device)

    override fun init() {
        // The light's colors span pulses from 500 to 2500 µs, wider than the SDK's servo default
        (device as? PwmControl)?.pwmRange = PwmControl.PwmRange(500.0, 2500.0)
        super.init()
    }

    override fun display(state: RgbColor) {
        timed("light writes", counted = true) { device.position = state.position }
    }
}
