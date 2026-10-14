package nl.beargineers.platform.runtime

import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class ExclusiveResourceTest {
    private val loop = TestLoop()
    private val drive = ExclusiveResource("drive")
    private val events = mutableListOf<String>()

    private suspend fun driveForever(action: String) {
        drive.takeOver(action) {
            try {
                events += "$action starts"
                while (true) nextTick()
            } finally {
                events += "$action stops motors"
            }
        }
    }

    @Test
    fun latestActionWins() {
        var firstError: Throwable? = null
        loop.launch {
            try {
                driveForever("to ramp")
            } catch (e: Throwable) {
                firstError = e
                throw e
            }
        }
        loop.tick()
        assertEquals("to ramp", drive.currentAction)

        loop.launch { driveForever("to goal") }
        loop.tick()

        // The old action cleaned up before the new one started driving
        assertEquals(listOf("to ramp starts", "to ramp stops motors", "to goal starts"), events)
        assertEquals("to goal", drive.currentAction)

        val preempted = assertIs<Preempted>(firstError)
        assertEquals("to ramp", preempted.action)
        assertEquals("to goal", preempted.by)
    }

    @Test
    fun preemptedActionStopsItsWholeCoroutine() {
        var continuedAfterDriving = false
        loop.launch {
            driveForever("to ramp")
            continuedAfterDriving = true
        }
        loop.tick()
        loop.launch { driveForever("to goal") }
        loop.tick(3)

        assertFalse(continuedAfterDriving)
    }

    @Test
    fun nestedTakeOverKeepsControl() {
        loop.launch {
            drive.takeOver("path") {
                events += "path starts"
                drive.takeOver("segment") {
                    events += "segment runs"
                    nextTick()
                }
                events += "path continues"
            }
        }

        loop.tick(3)
        assertEquals(listOf("path starts", "segment runs", "path continues"), events)
        assertFalse(drive.isBusy)
    }

    @Test
    fun loopsLaunchedByAnActionKeepItInControl() {
        var holds = 0
        loop.launch { drive.takeOver("hold") { everyTick { holds++ } } }
        loop.tick(3)
        // The block returned right after setting up its loop, but the action lasts as long as the loop
        assertEquals("hold", drive.currentAction)

        loop.launch { driveForever("to goal") }
        loop.tick()
        val holdsWhenTakenOver = holds
        loop.tick(3)
        assertEquals(holdsWhenTakenOver, holds)
        assertEquals("to goal", drive.currentAction)
    }

    @Test
    fun freedWhenActionFinishes() {
        loop.launch {
            drive.takeOver("short") { delay(20.milliseconds) }
        }

        loop.tick()
        assertTrue(drive.isBusy)
        loop.tick(2)
        assertFalse(drive.isBusy)
        assertNull(drive.currentAction)
    }

    @Test
    fun cancelStopsTheCurrentAction() {
        loop.launch { driveForever("to ramp") }
        loop.tick()

        drive.cancel()
        loop.tick()

        assertEquals(listOf("to ramp starts", "to ramp stops motors"), events)
        assertFalse(drive.isBusy)
    }

    @Test
    fun preemptingOneContestantEndsTheRace() {
        var raceError: Throwable? = null
        loop.launch {
            try {
                race({ driveForever("race drive") }, { waitUntil { false } })
            } catch (e: Throwable) {
                raceError = e
                throw e
            }
        }
        loop.tick()

        loop.launch { driveForever("driver override") }
        loop.tick()

        assertIs<Preempted>(raceError)
    }
}
