package org.beargineers.platform.ftc

import org.beargineers.platform.component.Robot

/**
 * The robots one app can be, by the name of the robot configuration picked on the Driver Station. One app then serves
 * several robots, and opmodes shared between them create whichever robot this one is:
 * ```
 * object DecodeRobots : RobotChoice<DecodeRobot>(
 *     "betaDecode" to ::BetaRobot,
 *     "gammaDecode" to ::GammaRobot,
 * )
 *
 * class Driving : CoroutineOpMode() {
 *     val robot by robot(DecodeRobots)                     // Beta on betaDecode, Gamma on gammaDecode
 * }
 * class TurretTuning : CoroutineOpMode() {
 *     val robot by robot(DecodeRobots.only<GammaRobot>())  // fails INIT on Beta, saying so
 * }
 * ```
 * The config follows the same name: the text's `[betaDecode]` section is Beta's (see [Config][org.beargineers.platform.config.Config]).
 */
open class RobotChoice<R : Robot>(vararg robots: Pair<String, (FtcHost) -> R>) : (FtcHost) -> R {
    private val robots: Map<String, (FtcHost) -> R> = robots.toMap()

    init {
        val repeated = robots.groupBy { it.first }.filterValues { it.size > 1 }.keys
        require(repeated.isEmpty()) { "${javaClass.simpleName} names the robot configurations $repeated more than once" }
    }

    /** The names of the robot configurations this app has robots for */
    val configurations: Set<String> get() = robots.keys

    /** Creates the robot of the robot configuration picked on the Driver Station */
    override fun invoke(host: FtcHost): R {
        val name = host.robotConfiguration
            ?: error("No robot configuration is active. Pick one on the Driver Station: ${configurations.joinToString()}")
        val create = robots[name]
            ?: error("This app has no robot for the robot configuration '$name'. ${javaClass.simpleName} knows ${configurations.joinToString()}")
        return create(host)
    }

    /** Creates the robot like [invoke], failing unless it is a [T]: for opmodes of one robot only */
    inline fun <reified T : R> only(): (FtcHost) -> T = { host -> narrow(invoke(host), T::class.java, host) }

    @PublishedApi
    internal fun <T : R> narrow(robot: R, type: Class<T>, host: FtcHost): T {
        check(type.isInstance(robot)) {
            "This opmode is for ${type.simpleName}, and the robot configuration '${host.robotConfiguration}' is " +
                "${robot.javaClass.simpleName}'s. Pick ${type.simpleName}'s configuration on the Driver Station"
        }
        return type.cast(robot)!!
    }
}
