package org.beargineers.platform.ftc

import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.DcMotorSimple.Direction
import org.beargineers.platform.component.Component
import org.beargineers.platform.config.config
import org.beargineers.platform.config.optionalConfig
import org.beargineers.platform.units.Angle
import org.beargineers.platform.units.AngularVelocity
import org.beargineers.platform.units.perSecond
import org.beargineers.platform.units.rotations
import kotlin.math.roundToLong

/**
 * A DC motor from the robot configuration, as a part of [parent]: `val fly1 = Motor(this, "fly1")`.
 *
 * Its direction, braking, speed control, encoder resolution and gearing are config settings, so they can be fixed
 * without rebuilding: `shooter.fly1.direction = R`. The constructor arguments are the defaults.
 *
 * Position and velocity are measured at the output, after [gearRatio], using [ticksPerTurn].
 */
class Motor(
    parent: Component,
    name: String,
    /** Name in the robot configuration, if it differs from [name] */
    device: String = name,
    direction: Direction = Direction.FORWARD,
    brake: Boolean = true,
    velocityControl: Boolean = false,
    ticksPerTurn: Double? = null,
    gearRatio: Double = 1.0,
) : Component(parent, name) {
    val direction by config(direction)

    /** Whether the motor holds its position at zero power, instead of coasting */
    val brake by config(brake)

    /**
     * Whether the hub holds the motor's speed using its encoder: [power] then asks for a fraction of the motor's top
     * speed, which the motor keeps whatever the load, friction or battery, even at low powers that wouldn't move it
     * otherwise. The top speed comes from the motor type in the robot configuration; for goBILDA and REV motors it is
     * right whatever the gearbox.
     *
     * Needs the encoder plugged in. Without it the hub never sees the motor move, so it keeps raising the power as long
     * as any speed is asked for, up to full: the motor overshoots, and a small stick push ends at full speed.
     */
    val velocityControl by config(velocityControl)

    /**
     * Encoder ticks per turn of the motor's own output shaft: the encoder resolution "at the output shaft" from the
     * motor's product page, like 1425.1 for a goBILDA 117 RPM. [position] and [velocity] need it; a motor whose encoder
     * isn't used doesn't.
     *
     * The motor type in the robot configuration isn't used for it, as it is wrong for most current motors: the SDK's
     * goBILDA type has the 60 RPM model's numbers, and its REV UltraPlanetary type assumes a 20:1 gearbox.
     */
    val ticksPerTurn by optionalConfig(ticksPerTurn)

    /**
     * Turns of the motor's output shaft per turn of what it drives, for gears after the motor, like 121.0 / 16 for
     * a 16-tooth gear driving a 121-tooth one
     */
    val gearRatio by config(gearRatio)

    /** The SDK motor, for anything this class doesn't cover */
    val device: DcMotorEx by hardware(device)

    private var appliedDirection: Direction? = null
    private var appliedBrake: Boolean? = null
    private var appliedVelocityControl: Boolean? = null
    private var zeroTicks = 0

    /**
     * Power from -1.0 to 1.0, or with [velocityControl] the fraction of top speed. Values outside are clamped. The
     * motor is only sent new values, which keeps the loop fast when code sets the same power every loop.
     */
    var power: Double = 0.0
        set(value) {
            val rounded = (value.coerceIn(-1.0, 1.0) * 1000).roundToLong() / 1000.0
            if (rounded != field) {
                field = rounded
                device.power = rounded
            }
        }

    /** Encoder ticks since the start or the last [resetPosition] */
    val ticks: Int get() = device.currentPosition - zeroTicks

    /** How far the output turned since the start or the last [resetPosition] */
    val position: Angle get() = outputTurns(ticks.toDouble()).rotations

    val velocity: AngularVelocity get() = outputTurns(device.velocity).rotations.perSecond

    /** Makes the current position the new zero */
    fun resetPosition() {
        zeroTicks = device.currentPosition
    }

    override fun init() {
        applySettings()
        resetPosition()
        device.power = 0.0
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
        if (appliedBrake != brake) {
            device.zeroPowerBehavior = if (brake) DcMotor.ZeroPowerBehavior.BRAKE else DcMotor.ZeroPowerBehavior.FLOAT
            appliedBrake = brake
        }
        val velocityControl = velocityControl
        if (appliedVelocityControl != velocityControl) {
            device.mode = if (velocityControl) DcMotor.RunMode.RUN_USING_ENCODER else DcMotor.RunMode.RUN_WITHOUT_ENCODER
            // Power means something else in the new mode: send it again
            device.power = power
            appliedVelocityControl = velocityControl
        }
    }

    private fun outputTurns(ticks: Double): Double {
        val ticksPerTurn = ticksPerTurn?.takeIf { it > 0 } ?: error(
            "Motor ${describe()} needs its encoder resolution to measure position or velocity. " +
                "Set $configScope.ticksPerTurn to the resolution at the output shaft from the motor's product page, " +
                "like 1425.1 for goBILDA 117 RPM, 384.5 for goBILDA 435 RPM or 288 for REV Core Hex."
        )
        return ticks / ticksPerTurn / gearRatio
    }
}
