package nl.beargineers.platform.telemetry

import java.util.Locale
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Measures how long loops take, from the start of one loop to the start of the next. Graphs every loop's time as
 * `loop ms`: at most one sample per 10 ms like any graph, but a loop longer than that always makes it in. [report]
 * shows the average and the longest since the last report.
 */
class LoopTimer(private val telemetry: RobotTelemetry) {
    private var lastStart: Duration? = null
    private var loops = 0
    private var total = Duration.ZERO
    private var longest = Duration.ZERO

    /** Call at the start of every loop */
    fun loopStarted(now: Duration) {
        lastStart?.let { last ->
            val loop = now - last
            loops++
            total += loop
            if (loop > longest) longest = loop
            telemetry.graph("loop ms", loop.toDouble(DurationUnit.MILLISECONDS))
        }
        lastStart = now
    }

    /** Shows the loops since the last report, then starts counting over. Nothing before two loops. */
    fun report() {
        if (loops == 0) return
        val average = total.toDouble(DurationUnit.MILLISECONDS) / loops
        val longestMs = longest.toDouble(DurationUnit.MILLISECONDS)
        telemetry.show("Loop") { String.format(Locale.US, "%.1f ms average, %.1f ms longest", average, longestMs) }
        loops = 0
        total = Duration.ZERO
        longest = Duration.ZERO
    }
}
