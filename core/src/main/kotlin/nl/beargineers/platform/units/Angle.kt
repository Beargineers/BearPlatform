package nl.beargineers.platform.units

import java.util.Locale
import kotlin.math.PI
import kotlin.time.Duration

internal const val TWO_PI = 2 * PI

/**
 * An amount of turning. Create one with `90.degrees`, `1.5.radians` or `0.25.rotations` and read it back
 * with [inDegrees], [inRadians] or [inRotations]. Positive angles are counterclockwise.
 *
 * Arithmetic never wraps around: `270.degrees + 180.degrees` is 450°, which is what a turret, an arm or counting
 * rotations needs. For the direction the robot faces use [Heading], which wraps around by itself.
 */
@JvmInline
value class Angle internal constructor(val inRadians: Double) : Comparable<Angle> {
    val inDegrees: Double get() = Math.toDegrees(inRadians)
    val inRotations: Double get() = inRadians / TWO_PI

    operator fun plus(other: Angle): Angle = Angle(inRadians + other.inRadians)
    operator fun minus(other: Angle): Angle = Angle(inRadians - other.inRadians)
    operator fun unaryMinus(): Angle = Angle(-inRadians)

    operator fun times(factor: Double): Angle = Angle(inRadians * factor)
    operator fun times(factor: Int): Angle = Angle(inRadians * factor)
    operator fun div(divisor: Double): Angle = Angle(inRadians / divisor)
    operator fun div(divisor: Int): Angle = Angle(inRadians / divisor)

    /** How many times [other] fits into this angle */
    operator fun div(other: Angle): Double = inRadians / other.inRadians

    /** Average turning speed needed to turn by this angle in [time] */
    operator fun div(time: Duration): AngularVelocity = AngularVelocity(inRadians / time.inSeconds())

    /** The same direction, expressed within (-180°, 180°] */
    fun normalized(): Angle = Angle(normalizeRadians(inRadians))

    /** The direction this angle points to, measured from +x */
    fun toHeading(): Heading = Heading.fromRadians(inRadians)

    override fun compareTo(other: Angle): Int = inRadians.compareTo(other.inRadians)

    override fun toString(): String = String.format(Locale.US, "%.1f°", inDegrees)

    companion object {
        val ZERO: Angle = Angle(0.0)
    }
}

val Int.degrees: Angle get() = Angle(Math.toRadians(toDouble()))
val Double.degrees: Angle get() = Angle(Math.toRadians(this))
val Int.radians: Angle get() = Angle(toDouble())
val Double.radians: Angle get() = Angle(this)

/** Full turns: `0.25.rotations` is 90° */
val Int.rotations: Angle get() = Angle(this * TWO_PI)
val Double.rotations: Angle get() = Angle(this * TWO_PI)

operator fun Int.times(angle: Angle): Angle = angle * this
operator fun Double.times(angle: Angle): Angle = angle * this

fun abs(angle: Angle): Angle = Angle(kotlin.math.abs(angle.inRadians))
fun sin(angle: Angle): Double = kotlin.math.sin(angle.inRadians)
fun cos(angle: Angle): Double = kotlin.math.cos(angle.inRadians)
fun tan(angle: Angle): Double = kotlin.math.tan(angle.inRadians)

/** Direction of the vector ([x], [y]), measured counterclockwise from +x */
fun atan2(y: Distance, x: Distance): Angle = Angle(kotlin.math.atan2(y.inCm, x.inCm))
