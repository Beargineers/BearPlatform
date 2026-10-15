package nl.beargineers.platform.gradle

import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import java.io.File

/**
 * Sends a config file to the robot. It takes effect right away, even in a running opmode, and stays until the next
 * install. The robot checks it first: a value it can't read rejects the whole file, and the robot keeps its config.
 */
abstract class SendConfig : RobotTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val file: RegularFileProperty

    /** Where Gradle was started from, for relative paths in --file */
    @get:Internal
    internal lateinit var invocationDir: File

    @Option(option = "file", description = "The config file to send, instead of the module's config.properties")
    fun fileOption(path: String) {
        file.set(File(path).let { if (it.isAbsolute) it else File(invocationDir, path) })
    }

    @TaskAction
    fun send() {
        val config = file.get().asFile
        val response = Connection().post("/bear/config.properties", config.readBytes(), "text/plain; charset=UTF-8")
        if (!response.ok) throw GradleException("The robot didn't take ${config.name}:\n${response.text}")
        logger.lifecycle("Robot: ${response.text}")
    }
}
