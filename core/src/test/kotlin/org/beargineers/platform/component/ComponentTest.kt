package org.beargineers.platform.component

import kotlinx.coroutines.awaitCancellation
import org.beargineers.platform.config.Config
import org.beargineers.platform.config.config
import org.beargineers.platform.runtime.Preempted
import org.beargineers.platform.runtime.everyTick
import org.beargineers.platform.runtime.nextTick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource

private open class Recorder(parent: Component, name: String? = null) : Component(parent, name) {
    private val events get() = (robot as TestRobot).events

    override fun init() {
        events += "init $path"
    }

    override fun update() {
        events += "update $path"
    }

    override fun stop() {
        events += "stop $path"
    }
}

private class Latch(parent: Component) : Recorder(parent) {
    val open by config(0.3)
}

private class Shooter(parent: Component) : Recorder(parent) {
    val latch = Latch(this)
}

private class TestRobot(host: RobotHost) : Robot(host) {
    val events = mutableListOf<String>()
    val maxSpeed by config(1.0)

    val sensors = Recorder(this, "sensors")
    val shooter = Shooter(this)

    override fun init() {
        events += "init robot"
    }

    override fun update() {
        events += "update robot"
    }

    override fun stop() {
        events += "stop robot"
    }
}

private class Flywheel(parent: Component, name: String? = null) : Component(parent, name)

private class TwoFlywheels(host: RobotHost, secondName: String?) : Robot(host) {
    val left = Flywheel(this, "left")
    val other = Flywheel(this, secondName)
    val third = Flywheel(this)
}

class ComponentTest {
    private val runner = RobotRunner(TestTimeSource())

    @Test
    fun pathsAndConfigKeys() {
        val robot = TestRobot(runner)
        assertEquals("shooter", robot.shooter.path)
        assertEquals("shooter.latch", robot.shooter.latch.path)
        assertEquals(robot, robot.shooter.latch.robot)
        assertEquals(listOf(robot.sensors, robot.shooter), robot.children)

        val keys = Config.declarations().map { it.key }
        assertTrue("shooter.latch.open" in keys, "$keys")
        assertTrue("robot.maxSpeed" in keys, "$keys")
    }

    @Test
    fun partNamesMustBeUnique() {
        val thrown = assertFailsWith<IllegalStateException> { TwoFlywheels(runner, "left") }
        assertEquals(
            "robot already has a part named 'left'. " +
                "Give them different names, like Flywheel(this, \"left\") and Flywheel(this, \"right\")",
            thrown.message
        )
    }

    @Test
    fun namesDefaultToClassName() {
        val robot = TwoFlywheels(runner, "right")
        assertEquals(listOf("left", "right", "flywheel"), robot.children.map { it.name })
    }

    @Test
    fun namesMustFitConfigKeys() {
        val robot = TestRobot(runner)
        assertFailsWith<IllegalArgumentException> { Recorder(robot, "left arm") }
    }

    @Test
    fun oneRobotPerOpMode() {
        TestRobot(runner)
        assertFailsWith<IllegalStateException> { TestRobot(runner) }
    }

    @Test
    fun lifecycleOrder() {
        val robot = TestRobot(runner)
        runner.init()
        runner.tick()
        runner.stop()

        assertEquals(
            listOf(
                "init robot", "init sensors", "init shooter", "init shooter.latch",
                "update robot", "update sensors", "update shooter", "update shooter.latch",
                "stop shooter.latch", "stop shooter", "stop sensors", "stop robot",
            ),
            robot.events
        )
    }

    @Test
    fun componentsUpdateBeforeCoroutines() {
        val robot = TestRobot(runner)
        runner.init()
        runner.start { everyTick { robot.events += "coroutine" } }
        robot.events.clear()

        runner.tick()
        assertEquals("update shooter.latch", robot.events[robot.events.size - 2])
        assertEquals("coroutine", robot.events.last())
    }

    @Test
    fun initCoroutineIsCancelledAtStart() {
        TestRobot(runner)
        val phases = mutableListOf<String>()
        runner.init {
            try {
                awaitCancellation()
            } finally {
                phases += "init cancelled"
            }
        }
        runner.tick()
        runner.start { phases += "main started" }
        runner.tick()

        assertEquals(listOf("init cancelled", "main started"), phases)
    }

    @Test
    fun componentCreatedWhileRunningIsInitializedBeforeItsFirstUpdate() {
        val robot = TestRobot(runner)
        runner.init()
        runner.start {
            nextTick()
            Recorder(robot, "late")
        }
        runner.tick()
        runner.tick() // the coroutine creates the component after this tick's updates
        assertFalse(robot.events.any { "late" in it })

        runner.tick()
        val lateEvents = robot.events.filter { "late" in it }
        assertEquals(listOf("init late", "update late"), lateEvents)
    }

    @Test
    fun componentsThatNeverStartedAreNotStopped() {
        val robot = TestRobot(runner)
        runner.stop()
        assertEquals(emptyList(), robot.events)
    }

    @Test
    fun stopReachesComponentsWhenCleanupFails() {
        val robot = TestRobot(runner)
        runner.init()
        runner.start {
            try {
                awaitCancellation()
            } finally {
                error("cleanup failed")
            }
        }
        runner.tick()

        val thrown = assertFailsWith<IllegalStateException> { runner.stop() }
        assertEquals("cleanup failed", thrown.message)
        assertEquals("stop robot", robot.events.last())
    }

    @Test
    fun updateFailureStopsTheLoop() {
        class Broken(parent: Component) : Component(parent) {
            override fun update() = error("sensor unplugged")
        }

        val robot = TestRobot(runner)
        Broken(robot)
        runner.init()
        val thrown = assertFailsWith<IllegalStateException> { runner.tick() }
        assertEquals("sensor unplugged", thrown.message)
    }

    @Test
    fun actionsTakeOverComponents() {
        val robot = TestRobot(runner)
        var firstError: Throwable? = null
        runner.init()
        runner.start {
            try {
                robot.shooter.takeOver("spin up") { awaitCancellation() }
            } catch (e: Throwable) {
                firstError = e
                throw e
            }
        }
        runner.tick()
        assertEquals("spin up", robot.shooter.currentAction)

        runner.runtime.launch("shoot") { robot.shooter.takeOver("shoot") { awaitCancellation() } }
        runner.tick()
        assertEquals("shoot", robot.shooter.currentAction)
        assertEquals("shooter", assertIs<Preempted>(firstError).resource)

        robot.shooter.cancelAction()
        runner.tick()
        assertFalse(robot.shooter.isBusy)
    }
}
