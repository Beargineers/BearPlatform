package nl.beargineers.platform.units

import java.util.Locale
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * How fast something moves along a line. Create one with `50.cm.perSecond` or by dividing a [Distance]
 * by a [Duration]: `2.tiles / 1.5.seconds`.
 */
@JvmInline
value class LinearVelocity internal constructor(val inCmPerSecond: Double) : Comparable<LinearVelocity> {
    val inInchesPerSecond: Double get() = inCmPerSecond / CM_PER_INCH

    operator fun plus(other: LinearVelocity): LinearVelocity = LinearVelocity(inCmPerSecond + other.inCmPerSecond)
    operator fun minus(other: LinearVelocity): LinearVelocity = LinearVelocity(inCmPerSecond - other.inCmPerSecond)
    operator fun unaryMinus(): LinearVelocity = LinearVelocity(-inCmPerSecond)

    operator fun times(factor: Double): LinearVelocity = LinearVelocity(inCmPerSecond * factor)
    operator fun times(factor: Int): LinearVelocity = LinearVelocity(inCmPerSecond * factor)
    operator fun div(divisor: Double): LinearVelocity = LinearVelocity(inCmPerSecond / divisor)
    operator fun div(divisor: Int): LinearVelocity = LinearVelocity(inCmPerSecond / divisor)
    operator fun div(other: LinearVelocity): Double = inCmPerSecond / other.inCmPerSecond

    /** Distance covered at this speed in [time] */
    operator fun times(time: Duration): Distance = Distance(inCmPerSecond * time.inSeconds())

    override fun compareTo(other: LinearVelocity): Int = inCmPerSecond.compareTo(other.inCmPerSecond)

    override fun toString(): String = String.format(Locale.US, "%.1fcm/s", inCmPerSecond)

    companion object {
        val ZERO: LinearVelocity = LinearVelocity(0.0)
    }
}

/**
 * How fast something turns. Positive is counterclockwise. Create one with `90.degrees.perSecond`, `435.rpm`
 * or by dividing an [Angle] by a [Duration].
 */
@JvmInline
value class AngularVelocity internal constructor(val inRadiansPerSecond: Double) : Comparable<AngularVelocity> {
    val inDegreesPerSecond: Double get() = Math.toDegrees(inRadiansPerSecond)
    val inRpm: Double get() = inRadiansPerSecond / TWO_PI * 60.0

    operator fun plus(other: AngularVelocity): AngularVelocity = AngularVelocity(inRadiansPerSecond + other.inRadiansPerSecond)
    operator fun minus(other: AngularVelocity): AngularVelocity = AngularVelocity(inRadiansPerSecond - other.inRadiansPerSecond)
    operator fun unaryMinus(): AngularVelocity = AngularVelocity(-inRadiansPerSecond)

    operator fun times(factor: Double): AngularVelocity = AngularVelocity(inRadiansPerSecond * factor)
    operator fun times(factor: Int): AngularVelocity = AngularVelocity(inRadiansPerSecond * factor)
    operator fun div(divisor: Double): AngularVelocity = AngularVelocity(inRadiansPerSecond / divisor)
    operator fun div(divisor: Int): AngularVelocity = AngularVelocity(inRadiansPerSecond / divisor)
    operator fun div(other: AngularVelocity): Double = inRadiansPerSecond / other.inRadiansPerSecond

    /** Angle turned at this speed in [time] */
    operator fun times(time: Duration): Angle = Angle(inRadiansPerSecond * time.inSeconds())

    override fun compareTo(other: AngularVelocity): Int = inRadiansPerSecond.compareTo(other.inRadiansPerSecond)

    override fun toString(): String = String.format(Locale.US, "%.1f°/s", inDegreesPerSecond)

    companion object {
        val ZERO: AngularVelocity = AngularVelocity(0.0)
    }
}

val Distance.perSecond: LinearVelocity get() = LinearVelocity(inCm)
val Angle.perSecond: AngularVelocity get() = AngularVelocity(inRadians)

/** Revolutions per minute, as listed in motor specs */
val Int.rpm: AngularVelocity get() = AngularVelocity(this * TWO_PI / 60.0)
val Double.rpm: AngularVelocity get() = AngularVelocity(this * TWO_PI / 60.0)

fun abs(velocity: LinearVelocity): LinearVelocity = LinearVelocity(kotlin.math.abs(velocity.inCmPerSecond))
fun abs(velocity: AngularVelocity): AngularVelocity = AngularVelocity(kotlin.math.abs(velocity.inRadiansPerSecond))

internal fun Duration.inSeconds(): Double = toDouble(DurationUnit.SECONDS)
