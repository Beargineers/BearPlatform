package org.beargineers.platform.ftc

import org.beargineers.platform.config.Config
import org.beargineers.platform.config.ConfigException
import org.beargineers.platform.config.config
import org.beargineers.platform.ftc.SettingsPage.Outcome
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class Arm {
    val power by config(0.5)
}

class ConfigFilesTest {
    private val directory = Files.createTempDirectory("bear-config").toFile()
    private val bundled = "bundled = 1"

    private fun files(build: String) = ConfigFiles(directory, build) { bundled }

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun bundledConfigUntilEdited() {
        assertFalse(files("1").hasEdits)
        assertEquals(bundled, files("1").read())
    }

    @Test
    fun editsLastUntilTheNextInstall() {
        files("1").save("edited = 2")
        assertTrue(files("1").hasEdits)
        assertEquals("edited = 2", files("1").read())

        assertFalse(files("2").hasEdits)
        assertEquals(bundled, files("2").read())
    }

    @Test
    fun savingForANewBuildForgetsOldEdits() {
        files("1").save("a = 1")
        files("2").save("a = 2")
        assertEquals(listOf("config-2.properties"), directory.list()!!.toList())
    }

    @Test
    fun discardingEditsGoesBackToBundled() {
        files("1").save("a = 1")
        files("1").discardEdits()
        assertEquals(bundled, files("1").read())
    }
}

class RobotConfigTest {
    private val directory = Files.createTempDirectory("bear-config").toFile()
    private var bundled = ""
    private val files = ConfigFiles(directory, "1") { bundled }
    private val config = RobotConfig(files)

    @BeforeTest
    fun emptyConfig() {
        Config.load("")
    }

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun textComesFromFilesUntilAnOpModeLoadsIt() {
        bundled = "Arm.power = 0.7"
        assertEquals("Arm.power = 0.7", config.text)
        assertEquals("", Config.text)
    }

    @Test
    fun opModeLoadsTheConfig() {
        val arm = Arm()
        bundled = "Arm.power = 0.7"
        config.loadForOpMode()
        assertEquals(0.7, arm.power)
    }

    @Test
    fun opModeFailsOnValuesThatCantBeRead() {
        Arm()
        bundled = "Arm.power = strong"
        val thrown = assertFailsWith<ConfigException> { config.loadForOpMode() }
        assertEquals(listOf("Arm.power = strong: expected a number"), thrown.problems)
    }

    @Test
    fun editTakesEffectAndIsKept() {
        val arm = Arm()
        config.loadForOpMode()
        config.edit("Arm.power = 0.9")

        assertEquals(0.9, arm.power)
        assertTrue(config.isEdited)
        assertEquals("Arm.power = 0.9", RobotConfig(files).text, "a restarted app finds the edit")
    }

    @Test
    fun rejectedEditChangesNothing() {
        val arm = Arm()
        config.loadForOpMode()
        assertFailsWith<ConfigException> { config.edit("Arm.power = strong") }

        assertEquals(0.5, arm.power)
        assertFalse(config.isEdited)
    }

    @Test
    fun discardingEditsGoesBackToBundled() {
        val arm = Arm()
        config.edit("Arm.power = 0.9")
        config.discardEdits()
        assertEquals(0.5, arm.power)
        assertFalse(config.isEdited)
    }

    @Test
    fun theActiveRobotConfigurationsSectionsApply() {
        val arm = Arm()
        var active: String? = "betaDecode"
        val config = RobotConfig(files) { active }
        bundled = "Arm.power = 0.6\n[betaDecode]\nArm.power = 0.7\n[gammaDecode]\nArm.power = 0.8"
        config.loadForOpMode()
        assertEquals(0.7, arm.power)
        active = "gammaDecode"
        config.edit(bundled.replace("0.8", "0.85"))
        assertEquals(0.85, arm.power, "edits too")
        active = null
        config.loadForOpMode()
        assertEquals(0.6, arm.power)
    }
}

class SettingsPageTest {
    private val directory = Files.createTempDirectory("bear-config").toFile()
    private var bundled = ""
    private val config = RobotConfig(ConfigFiles(directory, "1") { bundled })

    @BeforeTest
    fun emptyConfig() {
        Config.load("")
    }

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun showsTheConfigSafely() {
        bundled = "note = <b>&"
        val html = SettingsPage.html(config)
        assertTrue("note = &lt;b&gt;&amp;</textarea>" in html, html)
        assertTrue("bundled with the app" in html)
        assertTrue(html.startsWith("<!DOCTYPE html>\n<html"), html.take(80))
    }

    @Test
    fun showsTheConfigExactly() {
        bundled = "  |indented = 1\n\n# comment\n"
        assertTrue(">  |indented = 1\n\n# comment\n</textarea>" in SettingsPage.html(config))
    }

    @Test
    fun listsSettingsAndProblems() {
        Arm()
        bundled = "Arm.Power = 1"
        config.loadForOpMode()
        val html = SettingsPage.html(config)
        assertTrue("<td><code>Arm.power</code></td><td>number</td><td><code>0.5</code></td>" in html, html)
        assertTrue("Arm.Power is set but nothing uses it. Did you mean Arm.power?" in html, html)
    }

    @Test
    fun showsTheRobotConfigurationAndWhereValuesComeFrom() {
        Arm()
        val config = RobotConfig(ConfigFiles(directory, "1") { bundled }) { "betaDecode" }
        bundled = "Arm.power = 0.6\n[betaDecode]\nArm.power = 0.7"
        config.loadForOpMode()
        val html = SettingsPage.html(config)
        assertTrue("Robot configuration <b>betaDecode</b>" in html, html)
        assertTrue("<code>0.7</code> <span class=\"muted\">[betaDecode]</span>" in html, html)
    }

    @Test
    fun rejectedEditKeepsThePostedText() {
        val html = SettingsPage.html(
            config,
            Outcome.Rejected(listOf("Arm.power = strong: expected a number")),
            text = "Arm.power = strong",
        )
        assertTrue("Not saved" in html)
        assertTrue("<li>Arm.power = strong: expected a number</li>" in html)
        assertTrue(">Arm.power = strong</textarea>" in html)
        assertTrue("<span id=\"unsaved\" class=\"warn\">" in html, "the editor says the text isn't saved")
    }

    @Test
    fun showsHowLongAgoTheAppWasInstalled() {
        val installed = RobotConfig(ConfigFiles(directory, "1") { bundled }, System.currentTimeMillis() - 5 * 60_000)
        assertTrue("App installed 5 min ago." in SettingsPage.html(installed))

        val now = 1_000_000_000L
        assertEquals("less than a minute ago", SettingsPage.installedAgo(now - 30_000, now))
        assertEquals("3 h ago", SettingsPage.installedAgo(now - 3 * 3_600_000, now))
        assertEquals("4 days ago", SettingsPage.installedAgo(now - 4 * 86_400_000L, now))
    }

    @Test
    fun resetOnlyWhenEdited() {
        assertFalse(SettingsPage.RESET_PATH in SettingsPage.html(config))
        config.edit("a = 1")
        val html = SettingsPage.html(config, Outcome.Saved)
        assertTrue(SettingsPage.RESET_PATH in html)
        assertTrue("edited on the robot" in html)
    }
}
