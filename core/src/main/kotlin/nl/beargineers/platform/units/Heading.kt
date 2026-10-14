package nl.beargineers.platform.units

import java.util.Locale
import kotlin.math.PI

/**
 * A direction on the field, like the way the robot faces. Heading 0 faces +x and positive is counterclockwise.
 *
 * A heading always stays within (-180°, 180°], so there is nothing to normalize by hand:
 * - `heading + 90.degrees` and `heading - 90.degrees` turn it, wrapping around as needed;
 * - `target - current` is the shortest turn from `current` to `target`: an [Angle] within (-180°, 180°].
 *
 * Get one from an angle with [Angle.toHeading], e.g. `135.degrees.toHeading()`. Most places that take a heading,
 * like `Pose(x, y, 135.degrees)`, also accept an [Angle] and convert it for you.
 */
@JvmInline
value class Heading private constructor(val inRadians: Double) {
    val inDegrees: Double get() = Math.toDegrees(inRadians)

    operator fun plus(turn: Angle): Heading = fromRadians(inRadians + turn.inRadians)
    operator fun minus(turn: Angle): Heading = fromRadians(inRadians - turn.inRadians)

    /** The shortest turn from [other] to this heading, within (-180°, 180°] */
    operator fun minus(other: Heading): Angle = Angle(normalizeRadians(inRadians - other.inRadians))

    /** This heading as an angle within (-180°, 180°] */
    fun toAngle(): Angle = Angle(inRadians)

    override fun toString(): String = String.format(Locale.US, "%.1f°", inDegrees)

    companion object {
        val ZERO: Heading = Heading(0.0)

        internal fun fromRadians(radians: Double): Heading = Heading(normalizeRadians(radians))
    }
}

fun sin(heading: Heading): Double = kotlin.math.sin(heading.inRadians)
fun cos(heading: Heading): Double = kotlin.math.cos(heading.inRadians)

/** Wraps any number of radians into (-π, π] */
internal fun normalizeRadians(radians: Double): Double {
    // % drops all whole turns, leaving (-360°, 360°); at most one more turn then lands in (-180°, 180°]
    var r = radians % TWO_PI
    if (r > PI) r -= TWO_PI
    else if (r <= -PI) r += TWO_PI
    return r
}
