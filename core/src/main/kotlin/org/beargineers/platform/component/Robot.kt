package org.beargineers.platform.component

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.beargineers.platform.runtime.LoopRuntime
import kotlin.time.TimeSource

/**
 * The top of a robot's component tree. Create it in the opmode: `val robot = GammaRobot(this)`.
 *
 * The robot's name is not part of its parts' paths, so config keys stay short: `shooter.kV`, not `robot.shooter.kV`.
 * Settings declared in the robot class itself use the `robot` scope.
 */
abstract class Robot(val host: RobotHost) : Component() {
    init {
        host.attach(this)
    }
}

/** What runs a robot: the opmode on the Control Hub, or a test */
interface RobotHost {
    val runtime: LoopRuntime

    /** Called by the [Robot] when it is created */
    fun attach(robot: Robot)
}

/**
 * Runs a robot and coroutines through the phases of an opmode: [init], a [tick] per loop, [start], more ticks, [stop].
 * The FTC opmode drives it on the robot; tests drive it directly.
 */
class RobotRunner(timeSource: TimeSource = TimeSource.Monotonic, log: (String) -> Unit = {}) : RobotHost {
    override val runtime = LoopRuntime(timeSource, log)

    var robot: Robot? = null
        private set

    private var initJob: Job? = null

    override fun attach(robot: Robot) {
        check(this.robot == null) { "An opmode can only have one robot" }
        this.robot = robot
    }

    /** Initializes all components and launches [onInit], which runs until [start] */
    fun init(onInit: suspend CoroutineScope.() -> Unit = {}) {
        robot?.inOrder()?.forEach { it.initIfNew() }
        initJob = runtime.launch("init", onInit)
    }

    /** One loop: updates all components, initializing those created since the last loop first, then runs coroutines */
    fun tick() {
        robot?.let { robot ->
            val components = robot.inOrder()
            components.forEach { it.initIfNew() }
            components.forEach { it.runUpdate() }
        }
        runtime.tick()
    }

    /** Cancels the init coroutine and launches [onStart] */
    fun start(onStart: suspend CoroutineScope.() -> Unit) {
        initJob?.cancel()
        runtime.launch("main", onStart)
    }

    /** Cancels all coroutines, letting them clean up, then stops all components. Rethrows the first failure. */
    fun stop() {
        var failure: Throwable? = null
        try {
            runtime.stop()
        } catch (e: Throwable) {
            failure = e
        }
        for (component in robot?.inOrder()?.asReversed().orEmpty()) {
            try {
                component.runStopIfInitialized()
            } catch (e: Throwable) {
                val first = failure
                if (first == null) failure = e else first.addSuppressed(e)
            }
        }
        failure?.let { throw it }
    }
}
