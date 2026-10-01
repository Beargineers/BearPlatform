package org.beargineers.platform.config

import org.beargineers.platform.geometry.Point
import org.beargineers.platform.units.Distance
import org.beargineers.platform.units.cm
import org.beargineers.platform.units.degrees
import org.beargineers.platform.units.inch
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private val topLevelSpeed by config(1.5)

private class Shooter {
    val kV by config(0.5)
    val spinUp by config(300.milliseconds)
}

private class Camera(override val configScope: String) : ConfigScope {
    val forward by config(10.cm)
}

private class ScopeSetTooLate : ConfigScope {
    val x by config(1)
    override val configScope = "late"
}

private class WithCompanion {
    companion object {
        val gain by config(2.0)
    }
}

private class OldStyleKey {
    val p by config(0.1, key = "SHOOTER_P")
}

private class NeedsGoal {
    val goal by config<Point>()
}

private enum class TurretMode { OFF, FIXED, FOLLOW }

private class Turret {
    val mode by config(TurretMode.OFF)
}

private class DeclaredLater {
    val count by config(1)
}

private class Arm(override val configScope: String, defaultPower: Double) : ConfigScope {
    val power by config(defaultPower)
}

private class ArmWithAngle(override val configScope: String) : ConfigScope {
    val power by config(90.degrees)
}

private class Mount {
    val offset by optionalConfig<Distance>()
    val tilt by optionalConfig(5.degrees)
}

class ConfigTest {
    @BeforeTest
    fun emptyConfig() {
        Config.load("")
    }

    @Test
    fun defaultsWhenNotSet() {
        assertEquals(0.5, Shooter().kV)
        assertEquals(300.milliseconds, Shooter().spinUp)
        assertEquals(1.5, topLevelSpeed)
    }

    @Test
    fun valuesFollowEveryLoad() {
        val shooter = Shooter()
        Config.load("Shooter.kV = 0.7")
        assertEquals(0.7, shooter.kV)
        Config.load("Shooter.kV=0.9\nShooter.spinUp = 1.5s")
        assertEquals(0.9, shooter.kV)
        assertEquals(1500.milliseconds, shooter.spinUp)
        Config.load("")
        assertEquals(0.5, shooter.kV)
    }

    @Test
    fun keyNames() {
        topLevelSpeed
        WithCompanion.gain
        Shooter()
        OldStyleKey()
        Camera("left")

        val keys = Config.declarations().map { it.key }
        for (key in listOf("topLevelSpeed", "WithCompanion.gain", "Shooter.kV", "SHOOTER_P", "left.forward")) {
            assertTrue(key in keys, "$key should be declared, have $keys")
        }
    }

    @Test
    fun scopesSeparateInstances() {
        val left = Camera("left")
        val right = Camera("right")
        Config.load("left.forward = 12\nright.forward = 5in")
        assertEquals(12.cm, left.forward)
        assertEquals(12.7, right.forward.inCm, 1e-9)
    }

    @Test
    fun sameKeySharesTheSetting() {
        val first = Shooter()
        val second = Shooter()
        Config.load("Shooter.kV = 0.8")
        assertEquals(0.8, first.kV)
        assertEquals(0.8, second.kV)
    }

    @Test
    fun sameKeyWithDifferentDefaultFails() {
        Arm("lift", 0.5)
        Arm("lift", 0.5)
        val thrown = assertFailsWith<IllegalStateException> { Arm("lift", 0.7) }
        assertEquals(
            "Config key lift.power is declared twice with different defaults 0.5 and 0.7. Give one of them another key or ConfigScope.",
            thrown.message
        )
    }

    @Test
    fun anotherOpModesRobotMayHaveOtherDefaults() {
        Arm("elbow", 0.5)
        Config.newRound()
        val next = Arm("elbow", 0.7)
        Config.load("")
        assertEquals(0.7, next.power)
        assertEquals("0.7", Config.declarations().single { it.key == "elbow.power" }.defaultText)
    }

    @Test
    fun sameKeyWithDifferentTypeFails() {
        Arm("wrist", 0.5)
        val thrown = assertFailsWith<IllegalStateException> { ArmWithAngle("wrist") }
        assertTrue("types number and angle" in thrown.message!!, thrown.message)
    }

    @Test
    fun scopeMustBeSetBeforeSettings() {
        val thrown = assertFailsWith<IllegalStateException> { ScopeSetTooLate() }
        assertTrue("configScope" in thrown.message!!, thrown.message)
    }

    @Test
    fun requiredSetting() {
        val needsGoal = NeedsGoal()
        val thrown = assertFailsWith<ConfigException> { needsGoal.goal }
        assertTrue("NeedsGoal.goal" in thrown.message!!, thrown.message)

        Config.load("NeedsGoal.goal = 10, -20")
        assertEquals(Point(10.cm, (-20).cm), needsGoal.goal)
    }

    @Test
    fun optionalSettings() {
        val mount = Mount()
        assertNull(mount.offset)
        assertEquals(5.degrees, mount.tilt)
        assertTrue(Config.problems().none { "Mount" in it }, "an optional setting left out is no problem")

        Config.load("Mount.offset = 3in\nMount.tilt = 7")
        assertEquals(3.inch, mount.offset)
        assertEquals(7.degrees, mount.tilt)

        val offset = Config.declarations().single { it.key == "Mount.offset" }
        assertTrue(offset.optional)
        assertFalse(offset.isRequired)
        assertNull(offset.defaultText)
    }

    @Test
    fun loadRejectsBadValuesAndKeepsOldOnes() {
        val shooter = Shooter()
        Config.load("Shooter.kV = 0.7")

        val thrown = assertFailsWith<ConfigException> { Config.load("Shooter.kV = fast\nShooter.spinUp = 300") }
        assertEquals(2, thrown.problems.size, thrown.message)
        assertTrue(thrown.problems.any { it.startsWith("Shooter.kV = fast: expected a number") }, thrown.message)
        assertEquals(0.7, shooter.kV)
    }

    @Test
    fun explainsCommentAfterValue() {
        Shooter()
        val thrown = assertFailsWith<ConfigException> { Config.load("Shooter.kV = 0.7  # tuned at FEUP") }
        assertTrue(thrown.message!!.endsWith("(comments must be on their own line)"), thrown.message)
    }

    @Test
    fun badValueForSettingDeclaredAfterLoad() {
        Config.load("DeclaredLater.count = many")
        val declaredLater = DeclaredLater()

        val thrown = assertFailsWith<ConfigException> { declaredLater.count }
        assertEquals("DeclaredLater.count = many: expected a whole number", thrown.message)
        assertTrue(Config.problems().any { it.startsWith("DeclaredLater.count = many") })
    }

    @Test
    fun problemsPointOutTypos() {
        Shooter()
        Config.load("Shooter.kv = 1\nkV = 2\nShooter.spnUp = 1s\nShooter.somethingElse = 3")
        val problems = Config.problems()
        assertTrue("Shooter.kv is set but nothing uses it. Did you mean Shooter.kV?" in problems, "$problems")
        assertTrue("kV is set but nothing uses it. Did you mean Shooter.kV?" in problems, "$problems")
        assertTrue("Shooter.spnUp is set but nothing uses it. Did you mean Shooter.spinUp?" in problems, "$problems")
        assertTrue("Shooter.somethingElse is set but nothing uses it" in problems, "$problems")
    }

    @Test
    fun enumsByNameOrUniqueBeginning() {
        val turret = Turret()
        Config.load("Turret.mode = follow")
        assertEquals(TurretMode.FOLLOW, turret.mode)
        Config.load("Turret.mode = FI")
        assertEquals(TurretMode.FIXED, turret.mode)

        val thrown = assertFailsWith<ConfigException> { Config.load("Turret.mode = F") }
        assertEquals("Turret.mode = F: could mean any of FIXED, FOLLOW", thrown.message)
    }

    @Test
    fun declarationsDescribeDefaults() {
        Camera("front")
        val declaration = Config.declarations().single { it.key == "front.forward" }
        assertEquals("10cm", declaration.defaultText)
        assertEquals("distance", declaration.type.name)
        assertEquals(Distance::class.java.simpleName, "Distance")
    }
}
