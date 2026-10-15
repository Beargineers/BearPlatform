package nl.beargineers.platform.gradle

import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Downloads the SDK's match logs into a new folder, `match-logs-<date>-<time>`, and deletes them from the robot. Logs
 * are renamed from .txt to .log, which editors color as logs.
 */
abstract class DownloadMatchLogs : RobotTask() {
    @get:Internal
    abstract val into: DirectoryProperty

    @Option(option = "into", description = "The folder to download into, instead of the module's matchLogs")
    fun intoOption(path: String) {
        into.set(project.layout.projectDirectory.dir(path))
    }

    @TaskAction
    fun download() {
        val response = Connection().get("/bear/matchLogs", timeout = Duration.ofSeconds(60))
        if (!response.ok) throw GradleException("The robot didn't send its match logs:\n${response.text}")

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val folder = into.get().dir("match-logs-$stamp").asFile
        val count = unzip(response.body, folder)
        if (count == 0) {
            folder.delete()
            logger.lifecycle("The robot has no match logs")
        } else {
            logger.lifecycle("Downloaded $count match log${if (count == 1) "" else "s"} into $folder")
        }
    }

    private fun unzip(zip: ByteArray, folder: File): Int {
        var count = 0
        val root = folder.canonicalFile
        ZipInputStream(ByteArrayInputStream(zip)).use { entries ->
            while (true) {
                val entry = entries.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.removeSuffix(".txt").let { if (it != entry.name) "$it.log" else it }
                val file = File(root, name).canonicalFile
                // An entry must not write outside the folder, whatever its name says
                if (!file.path.startsWith(root.path + File.separator)) {
                    throw GradleException("The robot's match logs name a file outside their folder: ${entry.name}")
                }
                file.parentFile.mkdirs()
                file.outputStream().use { entries.copyTo(it) }
                count++
            }
        }
        return count
    }
}
