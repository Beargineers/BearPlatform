package nl.beargineers.platform.ftc

import nl.beargineers.platform.component.Robot
import nl.beargineers.platform.component.RobotHost
import nl.beargineers.platform.config.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

private abstract class SeasonRobot(host: RobotHost) : Robot(host)
private class Beta(host: RobotHost) : SeasonRobot(host)
private class Gamma(host: RobotHost) : SeasonRobot(host)

private object SeasonRobots : RobotChoice<SeasonRobot>(
    "betaDecode" to ::Beta,
    "betaPractice" to ::Beta,
    "gammaDecode" to ::Gamma,
)

class RobotChoiceTest {
    @BeforeTest
    fun emptyConfig() {
        Config.load("")
    }

    private fun on(configuration: String?) = TestFtcHost().also { it.configuration = configuration }

    @Test
    fun theRobotConfigurationPicksTheRobot() {
        assertIs<Beta>(SeasonRobots(on("betaDecode")))
        assertIs<Beta>(SeasonRobots(on("betaPractice")))
        assertIs<Gamma>(SeasonRobots(on("gammaDecode")))
        assertEquals(setOf("betaDecode", "betaPractice", "gammaDecode"), SeasonRobots.configurations)
    }

    @Test
    fun unknownConfigurationsSayWhatTheAppKnows() {
        val thrown = assertFailsWith<IllegalStateException> { SeasonRobots(on("deltaDecode")) }
        assertEquals(
            "This app has no robot for the robot configuration 'deltaDecode'. SeasonRobots knows betaDecode, betaPractice, gammaDecode",
            thrown.message,
        )
        assertFailsWith<IllegalStateException> { SeasonRobots(on(null)) }
    }

    @Test
    fun opModesOfOneRobotSayWhenTheyreOnAnother() {
        assertIs<Gamma>(SeasonRobots.only<Gamma>()(on("gammaDecode")))
        val thrown = assertFailsWith<IllegalStateException> { SeasonRobots.only<Gamma>()(on("betaDecode")) }
        assertEquals(
            "This opmode is for Gamma, and the robot configuration 'betaDecode' is Beta's. Pick Gamma's configuration on the Driver Station",
            thrown.message,
        )
    }

    @Test
    fun aConfigurationNamesOneRobot() {
        assertFailsWith<IllegalArgumentException> { RobotChoice<SeasonRobot>("betaDecode" to ::Beta, "betaDecode" to ::Gamma) }
    }
}
