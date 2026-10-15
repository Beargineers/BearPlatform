package nl.beargineers.platform.gradle

import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import java.net.URLEncoder

/**
 * Starts a command the running opmode offers, like a button on the robot's graphs page: `--name="forward 60 cm"`.
 * Without a name, lists the commands offered now. Someone at the Driver Station should be ready to stop the robot.
 */
abstract class RobotCommand : RobotTask() {
    @get:Input
    @get:Optional
    @get:Option(option = "name", description = "The command to start; without it, lists the commands offered now")
    abstract val commandName: Property<String>

    @TaskAction
    fun run() {
        val name = commandName.orNull
        val robot = Connection()
        if (name == null) list(robot) else start(robot, name)
    }

    private fun list(robot: Connection) {
        val response = robot.get("/bear/graphs/data?since=$LATEST")
        if (!response.ok) throw GradleException("The robot didn't say what it offers:\n${response.text}")
        val data = Json.parse(response.text) as? Map<*, *>
            ?: throw GradleException("The robot answered something unexpected:\n${response.text}")
        val names = (data["commands"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.get("name") as? String }
        when {
            data["running"] != true -> logger.lifecycle("No opmode is running")
            names.isEmpty() -> logger.lifecycle("The opmode offers no commands now")
            else -> logger.lifecycle("Commands offered now:\n" + names.joinToString("\n") { "  $it" })
        }
    }

    private fun start(robot: Connection, name: String) {
        // %20 rather than +, which not every server reads as a space
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val response = robot.post("/bear/command?name=$encoded", ByteArray(0), "text/plain")
        if (!response.ok) throw GradleException("The robot didn't start '$name': ${response.text}")
        val run = ((Json.parse(response.text) as? Map<*, *>)?.get("run") as? Double)?.toInt()
        logger.lifecycle("Started '$name'" + (run?.let { ", run $it" } ?: ""))
    }

    private companion object {
        /** Asks for samples after this one: none, just the opmode's state and commands */
        const val LATEST = 999_999_999_999L
    }
}
