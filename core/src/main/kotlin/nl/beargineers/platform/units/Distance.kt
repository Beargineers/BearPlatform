package nl.beargineers.platform.units

import java.util.Locale
import kotlin.time.Duration

internal const val CM_PER_INCH = 2.54
private const val INCHES_PER_TILE = 24.0

/**
 * A length. Create one with `10.cm`, `4.inch` or `2.tiles` and read it back with [inCm], [inInches], etc.
 *
 * Stored in centimeters. Being a value class, it costs the same as a plain `Double` at runtime.
 */
@JvmInline
value class Distance internal constructor(val inCm: Double) : Comparable<Distance> {
    val inMm: Double get() = inCm * 10.0
    val inMeters: Double get() = inCm / 100.0
    val inInches: Double get() = inCm / CM_PER_INCH
    val inTiles: Double get() = inInches / INCHES_PER_TILE

    operator fun plus(other: Distance): Distance = Distance(inCm + other.inCm)
    operator fun minus(other: Distance): Distance = Distance(inCm - other.inCm)
    operator fun unaryMinus(): Distance = Distance(-inCm)

    operator fun times(factor: Double): Distance = Distance(inCm * factor)
    operator fun times(factor: Int): Distance = Distance(inCm * factor)
    operator fun div(divisor: Double): Distance = Distance(inCm / divisor)
    operator fun div(divisor: Int): Distance = Distance(inCm / divisor)

    /** How many times [other] fits into this distance */
    operator fun div(other: Distance): Double = inCm / other.inCm

    /** Average speed needed to cover this distance in [time] */
    operator fun div(time: Duration): LinearVelocity = LinearVelocity(inCm / time.inSeconds())

    override fun compareTo(other: Distance): Int = inCm.compareTo(other.inCm)

    override fun toString(): String = String.format(Locale.US, "%.1fcm", inCm)

    companion object {
        val ZERO: Distance = Distance(0.0)
    }
}

val Int.cm: Distance get() = Distance(toDouble())
val Double.cm: Distance get() = Distance(this)
val Int.mm: Distance get() = Distance(this / 10.0)
val Double.mm: Distance get() = Distance(this / 10.0)
val Int.meters: Distance get() = Distance(this * 100.0)
val Double.meters: Distance get() = Distance(this * 100.0)
val Int.inch: Distance get() = Distance(this * CM_PER_INCH)
val Double.inch: Distance get() = Distance(this * CM_PER_INCH)

/** Standard FTC field tiles, 24 inches each */
val Int.tiles: Distance get() = Distance(this * INCHES_PER_TILE * CM_PER_INCH)
val Double.tiles: Distance get() = Distance(this * INCHES_PER_TILE * CM_PER_INCH)

operator fun Int.times(distance: Distance): Distance = distance * this
operator fun Double.times(distance: Distance): Distance = distance * this

fun abs(distance: Distance): Distance = Distance(kotlin.math.abs(distance.inCm))

/** Length of the hypotenuse of a right triangle with legs [x] and [y] */
fun hypot(x: Distance, y: Distance): Distance = Distance(kotlin.math.hypot(x.inCm, y.inCm))
