package org.beargineers.platform.ftc

import android.content.Context
import com.qualcomm.ftccommon.configuration.RobotConfigFileManager
import org.beargineers.platform.config.Config
import org.beargineers.platform.config.ConfigException
import org.firstinspires.ftc.robotcore.internal.system.AppUtil
import java.io.File

/**
 * The robot's config: loads the text from [files] into [Config], and keeps edits made on the robot.
 * The opmodes and the settings web page share one instance, [onRobot].
 *
 * One app can serve several robots: the text's sections for the active robot configuration apply (see [Config]).
 */
class RobotConfig(
    private val files: ConfigFiles,
    /** When the app was installed, in milliseconds since the epoch, if known */
    val appInstalledAt: Long? = null,
    /** The name of the robot configuration picked on the Driver Station, asked for at every load */
    private val robotConfiguration: () -> String? = { null },
) {
    private var loaded = false

    /** The config text in effect, or the one that will be once an opmode starts */
    val text: String
        @Synchronized get() = if (loaded) Config.text else files.read()

    /** The robot configuration whose sections of the config apply: the one picked on the Driver Station */
    val activeRobot: String? get() = robotConfiguration()

    /** Whether the config was edited on the robot since this build was installed */
    val isEdited: Boolean get() = files.hasEdits

    /**
     * Loads the config as an opmode starts, checking it against the settings its robot declares.
     * Throws [ConfigException] if a value can't be read.
     */
    @Synchronized
    fun loadForOpMode() {
        Config.load(text, robotConfiguration())
        loaded = true
    }

    /**
     * Applies [newText] right away and keeps it on the robot until the next install.
     * Throws [ConfigException], changing nothing, if a value can't be read, and whatever went wrong, also changing
     * nothing, if it couldn't be stored.
     */
    @Synchronized
    fun edit(newText: String) = applyAndStore(newText) { files.save(newText) }

    /** Goes back to the config bundled with the app. Throws [ConfigException], changing nothing, if it can't be read. */
    @Synchronized
    fun discardEdits() = applyAndStore(files.bundled()) { files.discardEdits() }

    // Loading checks the values; storing comes after it, and a failure there takes the loaded values back
    private fun applyAndStore(newText: String, store: () -> Unit) {
        val previous = Config.text
        val robot = robotConfiguration()
        Config.load(newText, robot)
        try {
            store()
        } catch (e: Exception) {
            runCatching { Config.load(previous, robot) }
            throw e
        }
        loaded = true
    }

    companion object {
        @Volatile
        private var onRobot: RobotConfig? = null

        /**
         * The config of the app on this Control Hub. The bundled config is `res/raw/config.properties` of the app;
         * edits are kept in `FIRST/settings/BearPlatformConfig` on the Control Hub. (Not `BearPlatform`: the DECODE
         * code keeps a file of its own settings there.)
         */
        fun onRobot(context: Context): RobotConfig = onRobot ?: synchronized(this) {
            onRobot ?: run {
                val installedAt = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
                val files = ConfigFiles(File(AppUtil.ROBOT_SETTINGS, "BearPlatformConfig"), installedAt.toString()) {
                    bundled(context)
                }
                RobotConfig(files, installedAt) { activeRobotConfiguration() }
            }.also { onRobot = it }
        }

        private fun bundled(context: Context): String {
            val resources = context.resources
            val id = resources.getIdentifier("config", "raw", context.packageName)
            return if (id == 0) "" else resources.openRawResource(id).bufferedReader().use { it.readText() }
        }
    }
}

/** The name of the robot configuration picked on the Driver Station; null where there is none, like in tests */
internal fun activeRobotConfiguration(): String? =
    runCatching { RobotConfigFileManager().activeConfig.name }.getOrNull()?.takeIf { it.isNotBlank() }
