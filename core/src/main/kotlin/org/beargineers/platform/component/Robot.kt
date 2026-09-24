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
    private val reported = mutableListOf<String>()

    /** Problems components reported with [warn], like doubtful hardware settings. The opmode shows them at INIT. */
    val warnings: List<String> get() = reported.toList()

    init {
        host.runner.attach(this)
    }

    internal fun addWarning(warning: String) {
        reported += warning
    }
}

/**
 * What a robot is created with: the opmode on the Control Hub, or a [RobotRunner] in tests.
 * Platform modules extend it with what their components need, like the FTC hardware.
 */
interface RobotHost {
    /** Runs the robot created with this host */
    val runner: RobotRunner
}

/**
 * Runs a robot and coroutines through the phases of an opmode: [init], a [tick] per loop, [start], more ticks, [stop].
 * The FTC opmode drives it on the robot; tests drive it directly.
 */
class RobotRunner(timeSource: TimeSource = TimeSource.Monotonic, log: (String) -> Unit = {}) : RobotHost {
    val runtime = LoopRuntime(timeSource, log)

    override val runner: RobotRunner get() = this

    var robot: Robot? = null
        private set

    private var initJob: Job? = null
    private val inputs = mutableListOf<() -> Unit>()

    /** Reads inputs, like gamepads, at the start of every [tick], before anything else runs */
    internal fun readEveryTick(input: () -> Unit) {
        inputs += input
    }

    internal fun attach(robot: Robot) {
        check(this.robot == null) { "An opmode can only have one robot" }
        this.robot = robot
    }

    /** Initializes all components and launches [onInit], which runs until [start] */
    fun init(onInit: suspend CoroutineScope.() -> Unit = {}) {
        robot?.inOrder()?.forEach { it.initIfNew() }
        initJob = runtime.launch("init", onInit)
    }

    /**
     * One loop: reads inputs, updates all components (initializing those created since the last loop first),
     * then runs coroutines
     */
    fun tick() {
        inputs.forEach { it() }
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
