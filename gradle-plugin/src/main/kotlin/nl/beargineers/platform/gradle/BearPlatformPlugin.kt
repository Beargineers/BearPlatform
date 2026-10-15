package nl.beargineers.platform.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import java.io.File
import java.util.Properties

/**
 * Tasks that talk to a robot running BearPlatform, for the season project's app module (TeamCode):
 * - `sendConfig` sends the module's `res/raw/config.properties` to the robot, where it takes effect right away;
 * - `downloadMatchLogs` downloads the SDK's match logs into `matchLogs/`, and deletes them from the robot;
 * - `robotCommand --name="forward 60 cm"` starts a command the running opmode offers; without a name, it lists them.
 *
 * They reach the robot on its WiFi. Over USB, set `bear.robot=usb` (in `gradle.properties`, or `-Pbear.robot=usb`):
 * they forward the robot's port with adb first.
 */
class BearPlatformPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("bearPlatform", BearPlatformExtension::class.java)
        extension.robot.convention(project.providers.gradleProperty("bear.robot").orElse(DEFAULT_ROBOT))
        extension.config.convention(project.layout.projectDirectory.file("src/main/res/raw/config.properties"))
        extension.matchLogs.convention(project.layout.projectDirectory.dir("matchLogs"))

        val rootDir = project.rootDir
        val adb = project.providers.provider { findAdb(rootDir) }
        val invocationDir = project.gradle.startParameter.currentDir

        project.tasks.withType(RobotTask::class.java).configureEach { task ->
            task.group = "robot"
            task.robot.convention(extension.robot)
            task.adb.convention(adb)
        }
        project.tasks.register("sendConfig", SendConfig::class.java) { task ->
            task.description = "Sends the config to the robot, where it takes effect right away. --file=... sends another."
            task.file.convention(extension.config)
            task.invocationDir = invocationDir
        }
        project.tasks.register("downloadMatchLogs", DownloadMatchLogs::class.java) { task ->
            task.description = "Downloads the SDK's match logs into matchLogs/, and deletes them from the robot."
            task.into.convention(extension.matchLogs)
        }
        project.tasks.register("robotCommand", RobotCommand::class.java) { task ->
            task.description = "Starts a command the running opmode offers: --name=\"forward 60 cm\". Lists them without --name."
        }
    }

    companion object {
        const val DEFAULT_ROBOT = "http://192.168.43.1:8080"

        /** adb from the Android SDK that Android Studio set up, or from the PATH */
        internal fun findAdb(rootDir: File): String {
            val exe = if (System.getProperty("os.name").startsWith("Windows")) "adb.exe" else "adb"
            val sdks = listOfNotNull(
                sdkFromLocalProperties(rootDir),
                System.getenv("ANDROID_HOME"),
                System.getenv("ANDROID_SDK_ROOT"),
            )
            return sdks.map { File(it, "platform-tools/$exe") }.firstOrNull { it.isFile }?.path ?: exe
        }

        private fun sdkFromLocalProperties(rootDir: File): String? {
            val file = File(rootDir, "local.properties")
            if (!file.isFile) return null
            return Properties().apply { file.reader().use { load(it) } }.getProperty("sdk.dir")
        }
    }
}

/** Settings of the robot tasks: `bearPlatform { robot = "usb" }` in the module's build file */
abstract class BearPlatformExtension {
    /**
     * The robot's web server: `http://192.168.43.1:8080` on the robot's WiFi, by default from the `bear.robot` Gradle
     * property. `usb` reaches it over USB, through adb.
     */
    abstract val robot: Property<String>

    /** The config `sendConfig` sends: the module's `src/main/res/raw/config.properties` */
    abstract val config: RegularFileProperty

    /** Where `downloadMatchLogs` puts the logs: the module's `matchLogs` folder */
    abstract val matchLogs: DirectoryProperty
}
