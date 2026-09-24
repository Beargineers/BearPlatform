package org.beargineers.platform.ftc

import android.content.Context
import com.qualcomm.robotcore.util.RobotLog
import com.qualcomm.robotcore.util.WebHandlerManager
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.beargineers.platform.config.ConfigException
import org.beargineers.platform.ftc.SettingsPage.Outcome
import org.firstinspires.ftc.ftccommon.external.WebHandlerRegistrar
import org.firstinspires.ftc.robotcore.internal.system.AppUtil
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pages served by the Control Hub's own web server, so they work whenever the robot is on, opmode or not:
 * - `http://192.168.43.1:8080/bear/config` edits the config; changes take effect right away.
 * - `/bear/config.properties` gets (GET) or replaces (POST, `text/plain`) the config text, for scripts.
 * - `/bear/matchLogs` downloads the SDK's match logs as a zip, and deletes them from the robot.
 */
object SettingsWebPages {
    @WebHandlerRegistrar
    @JvmStatic
    fun register(context: Context, manager: WebHandlerManager) {
        val config = RobotConfig.onRobot(context)
        val log = { message: String -> RobotLog.ii(LOG_TAG, "%s", message) }
        registerPages(manager, config, log)
        manager.register(SettingsPage.MATCH_LOGS_PATH) { matchLogs() }
        log("Settings page ready at ${SettingsPage.PATH}; app installed ${config.appInstalledAt?.let(SettingsPage::installedAgo)}")
    }

    internal fun registerPages(manager: WebHandlerManager, config: RobotConfig, log: (String) -> Unit) {
        manager.register(SettingsPage.PATH) { session -> configPage(config, session, log) }
        manager.register(SettingsPage.RESET_PATH) { session -> resetConfig(config, session, log) }
        manager.register(SettingsPage.RAW_PATH) { session -> rawConfig(config, session, log) }
    }

    private fun configPage(config: RobotConfig, session: IHTTPSession, log: (String) -> Unit): Response {
        if (session.method != NanoHTTPD.Method.POST) {
            val outcome = when {
                SAVED in session.parameters -> Outcome.Saved
                RESET in session.parameters -> Outcome.Reset
                else -> null
            }
            return html(Status.OK, SettingsPage.html(config, outcome))
        }

        session.parseBody(HashMap())
        val text = session.parameters["settings"]?.firstOrNull() ?: ""
        return try {
            config.edit(text)
            log("Config saved from the settings page")
            // Answer with a redirect, so that reloading the page shows it again rather than sending this edit again
            redirect("${SettingsPage.PATH}?$SAVED")
        } catch (e: ConfigException) {
            log("Config from the settings page rejected: ${e.problems.joinToString("; ")}")
            html(Status.BAD_REQUEST, SettingsPage.html(config, Outcome.Rejected(e.problems), text))
        }
    }

    private fun resetConfig(config: RobotConfig, session: IHTTPSession, log: (String) -> Unit): Response {
        if (session.method != NanoHTTPD.Method.POST) return redirect(SettingsPage.PATH)
        return try {
            config.discardEdits()
            log("Config back to the one bundled with the app")
            redirect("${SettingsPage.PATH}?$RESET")
        } catch (e: ConfigException) {
            log("Bundled config rejected: ${e.problems.joinToString("; ")}")
            html(Status.BAD_REQUEST, SettingsPage.html(config, Outcome.Rejected(e.problems)))
        }
    }

    private fun rawConfig(config: RobotConfig, session: IHTTPSession, log: (String) -> Unit): Response {
        if (session.method != NanoHTTPD.Method.POST) return text(Status.OK, config.text)

        val body = HashMap<String, String>()
        session.parseBody(body)
        val newText = body["postData"]
            ?: return text(Status.BAD_REQUEST, "Send the config as the body, with Content-Type: text/plain\n")
        return try {
            config.edit(newText)
            log("Config saved from a script")
            text(Status.OK, "Saved\n")
        } catch (e: ConfigException) {
            log("Config from a script rejected: ${e.problems.joinToString("; ")}")
            text(Status.BAD_REQUEST, e.problems.joinToString("\n", postfix = "\n"))
        }
    }

    private fun matchLogs(): Response {
        val folder = AppUtil.MATCH_LOG_FOLDER
        val logs = folder.walkTopDown().filter { it.isFile }.toList()
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                for (log in logs) {
                    zip.putNextEntry(ZipEntry(log.relativeTo(folder).invariantSeparatorsPath))
                    log.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }.toByteArray()

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return NanoHTTPD.newFixedLengthResponse(Status.OK, "application/zip", DeleteWhenSent(zip, logs), zip.size.toLong())
            .apply {
                addHeader("Content-Disposition", "attachment; filename=\"match-logs-$timestamp.zip\"")
                addHeader("Cache-Control", "no-store")
            }
    }

    // The SDK's web server answers in UTF-8 whatever the charset, and logs a warning unless it is spelled exactly so
    private fun html(status: Status, html: String): Response =
        NanoHTTPD.newFixedLengthResponse(status, "text/html; charset=UTF-8", html)

    private fun text(status: Status, text: String): Response =
        NanoHTTPD.newFixedLengthResponse(status, "text/plain; charset=UTF-8", text)

    private fun redirect(location: String): Response =
        NanoHTTPD.newFixedLengthResponse(Status.REDIRECT_SEE_OTHER, "text/plain; charset=UTF-8", "")
            .apply { addHeader("Location", location) }

    private const val SAVED = "saved"
    private const val RESET = "reset"

    /** Deletes the zipped logs once the whole zip was sent, so an interrupted download loses nothing */
    private class DeleteWhenSent(bytes: ByteArray, private val files: List<File>) : ByteArrayInputStream(bytes) {
        private var deleted = false

        override fun read(): Int = super.read().also { deleteIfSent() }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { deleteIfSent() }

        private fun deleteIfSent() {
            if (available() == 0 && !deleted) {
                deleted = true
                files.forEach { it.delete() }
            }
        }
    }
}
