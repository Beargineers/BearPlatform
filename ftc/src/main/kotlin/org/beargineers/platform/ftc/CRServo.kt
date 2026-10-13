package org.beargineers.platform.ftc

import com.qualcomm.robotcore.hardware.DcMotorSimple.Direction
import org.beargineers.platform.component.Component
import org.beargineers.platform.config.config
import org.beargineers.platform.config.optionalConfig
import kotlin.math.abs
import com.qualcomm.robotcore.hardware.CRServo as SdkCRServo

/**
 * A continuous rotation servo, which turns at a power rather than to a position, as a part of [parent]:
 * `val roller = CRServo(this, "roller")`.
 *
 * Its direction, pulse widths and power tolerance are config settings (`intake.roller.direction = R`); the
 * constructor argument is the default. Each new power is a command to the hub, so it is only sent when it differs
 * enough from what the hub has, and the servo stops when the opmode does.
 */
class CRServo(
    parent: Component,
    name: String,
    /** Name in the robot configuration, if it differs from [name] */
    device: String = name,
    direction: Direction = Direction.FORWARD,
) : Component(parent, name) {
    val direction by config(direction)

    /**
     * Pulse widths in microseconds for full power backward and forward, when the servo needs other ones than the
     * SDK's 600 and 2400: many reach full speed only at 500 and 2500
     */
    val pwmMin by optionalConfig<Double>()
    val pwmMax by optionalConfig<Double>()

    /**
     * A new power is sent only when it differs from what the hub has by at least this much, so code nudging the power
     * every loop doesn't write every loop. Stopping and full power are always sent exactly.
     */
    val powerTolerance by config(0.005)

    /** The SDK servo, for anything this class doesn't cover */
    val device: SdkCRServo by hardware(device)

    private var appliedDirection: Direction? = null
    private val pwmRange = PwmRangeSetting()

    /** Power from -1.0 to 1.0; values outside are clamped. The hub is sent it when it differs enough: see [powerTolerance]. */
    var power: Double = 0.0
        set(value) {
            val clamped = value.coerceIn(-1.0, 1.0)
            field = clamped
            if (clamped == sentPower) return
            val exact = clamped == 0.0 || clamped == 1.0 || clamped == -1.0
            if (!exact && abs(clamped - sentPower) < powerTolerance) return
            sentPower = clamped
            timed("servo writes", counted = true) { device.power = clamped }
        }

    /** The power the hub has: [power], give or take [powerTolerance] */
    var sentPower: Double = 0.0
        private set

    override fun init() {
        applySettings()
    }

    override fun update() {
        applySettings()
    }

    override fun stop() {
        power = 0.0
    }

    private fun applySettings() {
        if (appliedDirection != direction) {
            device.direction = direction
            appliedDirection = direction
        }
        pwmRange.apply(device, pwmMin, pwmMax)
    }
}
