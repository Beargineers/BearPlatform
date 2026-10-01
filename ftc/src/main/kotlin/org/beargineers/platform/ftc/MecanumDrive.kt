package org.beargineers.platform.ftc

import com.qualcomm.robotcore.hardware.DcMotorSimple.Direction
import kotlinx.coroutines.withTimeoutOrNull
import org.beargineers.platform.component.Component
import org.beargineers.platform.config.config
import org.beargineers.platform.config.optionalConfig
import org.beargineers.platform.drive.Drivetrain
import org.beargineers.platform.drive.MecanumPowers
import org.beargineers.platform.runtime.everyTick
import kotlin.time.Duration.Companion.seconds

/**
 * A mecanum drive, with motors named leftFront, rightFront, leftBack and rightBack in the robot configuration.
 * Create it on the robot as `val drive = MecanumDrive(this)`; its settings start with `drive.` Pass [ticksPerTurn], the
 * drive motors' encoder resolution (384.5 for goBILDA 435 RPM, 537.7 for 312 RPM), only to read the wheels' positions
 * or velocities.
 *
 * The left motors run reversed by default, as they are mounted mirrored to the right ones. [checkWheels] shows
 * whether each wheel spins the right way; fix one that doesn't in config: `drive.leftFront.direction = F`.
 *
 * With [velocityControl] the hub holds each wheel's speed using its encoder, so powers ask for speeds: small ones
 * still move the robot, and the battery doesn't matter. It needs the drive encoders plugged in (see
 * [Motor.velocityControl]).
 */
class MecanumDrive(
    parent: Component,
    name: String = "drive",
    velocityControl: Boolean = false,
    ticksPerTurn: Double? = null,
) : Drivetrain(parent, name) {
    val leftFront = wheel("leftFront", Direction.REVERSE, velocityControl, ticksPerTurn)
    val rightFront = wheel("rightFront", Direction.FORWARD, velocityControl, ticksPerTurn)
    val leftBack = wheel("leftBack", Direction.REVERSE, velocityControl, ticksPerTurn)
    val rightBack = wheel("rightBack", Direction.FORWARD, velocityControl, ticksPerTurn)

    /** Scales every power: lower it to drive slower, like 0.3 for the first tries */
    val maxPower by config(1.0)

    /**
     * When set, powers are scaled by this over the battery's voltage, so the robot drives the same on a full battery as
     * on a tired one: on 13 V with 12 here, a power of 0.5 becomes 0.46. Path following stays tuned as the battery runs
     * down. Full power stays full power, so a tired battery still loses top speed.
     */
    val nominalVoltage by optionalConfig<Double>()

    /**
     * Multiplies the sideways power, as mecanum wheels slip more moving sideways than forward. Raise it until a
     * sideways push of the stick is as fast as a forward one.
     */
    val strafeScale by config(1.0)

    override val canStrafe: Boolean get() = true

    private val wheels = listOf(leftFront, rightFront, leftBack, rightBack)

    override fun drive(forward: Double, right: Double, turn: Double) {
        val compensation = nominalVoltage?.let { nominal -> (robot.host as? FtcHost)?.batteryVoltage?.let { nominal / it } } ?: 1.0
        val powers = MecanumPowers.of(forward * compensation, right * strafeScale * compensation, turn * compensation)
        val max = maxPower
        leftFront.power = powers.leftFront * max
        rightFront.power = powers.rightFront * max
        leftBack.power = powers.leftBack * max
        rightBack.power = powers.rightBack * max
    }

    private fun wheel(name: String, direction: Direction, velocityControl: Boolean, ticksPerTurn: Double?) =
        Motor(this, name, direction = direction, velocityControl = velocityControl, ticksPerTurn = ticksPerTurn)

    /**
     * Spins the wheels one at a time for a second each, at [power], showing which one on the Driver Station. Each
     * should turn so it would push the robot forward; a wheel that turns backward needs its direction changed in
     * config, and a different wheel turning means the motors are plugged into other ports than configured.
     */
    suspend fun checkWheels(power: Double = 0.3) = takeOver("check wheels") {
        for (wheel in wheels) {
            try {
                wheel.power = power
                withTimeoutOrNull(1.seconds) {
                    everyTick { show("Checking wheel", "${wheel.name}: should push the robot forward") }
                }
            } finally {
                wheel.power = 0.0
            }
        }
    }
}
