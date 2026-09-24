package org.beargineers.platform.ftc

import android.content.Context
import org.beargineers.platform.config.Config
import org.beargineers.platform.config.ConfigException
import org.firstinspires.ftc.robotcore.internal.system.AppUtil
import java.io.File

/**
 * The robot's config: loads the text from [files] into [Config], and keeps edits made on the robot.
 * The opmodes and the settings web page share one instance, [onRobot].
 */
class RobotConfig(
    private val files: ConfigFiles,
    /** When the app was installed, in milliseconds since the epoch, if known */
    val appInstalledAt: Long? = null,
) {
    private var loaded = false

    /** The config text in effect, or the one that will be once an opmode starts */
    val text: String
        @Synchronized get() = if (loaded) Config.text else files.read()

    /** Whether the config was edited on the robot since this build was installed */
    val isEdited: Boolean get() = files.hasEdits

    /**
     * Loads the config as an opmode starts, checking it against the settings its robot declares.
     * Throws [ConfigException] if a value can't be read.
     */
    @Synchronized
    fun loadForOpMode() {
        Config.load(text)
        loaded = true
    }

    /**
     * Applies [newText] right away and keeps it on the robot until the next install.
     * Throws [ConfigException], changing nothing, if a value can't be read.
     */
    @Synchronized
    fun edit(newText: String) {
        Config.load(newText)
        files.save(newText)
        loaded = true
    }

    /** Goes back to the config bundled with the app. Throws [ConfigException], changing nothing, if it can't be read. */
    @Synchronized
    fun discardEdits() {
        Config.load(files.bundled())
        files.discardEdits()
        loaded = true
    }

    companion object {
        @Volatile
        private var onRobot: RobotConfig? = null

        /**
         * The config of the app on this Control Hub. The bundled config is `res/raw/config.properties` of the app;
         * edits are kept in `FIRST/settings/BearPlatform` on the Control Hub.
         */
        fun onRobot(context: Context): RobotConfig = onRobot ?: synchronized(this) {
            onRobot ?: run {
                val installedAt = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
                val files = ConfigFiles(File(AppUtil.ROBOT_SETTINGS, "BearPlatform"), installedAt.toString()) {
                    bundled(context)
                }
                RobotConfig(files, installedAt)
            }.also { onRobot = it }
        }

        private fun bundled(context: Context): String {
            val resources = context.resources
            val id = resources.getIdentifier("config", "raw", context.packageName)
            return if (id == 0) "" else resources.openRawResource(id).bufferedReader().use { it.readText() }
        }
    }
}
