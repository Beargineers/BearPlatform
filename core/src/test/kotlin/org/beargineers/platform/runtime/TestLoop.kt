package org.beargineers.platform.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/** A [LoopRuntime] with a fake clock that moves forward by [step] on every tick */
class TestLoop(private val step: Duration = 10.milliseconds) {
    val time = TestTimeSource()
    val log = mutableListOf<String>()
    val runtime = LoopRuntime(time) { log += it }

    fun launch(name: String = "test", block: suspend CoroutineScope.() -> Unit): Job = runtime.launch(name, block)

    fun tick(times: Int = 1) {
        repeat(times) {
            time += step
            runtime.tick()
        }
    }
}
