package org.beargineers.platform.input

import kotlinx.coroutines.delay
import org.beargineers.platform.component.RobotRunner
import org.beargineers.platform.config.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class RemoteCommandsTest {
    private val time = TestTimeSource()
    private val runner = RobotRunner(time)
    private val commands = runner.commands

    @BeforeTest
    fun setUp() {
        Config.load("")
    }

    private fun tick(times: Int = 1) {
        repeat(times) {
            time += 10.milliseconds
            runner.tick()
        }
    }

    private fun status(name: String) = commands.offered.single { it.name == name }

    @Test
    fun startingOnlyAsksAndTheLoopRunsIt() {
        var runs = 0
        runner.start { commands.offer("count") { runs++ } }
        tick()
        assertEquals(listOf("count"), commands.offered.map { it.name })

        assertEquals(1, commands.start("count"))
        assertEquals(0, runs, "not on the caller's thread")
        tick(2)
        assertEquals(1, runs)
        assertEquals(1, status("count").started)
        assertEquals(1, status("count").finished)
    }

    @Test
    fun onlyOfferedCommandsStart() {
        assertNull(commands.start("anything"))
    }

    @Test
    fun startingAgainStartsOver() {
        var started = 0
        var completed = 0
        runner.start {
            commands.offer("slow") {
                started++
                delay(1.seconds)
                completed++
            }
        }
        tick()
        commands.start("slow")
        tick(10)
        assertEquals(2, commands.start("slow"))
        tick(10)
        assertEquals(2, started)
        assertEquals(0, completed)
        assertEquals(1, status("slow").finished, "the first run was started over")

        tick(100)
        assertEquals(1, completed)
        assertEquals(2, status("slow").finished)
    }

    @Test
    fun commandsGoAwayWithTheirCoroutine() {
        runner.init { commands.offer("during INIT") {} }
        tick()
        assertEquals(listOf("during INIT"), commands.offered.map { it.name })
        runner.start {}
        tick()
        assertTrue(commands.offered.isEmpty())
        assertNull(commands.start("during INIT"))
    }

    @Test
    fun namesAreUnique() {
        runner.start {
            commands.offer("same") {}
            commands.offer("same") {}
        }
        val thrown = assertFailsWith<IllegalStateException> { tick() }
        assertEquals("A command named 'same' is offered already", thrown.message)
    }
}
