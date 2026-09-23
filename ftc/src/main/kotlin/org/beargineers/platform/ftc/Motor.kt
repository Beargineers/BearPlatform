package org.beargineers.platform.ftc

import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.DcMotorSimple.Direction
import org.beargineers.platform.component.Component
import org.beargineers.platform.config.config
import org.beargineers.platform.units.Angle
import org.beargineers.platform.units.AngularVelocity
import org.beargineers.platform.units.perSecond
import org.beargineers.platform.units.rotations
import kotlin.math.roundToLong

/**
 * A DC motor from the robot configuration, as a part of [parent]: `val fly1 = Motor(this, "fly1")`.
 *
 * Its direction, braking and gearing are config settings, so they can be fixed without rebuilding:
 * `shooter.fly1.direction = R`. The constructor arguments are the defaults.
 *
 * Position and velocity are measured at the output, after the gearing, using the encoder resolution of the motor
 * type picked in the robot configuration.
 */
class Motor(
    parent: Component,
    name: String,
    /** Name in the robot configuration, if it differs from [name] */
    device: String = name,
    direction: Direction = Direction.FORWARD,
    brake: Boolean = true,
    gearRatio: Double = 1.0,
) : Component(parent, name) {
    val direction by config(direction)

    /** Whether the motor holds its position at zero power, instead of coasting */
    val brake by config(brake)

    /** Motor turns per turn of what it drives, like 121.0 / 16 for a 16-tooth gear driving a 121-tooth one */
    val gearRatio by config(gearRatio)

    /** The SDK motor, for anything this class doesn't cover */
    val device: DcMotorEx by hardware(device)

    private var appliedDirection: Direction? = null
    private var appliedBrake: Boolean? = null
    private var zeroTicks = 0

    /**
     * Power from -1.0 to 1.0. Values outside are clamped. The motor is only sent new values,
     * which keeps the loop fast when code sets the same power every loop.
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

    /** The fastest the output can turn, according to the motor type */
    val maxVelocity: AngularVelocity
        get() = outputTurns(device.motorType.achieveableMaxTicksPerSecond).rotations.perSecond

    /** Makes the current position the new zero */
    fun resetPosition() {
        zeroTicks = device.currentPosition
    }

    override fun init() {
        device.mode = DcMotor.RunMode.RUN_WITHOUT_ENCODER
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
    }

    private fun outputTurns(ticks: Double): Double {
        val ticksPerTurn = device.motorType.ticksPerRev
        check(ticksPerTurn > 0) {
            "Motor ${describe()} doesn't know its encoder resolution. Pick its motor type in the robot configuration."
        }
        return ticks / ticksPerTurn / gearRatio
    }
}
