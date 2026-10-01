package org.beargineers.platform.config

import org.beargineers.platform.assertClose
import org.beargineers.platform.control.Feedforward
import org.beargineers.platform.control.PidGains
import org.beargineers.platform.geometry.Field
import org.beargineers.platform.geometry.FieldDirection
import org.beargineers.platform.geometry.FieldSymmetry
import org.beargineers.platform.geometry.Point
import org.beargineers.platform.geometry.Pose
import org.beargineers.platform.units.Angle
import org.beargineers.platform.units.AngularVelocity
import org.beargineers.platform.units.Distance
import org.beargineers.platform.units.Heading
import org.beargineers.platform.units.LinearVelocity
import org.beargineers.platform.units.cm
import org.beargineers.platform.units.degrees
import org.beargineers.platform.units.inch
import org.beargineers.platform.units.radians
import org.beargineers.platform.units.tiles
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ConfigTypeTest {
    private val decodeField = Field(xAxis = FieldDirection.TOWARD_AUDIENCE, symmetry = FieldSymmetry.FLIP_Y)

    private fun <T : Any> parse(kClass: KClass<T>, text: String): T = Config.typeOf(kClass).parse(text)

    @AfterTest
    fun noField() {
        Config.field = null
    }

    @Test
    fun distances() {
        assertClose(30.cm, parse(Distance::class, "30"))
        assertClose(30.cm, parse(Distance::class, " 30cm "))
        assertClose(12.inch, parse(Distance::class, "12 in"))
        assertClose(12.inch, parse(Distance::class, "12\""))
        assertClose(24.inch, parse(Distance::class, "2ft"))
        assertClose(1.5.tiles, parse(Distance::class, "1.5 tiles"))
        assertClose(30.cm, parse(Distance::class, "0.3m"))
        assertClose(15.cm, parse(Distance::class, "150mm"))
        assertClose((-4).cm, parse(Distance::class, "-4"))

        assertFailsWith<IllegalArgumentException> { parse(Distance::class, "far") }
        val thrown = assertFailsWith<IllegalArgumentException> { parse(Distance::class, "12 parsecs") }
        assertTrue("parsecs" in thrown.message!!)
    }

    @Test
    fun anglesAndHeadings() {
        assertClose(90.degrees, parse(Angle::class, "90"))
        assertClose(90.degrees, parse(Angle::class, "90deg"))
        assertClose(90.degrees, parse(Angle::class, "90°"))
        assertClose(135.degrees, parse(Angle::class, "+135"))
        assertClose(1.5.radians, parse(Angle::class, "1.5 rad"))
        assertClose(90.degrees, parse(Angle::class, "0.25rot"))
        assertEquals(-90.0, parse(Heading::class, "270").inDegrees, 1e-9)
    }

    @Test
    fun speeds() {
        assertEquals(30.0, parse(LinearVelocity::class, "30").inCmPerSecond, 1e-9)
        assertEquals(30.0, parse(LinearVelocity::class, "30cm/s").inCmPerSecond, 1e-9)
        assertEquals(12.0, parse(LinearVelocity::class, "12 in / s").inInchesPerSecond, 1e-9)
        assertEquals(150.0, parse(LinearVelocity::class, "1.5m/s").inCmPerSecond, 1e-9)
        val thrown = assertFailsWith<IllegalArgumentException> { parse(LinearVelocity::class, "30 cm") }
        assertTrue("30cm/s" in thrown.message!!, thrown.message)

        assertEquals(90.0, parse(AngularVelocity::class, "90").inDegreesPerSecond, 1e-9)
        assertEquals(90.0, parse(AngularVelocity::class, "90°/s").inDegreesPerSecond, 1e-9)
        assertEquals(60.0, parse(AngularVelocity::class, "60 rpm").inRpm, 1e-9)
        assertEquals(1.0, parse(AngularVelocity::class, "1rad/s").inRadiansPerSecond, 1e-9)
    }

    @Test
    fun durationsNeedUnits() {
        assertEquals(300.milliseconds, parse(Duration::class, "300ms"))
        assertEquals(1.5.seconds, parse(Duration::class, "1.5s"))
        assertFailsWith<IllegalArgumentException> { parse(Duration::class, "500") }
    }

    @Test
    fun booleans() {
        for (text in listOf("true", "T", "yes", "y", "on", "1")) assertEquals(true, parse(Boolean::class, text), text)
        for (text in listOf("false", "F", "no", "n", "off", "0")) assertEquals(false, parse(Boolean::class, text), text)
        assertFailsWith<IllegalArgumentException> { parse(Boolean::class, "maybe") }
    }

    @Test
    fun pointsAndPoses() {
        assertClose(Point(10.cm, (-20).cm), parse(Point::class, "10, -20"))
        assertClose(Point(12.inch, 1.tiles), parse(Point::class, "12in, 1tile"))
        assertClose(Pose(10.cm, 20.cm, 90.degrees), parse(Pose::class, "10, 20, 90"))
        assertFailsWith<IllegalArgumentException> { parse(Pose::class, "10, 20") }
    }

    @Test
    fun tileCodesUseTheSeasonField() {
        val thrown = assertFailsWith<IllegalArgumentException> { parse(Point::class, "F6TR") }
        assertTrue("Config.field" in thrown.message!!)

        Config.field = decodeField
        assertClose(decodeField.tile("F6TR"), parse(Point::class, "F6TR"))
        // The format used in DECODE season config files
        assertClose(decodeField.tile("F6BL").withHeading(135.degrees), parse(Pose::class, "F6BL:+135"))
        assertFailsWith<IllegalArgumentException> { parse(Pose::class, "F6BL") }
    }

    @Test
    fun gainsInOrderOrByName() {
        assertEquals(PidGains(0.02, 0.0, 0.001), parse(PidGains::class, "0.02, 0, 0.001"))
        assertEquals(PidGains(0.02), parse(PidGains::class, "0.02"))
        assertEquals(PidGains(0.02, 0.0, 0.001), parse(PidGains::class, "d=0.001, P = 0.02"))
        assertEquals(Feedforward(0.04, 0.0085), parse(Feedforward::class, "kS=0.04, kV=0.0085"))

        assertFailsWith<IllegalArgumentException> { parse(PidGains::class, "0.02, 0, 0.001, 0.3") }
        assertFailsWith<IllegalArgumentException> { parse(PidGains::class, "p=0.02, 0.001") }
        val thrown = assertFailsWith<IllegalArgumentException> { parse(PidGains::class, "p=0.02, f=1") }
        assertTrue("'f'" in thrown.message!!, thrown.message)
    }

    @Test
    fun formattedValuesReadBack() {
        fun <T : Any> roundTrip(kClass: KClass<T>, value: T): T {
            val type = Config.typeOf(kClass)
            return type.parse(type.format(value))
        }

        assertEquals(42, roundTrip(Int::class, 42))
        assertEquals(0.25, roundTrip(Double::class, 0.25))
        assertEquals(true, roundTrip(Boolean::class, true))
        assertEquals(1500.milliseconds, roundTrip(Duration::class, 1500.milliseconds))
        assertClose(30.48.cm, roundTrip(Distance::class, 12.inch))
        assertClose((-45).degrees, roundTrip(Angle::class, (-45).degrees))
        assertEquals(-90.0, roundTrip(Heading::class, 270.degrees.toHeading()).inDegrees, 1e-9)
        assertClose(Point(1.cm, 2.5.cm), roundTrip(Point::class, Point(1.cm, 2.5.cm)))
        assertClose(Pose(1.cm, 2.cm, 30.degrees), roundTrip(Pose::class, Pose(1.cm, 2.cm, 30.degrees)))
        assertEquals(PidGains(0.00036, 0.1, 2.0), roundTrip(PidGains::class, PidGains(0.00036, 0.1, 2.0)))
        assertEquals(Feedforward(0.04, 0.0085, 0.0), roundTrip(Feedforward::class, Feedforward(0.04, 0.0085)))
        assertEquals("p=0.02, i=0, d=0.001", Config.typeOf(PidGains::class).format(PidGains(0.02, d = 0.001)))
    }

    @Test
    fun formatIsReadable() {
        assertEquals("30cm", Config.typeOf(Distance::class).format(30.cm))
        assertEquals("0.00036", Config.typeOf(Double::class).format(0.00036))
        assertEquals("2", Config.typeOf(Double::class).format(2.0))
        assertEquals("10cm, 20cm, 90deg", Config.typeOf(Pose::class).format(Pose(10.cm, 20.cm, 90.degrees)))
        assertEquals("300ms", Config.typeOf(Duration::class).format(300.milliseconds))
    }

    @Test
    fun unknownTypeExplainsHowToTeachIt() {
        class Gains(val p: Double)

        val thrown = assertFailsWith<IllegalArgumentException> { Config.typeOf(Gains::class) }
        assertTrue("registerType" in thrown.message!!)

        Config.registerType(ConfigType("gains", { Gains(it.toDouble()) }, { it.p.toString() }))
        assertEquals(0.3, parse(Gains::class, "0.3").p)
    }
}
