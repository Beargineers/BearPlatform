package nl.beargineers.platform.geometry

import nl.beargineers.platform.units.Distance
import nl.beargineers.platform.units.Heading
import nl.beargineers.platform.units.abs
import nl.beargineers.platform.units.degrees
import nl.beargineers.platform.units.inch
import nl.beargineers.platform.units.radians

enum class Alliance { RED, BLUE }

/** A direction on the field as seen from the audience */
enum class FieldDirection(internal val right: Int, internal val away: Int) {
    AWAY_FROM_AUDIENCE(0, 1),
    TOWARD_AUDIENCE(0, -1),
    AUDIENCE_LEFT(-1, 0),
    AUDIENCE_RIGHT(1, 0);

    internal val opposite: FieldDirection get() = entries.first { it.right == -right && it.away == -away }
}

/** How one alliance's side of the field maps onto the other alliance's side */
enum class FieldSymmetry {
    /** Mirror image across the x axis: (x, y) becomes (x, -y). DECODE is laid out this way. */
    FLIP_Y {
        override fun map(pose: Pose) = Pose(pose.x, -pose.y, -pose.heading.toAngle())
    },

    /** Mirror image across the y axis: (x, y) becomes (-x, y) */
    FLIP_X {
        override fun map(pose: Pose) = Pose(-pose.x, pose.y, 180.degrees - pose.heading.toAngle())
    },

    /** Half a turn around the field center: (x, y) becomes (-x, -y) */
    ROTATE_180 {
        override fun map(pose: Pose) = Pose(-pose.x, -pose.y, pose.heading + 180.degrees)
    };

    /** The pose on the other alliance's side that corresponds to [pose] */
    abstract fun map(pose: Pose): Pose

    fun map(point: Point): Point = map(point.withHeading(Heading.ZERO)).point
}

/**
 * Season-specific description of the field. A season project creates one of these, for example
 * `val DecodeField = Field(xAxis = FieldDirection.TOWARD_AUDIENCE, symmetry = FieldSymmetry.FLIP_Y,
 * redStation = FieldDirection.AUDIENCE_LEFT)`.
 *
 * Field coordinates follow the FTC field coordinate system: the origin is the center of the field, +y points 90°
 * counterclockwise from +x, and heading 0 faces +x. Which physical direction +x points to changes between seasons;
 * [xAxis] says which one it is.
 *
 * Tiles are named the way the audience sees the field: columns A..F from left to right, rows 1..6 from the
 * audience side to the far side. See [tile].
 */
class Field(
    /** Where +x points, as seen from the audience */
    val xAxis: FieldDirection,
    /** How one alliance's side of the field maps onto the other's */
    val symmetry: FieldSymmetry,
    /** The alliance that poses written in code refer to. [forAlliance] converts them for the other alliance. */
    val referenceAlliance: Alliance = Alliance.RED,
    val tileSize: Distance = 24.inch,
    /** The wall the red drivers stand at, as seen from the audience; field-centric driving needs it */
    val redStation: FieldDirection? = null,
    /** The wall the blue drivers stand at; by default, the one across from red */
    val blueStation: FieldDirection? = redStation?.opposite,
) {
    /** Distance from the field center to each wall */
    val halfSize: Distance get() = tileSize * 3

    /**
     * A point on a tile: `tile("D4")` is the center of D4 and `tile("F6TR")` is the top right corner of F6.
     *
     * The optional suffix picks a spot on the tile as the audience sees it: Top, Bottom, Left, Right or Center.
     * Use one or two letters in any order: "TL" and "LT" are both the top left corner, "CL" and "L" are both
     * the middle of the left edge.
     */
    fun tile(code: String): Point {
        val text = code.trim().uppercase()
        require(text.length in 2..4) { "Tile code must look like D4 or F6TR, was '$code'" }

        val column = text[0] - 'A'
        val row = text[1] - '1'
        require(column in 0..5 && row in 0..5) { "Tile must be A1..F6, was '$code'" }

        val suffix = text.substring(2)
        require(suffix.count { it == 'T' || it == 'B' } <= 1 && suffix.count { it == 'L' || it == 'R' } <= 1) {
            "Tile position '$suffix' in '$code' names the same edge direction twice"
        }

        // In tiles from the field center, as seen from the audience
        var right = column - 2.5
        var away = row - 2.5
        for (c in suffix) {
            when (c) {
                'L' -> right -= 0.5
                'R' -> right += 0.5
                'T' -> away += 0.5
                'B' -> away -= 0.5
                'C' -> {}
                else -> throw IllegalArgumentException("Unknown tile position '$c' in '$code'. Use T, B, L, R or C")
            }
        }

        val (x, y) = toField(right, away)
        return Point(tileSize * x, tileSize * y)
    }

    /**
     * The way [alliance]'s drivers look: away from their wall. Field-centric driving drives this way when a stick is
     * pushed forward.
     */
    fun driversFace(alliance: Alliance): Heading {
        val station = (if (alliance == Alliance.RED) redStation else blueStation)
            ?: error("Field-centric driving needs to know where the drivers stand: set the Field's redStation")
        val (x, y) = toField(-station.right.toDouble(), -station.away.toDouble())
        return kotlin.math.atan2(y, x).radians.toHeading()
    }

    /** Field x and y of a direction given as the audience sees it: [right] and [away] */
    private fun toField(right: Double, away: Double): Pair<Double, Double> {
        // +x is xAxis; +y is xAxis turned 90° counterclockwise
        val x = right * xAxis.right + away * xAxis.away
        val y = -right * xAxis.away + away * xAxis.right
        return x to y
    }

    /** [pose] as written for the [referenceAlliance], converted for [alliance] */
    fun forAlliance(pose: Pose, alliance: Alliance): Pose =
        if (alliance == referenceAlliance) pose else symmetry.map(pose)

    /** [point] as written for the [referenceAlliance], converted for [alliance] */
    fun forAlliance(point: Point, alliance: Alliance): Point =
        if (alliance == referenceAlliance) point else symmetry.map(point)

    fun contains(point: Point): Boolean = abs(point.x) <= halfSize && abs(point.y) <= halfSize

    /** The closest point to [point] that is inside the field walls */
    fun clamp(point: Point): Point =
        Point(point.x.coerceIn(-halfSize, halfSize), point.y.coerceIn(-halfSize, halfSize))

    fun clamp(pose: Pose): Pose = Pose(clamp(pose.point), pose.heading)
}
