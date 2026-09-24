package org.beargineers.platform.config

import org.beargineers.platform.control.Feedforward
import org.beargineers.platform.control.PidGains
import org.beargineers.platform.geometry.Field
import org.beargineers.platform.geometry.Point
import org.beargineers.platform.geometry.Pose
import org.beargineers.platform.units.Angle
import org.beargineers.platform.units.Distance
import org.beargineers.platform.units.Heading
import org.beargineers.platform.units.cm
import org.beargineers.platform.units.degrees
import org.beargineers.platform.units.inch
import org.beargineers.platform.units.meters
import org.beargineers.platform.units.mm
import org.beargineers.platform.units.radians
import org.beargineers.platform.units.rotations
import org.beargineers.platform.units.tiles
import kotlin.reflect.KClass
import kotlin.time.Duration

/**
 * How config reads values of one type from text, and writes them back.
 * [parse] throws an exception with a short explanation, like "expected a number", when the text doesn't fit.
 */
class ConfigType<T : Any>(
    val name: String,
    val parse: (String) -> T,
    val format: (T) -> String = { it.toString() },
)

internal fun standardConfigTypes(): Map<KClass<*>, ConfigType<*>> = mapOf(
    Int::class to ConfigType("whole number", { it.trim().toIntOrNull() ?: fail("expected a whole number") }),
    Double::class to ConfigType("number", { it.trim().toDoubleOrNull() ?: fail("expected a number") }, ::formatNumber),
    Boolean::class to ConfigType("true/false", ::parseBoolean),
    String::class to ConfigType("text", { it }),
    Distance::class to ConfigType("distance", ::parseDistance, ::formatDistance),
    Angle::class to ConfigType("angle", ::parseAngle, ::formatAngle),
    Heading::class to ConfigType("heading", { parseAngle(it).toHeading() }, { formatAngle(it.toAngle()) }),
    Duration::class to ConfigType("duration", ::parseDuration),
    Point::class to ConfigType("point", ::parsePoint) { "${formatDistance(it.x)}, ${formatDistance(it.y)}" },
    Pose::class to ConfigType("pose", ::parsePose) {
        "${formatDistance(it.x)}, ${formatDistance(it.y)}, ${formatAngle(it.heading.toAngle())}"
    },
    PidGains::class to ConfigType("PID gains", { text ->
        val (p, i, d) = parseNamedNumbers(text, listOf("p", "i", "d"), "PID gains like 'p=0.02, i=0, d=0.001'")
        PidGains(p, i, d)
    }) { "p=${formatNumber(it.p)}, i=${formatNumber(it.i)}, d=${formatNumber(it.d)}" },
    Feedforward::class to ConfigType("feedforward", { text ->
        val (kS, kV, kA) = parseNamedNumbers(text, listOf("kS", "kV", "kA"), "feedforward like 'kS=0.04, kV=0.0085, kA=0'")
        Feedforward(kS, kV, kA)
    }) { "kS=${formatNumber(it.kS)}, kV=${formatNumber(it.kV)}, kA=${formatNumber(it.kA)}" },
)

/**
 * Reads up to [names].size numbers, either in order ("0.02, 0, 0.001") or by name in any order ("p=0.02, d=0.001").
 * Numbers left out are zero.
 */
private fun parseNamedNumbers(text: String, names: List<String>, expected: String): List<Double> {
    val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty() || parts.size > names.size) fail("expected $expected")

    val values = DoubleArray(names.size)
    when {
        parts.none { '=' in it } -> parts.forEachIndexed { index, part ->
            values[index] = part.toDoubleOrNull() ?: fail("expected $expected")
        }
        parts.all { '=' in it } -> for (part in parts) {
            val (name, number) = part.split('=', limit = 2).map { it.trim() }
            val index = names.indexOfFirst { it.equals(name, ignoreCase = true) }
            if (index < 0) fail("unknown '$name', expected $expected")
            values[index] = number.toDoubleOrNull() ?: fail("expected $expected")
        }
        else -> fail("expected $expected, either all named or none")
    }
    return values.toList()
}

internal fun <T : Any> enumConfigType(kClass: KClass<T>): ConfigType<T> {
    val constants = kClass.java.enumConstants
    fun nameOf(constant: T) = (constant as Enum<*>).name
    return ConfigType(kClass.java.simpleName, parse = { text ->
        val wanted = text.trim()
        constants.firstOrNull { nameOf(it).equals(wanted, ignoreCase = true) }
            ?: run {
                // A unique beginning is enough: F for FORWARD, R for REVERSE
                val matches = if (wanted.isEmpty()) emptyList() else constants.filter { nameOf(it).startsWith(wanted, ignoreCase = true) }
                matches.singleOrNull() ?: fail(
                    if (matches.isEmpty()) "expected one of ${constants.joinToString { nameOf(it) }}"
                    else "could mean any of ${matches.joinToString { nameOf(it) }}"
                )
            }
    }, format = ::nameOf)
}

private fun fail(explanation: String): Nothing = throw IllegalArgumentException(explanation)

private fun parseBoolean(text: String): Boolean = when (text.trim().lowercase()) {
    "true", "t", "yes", "y", "on", "1" -> true
    "false", "f", "no", "n", "off", "0" -> false
    else -> fail("expected true or false")
}

/** Splits "12.5 in" into 12.5 and "in" */
private fun numberAndUnit(text: String, expected: String): Pair<Double, String> {
    val trimmed = text.trim()
    val unitStart = trimmed.indexOfFirst { it.isLetter() || it == '°' || it == '"' }
    val number = if (unitStart < 0) trimmed else trimmed.substring(0, unitStart)
    val unit = if (unitStart < 0) "" else trimmed.substring(unitStart).trim().lowercase()
    return (number.trim().toDoubleOrNull() ?: fail("expected $expected")) to unit
}

private fun parseDistance(text: String): Distance {
    val expected = "a distance like 30cm or 12in (plain numbers are cm)"
    val (number, unit) = numberAndUnit(text, expected)
    return when (unit) {
        "", "cm" -> number.cm
        "mm" -> number.mm
        "m", "meter", "meters" -> number.meters
        "in", "inch", "inches", "\"" -> number.inch
        "ft", "foot", "feet" -> (number * 12).inch
        "tile", "tiles" -> number.tiles
        else -> fail("expected $expected; unknown unit '$unit'")
    }
}

private fun parseAngle(text: String): Angle {
    val expected = "an angle like 90deg or 1.5rad (plain numbers are degrees)"
    val (number, unit) = numberAndUnit(text, expected)
    return when (unit) {
        "", "deg", "degree", "degrees", "°" -> number.degrees
        "rad", "radian", "radians" -> number.radians
        "rot", "rotation", "rotations" -> number.rotations
        else -> fail("expected $expected; unknown unit '$unit'")
    }
}

private fun parseDuration(text: String): Duration =
    Duration.parseOrNull(text.trim()) ?: fail("expected a duration with a unit, like 300ms or 1.5s")

private fun tileField(): Field =
    Config.field ?: fail("tile codes need Config.field to be set to the season's Field")

private fun parsePoint(text: String): Point {
    val trimmed = text.trim()
    if (trimmed.firstOrNull()?.isLetter() == true) return tileField().tile(trimmed)

    val parts = trimmed.split(',')
    if (parts.size != 2) fail("expected x, y like '10cm, -20cm' or a tile like F6TR")
    return Point(parseDistance(parts[0]), parseDistance(parts[1]))
}

private fun parsePose(text: String): Pose {
    val trimmed = text.trim()
    if (trimmed.firstOrNull()?.isLetter() == true) {
        val parts = trimmed.split(':')
        if (parts.size != 2) fail("expected a tile and a heading like F6TR:135")
        return tileField().tile(parts[0]).withHeading(parseAngle(parts[1]))
    }

    val parts = trimmed.split(',')
    if (parts.size != 3) fail("expected x, y, heading like '10cm, -20cm, 90deg' or a tile and a heading like F6TR:135")
    return Pose(parseDistance(parts[0]), parseDistance(parts[1]), parseAngle(parts[2]))
}

private fun formatNumber(value: Double): String = when {
    !value.isFinite() -> value.toString()
    value == Math.rint(value) && kotlin.math.abs(value) < 1e15 -> value.toLong().toString()
    // Plain digits: 0.00036 rather than 3.6E-4
    else -> java.math.BigDecimal(value.toString()).toPlainString()
}

private fun formatDistance(distance: Distance) = formatNumber(distance.inCm) + "cm"

private fun formatAngle(angle: Angle) = formatNumber(angle.inDegrees) + "deg"
