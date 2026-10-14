package nl.beargineers.platform.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Something only one action at a time may control, like a drivetrain or an arm.
 *
 * [takeOver] stops whatever action controls the resource right now and runs the new one instead, so the latest
 * request always wins: pressing "go shoot" while the robot drives to the ramp stops the ramp drive. The interrupted
 * action ends with [Preempted], which is a cancellation, so the coroutine that ran it stops too.
 *
 * Use it from the loop thread only, like everything else in [LoopRuntime].
 */
class ExclusiveResource(val name: String) {
    private val key = object : CoroutineContext.Key<Holder> {}

    private inner class Holder : AbstractCoroutineContextElement(key)

    private var holderJob: Job? = null

    /** Name of the action in control right now, or null if the resource is free */
    var currentAction: String? = null
        private set

    val isBusy: Boolean get() = holderJob?.isActive == true

    /**
     * Takes control of this resource and runs [block], after stopping the action that was in control before
     * and letting it finish its cleanup. [action] is only a label for logs and [currentAction]: any [takeOver]
     * stops the previous action, whatever the labels.
     *
     * Calling [takeOver] again from inside [block] (for example `driveTo()` from inside `drivePath()`) keeps control
     * and does not interrupt anything. That includes coroutines launched inside [block]: they share control with it,
     * so two of them driving at the same time would fight each other.
     */
    suspend fun <T> takeOver(action: String, block: suspend CoroutineScope.() -> T): T {
        if (currentCoroutineContext()[key] != null) return coroutineScope(block)

        return withContext(Holder()) {
            val me = coroutineContext.job
            val previous = holderJob
            val previousAction = currentAction
            holderJob = me
            currentAction = action

            if (previous != null && previous.isActive) {
                previous.cancel(Preempted(name, previousAction, action))
                // Let it clean up, e.g. stop the motors, before this action starts driving them
                previous.join()
            }

            try {
                block()
            } finally {
                if (holderJob === me) {
                    holderJob = null
                    currentAction = null
                }
            }
        }
    }

    /** Stops the action that controls this resource, if any */
    fun cancel() {
        holderJob?.cancel(Preempted(name, currentAction, null))
    }
}

/** The action controlling [resource] was stopped, because another action took over or it was cancelled */
class Preempted(val resource: String, val action: String?, val by: String?) : CancellationException(
    if (by != null) "$resource: '$action' was taken over by '$by'" else "$resource: '$action' was cancelled"
)
