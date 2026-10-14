package nl.beargineers.platform

import nl.beargineers.platform.geometry.Point
import nl.beargineers.platform.geometry.Pose
import nl.beargineers.platform.geometry.RobotOffset
import nl.beargineers.platform.units.Angle
import nl.beargineers.platform.units.Distance
import nl.beargineers.platform.units.Heading
import kotlin.math.abs
import kotlin.test.assertTrue

private const val EPS = 1e-9

fun assertClose(expected: Distance, actual: Distance, what: String = "distance") {
    assertTrue(abs(expected.inCm - actual.inCm) <= EPS, "$what: expected ${expected.inCm}cm but was ${actual.inCm}cm")
}

fun assertClose(expected: Angle, actual: Angle, what: String = "angle") {
    assertTrue(
        abs(expected.inDegrees - actual.inDegrees) <= EPS,
        "$what: expected ${expected.inDegrees}° but was ${actual.inDegrees}°"
    )
}

fun assertClose(expected: Heading, actual: Heading, what: String = "heading") {
    assertTrue(
        abs((expected - actual).inDegrees) <= EPS,
        "$what: expected ${expected.inDegrees}° but was ${actual.inDegrees}°"
    )
}

fun assertClose(expected: Point, actual: Point) {
    assertClose(expected.x, actual.x, "x of $actual")
    assertClose(expected.y, actual.y, "y of $actual")
}

fun assertClose(expected: Pose, actual: Pose) {
    assertClose(expected.point, actual.point)
    assertClose(expected.heading, actual.heading, "heading of $actual")
}

fun assertClose(expected: RobotOffset, actual: RobotOffset) {
    assertClose(expected.forward, actual.forward, "forward of $actual")
    assertClose(expected.right, actual.right, "right of $actual")
    assertClose(expected.turn, actual.turn, "turn of $actual")
}
