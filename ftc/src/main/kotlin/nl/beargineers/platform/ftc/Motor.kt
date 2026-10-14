package nl.beargineers.platform.ftc

import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.DcMotorSimple.Direction
import nl.beargineers.platform.component.Component
import nl.beargineers.platform.config.config
import nl.beargineers.platform.config.optionalConfig
import nl.beargineers.platform.units.Angle
import nl.beargineers.platform.units.AngularVelocity
import nl.beargineers.platform.units.perSecond
import nl.beargineers.platform.units.rotations
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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
    keepZero: Boolean = false,
) : Component(parent, name) {
    private val deviceName = device

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
     * as any speed is asked for, up to full: the motor overshoots, and a small stick push ends at full speed. So when
     * the encoder doesn't move for half a second under power, the motor is stopped and the Driver Station says why.
     */
    val velocityControl by config(velocityControl)

    /**
     * Keeps the position's zero from the first INIT after the robot app started, instead of taking a new one at every
     * INIT, so autonomous and TeleOp share it: for a turret or an arm set to a known position once, before the match.
     * [resetPosition] still sets a new zero. A hub that lost power counts from 0 again, which this can't tell.
     */
    val keepZero by config(keepZero)

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
     * A new [power] is sent to the hub only once it differs from the power last sent by at least this much: each write
     * takes about 1.6 ms of the hubs' one connection, which all motors and sensors share, and code like a PID changes
     * the power a little every loop. Small changes add up until they reach it, so the motor follows within this much.
     * Stopping and full power are always sent exactly.
     */
    val powerTolerance by config(0.005)

    /**
     * Power from -1.0 to 1.0, or with [velocityControl] the fraction of top speed. Values outside are clamped. The hub
     * is sent it when it differs enough from what it has: see [powerTolerance].
     */
    var power: Double = 0.0
        set(value) {
            val clamped = value.coerceIn(-1.0, 1.0)
            field = clamped
            if (clamped == sentPower || encoderStuck) return
            val exact = clamped == 0.0 || clamped == 1.0 || clamped == -1.0
            if (!exact && abs(clamped - sentPower) < powerTolerance) return
            send(clamped)
        }

    /** The power the hub has: [power], give or take [powerTolerance] */
    var sentPower: Double = 0.0
        private set

    private fun send(value: Double) {
        sentPower = value
        // Each write is a command to the hub, which waits for the hub's answer
        timed("motor writes", counted = true) { device.power = value }
    }

    /** Encoder ticks since the start or the last [resetPosition] */
    val ticks: Int get() = device.currentPosition - zeroTicks

    /** How far the output turned since the start or the last [resetPosition] */
    val position: Angle get() = outputTurns(ticks.toDouble()).rotations

    val velocity: AngularVelocity get() = outputTurns(device.velocity).rotations.perSecond

    /** Makes the current position the new zero; with [keepZero], for the following opmodes too */
    fun resetPosition() {
        zeroTicks = device.currentPosition
        if (keepZero) keptZeros[deviceName] = zeroTicks
    }

    override fun init() {
        applySettings()
        zeroTicks = if (keepZero) keptZeros.getOrPut(deviceName) { device.currentPosition } else device.currentPosition
        device.power = 0.0
    }

    override fun update() {
        applySettings()
        if (velocityControl) checkEncoder()
        if (encoderStuck) show(path, "stopped: its encoder doesn't move under velocity control. Is it plugged in?")
    }

    // Velocity control without an encoder signal runs the motor up to full power: stop it instead
    private var encoderStuck = false
    private var lastCheck = Duration.ZERO
    private var lastCheckedTicks = 0
    private var stillSince: Duration? = null

    private fun checkEncoder() {
        val now = runtime.elapsed
        // Reading the encoder may take a bulk read; a few times a second is enough
        if (encoderStuck || now - lastCheck < ENCODER_CHECK_EVERY) return
        lastCheck = now
        val current = device.currentPosition
        if (abs(sentPower) < 0.2 || current != lastCheckedTicks) {
            lastCheckedTicks = current
            stillSince = null
            return
        }
        val since = stillSince ?: now.also { stillSince = it }
        if (now - since >= ENCODER_STUCK_AFTER) {
            encoderStuck = true
            val stuckAt = sentPower
            send(0.0)
            log(
                String.format(Locale.US, "Stopped: velocity control is on, the power was %.2f, and the encoder didn't move. ", stuckAt) +
                    "Is the encoder plugged in, or is the mechanism stuck?"
            )
        }
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
            send(power)
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

    private companion object {
        val ENCODER_CHECK_EVERY = 100.milliseconds
        val ENCODER_STUCK_AFTER = 500.milliseconds

        /** Zeros kept for the app's run, by device name in the robot configuration (see [keepZero]) */
        val keptZeros = ConcurrentHashMap<String, Int>()
    }

    internal object KeptZeros {
        /** For tests: as if the robot app had just started */
        fun forget() = keptZeros.clear()
    }
}
