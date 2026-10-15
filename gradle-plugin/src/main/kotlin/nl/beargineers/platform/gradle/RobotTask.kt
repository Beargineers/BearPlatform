package nl.beargineers.platform.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.inject.Inject

/** A task that talks to the robot's web server */
abstract class RobotTask : DefaultTask() {
    /** The robot's web server, or `usb` */
    @get:Input
    abstract val robot: Property<String>

    /** adb, for reaching the robot over USB */
    @get:Internal
    abstract val adb: Property<String>

    @get:Inject
    protected abstract val exec: ExecOperations

    internal class Response(val status: Int, val body: ByteArray) {
        val ok: Boolean get() = status in 200..299
        val text: String get() = body.toString(Charsets.UTF_8).trim()
    }

    /** Talks to the robot; made when the task runs, as reaching it over USB forwards a port first */
    internal inner class Connection {
        private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

        val baseUrl: String = robot.get().trim().trimEnd('/').let { address ->
            when {
                address.equals("usb", ignoreCase = true) -> {
                    forwardOverUsb()
                    "http://localhost:$ROBOT_PORT"
                }
                "://" in address -> address
                else -> "http://$address"
            }
        }

        fun get(path: String, timeout: Duration = Duration.ofSeconds(10)): Response =
            send(HttpRequest.newBuilder(uri(path)).timeout(timeout).GET())

        fun post(path: String, body: ByteArray, contentType: String, timeout: Duration = Duration.ofSeconds(10)): Response =
            send(
                HttpRequest.newBuilder(uri(path)).timeout(timeout)
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            )

        private fun uri(path: String): URI = URI.create(baseUrl + path)

        private fun send(request: HttpRequest.Builder): Response = try {
            val response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
            Response(response.statusCode(), response.body())
        } catch (e: IOException) {
            throw GradleException(
                "Can't reach the robot at $baseUrl (${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}). " +
                    "Is the laptop on the robot's WiFi? Over USB, use -Pbear.robot=usb.",
                e,
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw GradleException("Interrupted while talking to the robot", e)
        }
    }

    private fun forwardOverUsb() {
        val output = ByteArrayOutputStream()
        val result = try {
            exec.exec { spec ->
                spec.commandLine(adb.get(), "forward", "tcp:$ROBOT_PORT", "tcp:$ROBOT_PORT")
                spec.standardOutput = output
                spec.errorOutput = output
                spec.isIgnoreExitValue = true
            }
        } catch (e: Exception) {
            throw GradleException(
                "Can't run adb (${adb.get()}) to reach the robot over USB. Open the project in Android Studio once, " +
                    "so local.properties says where the Android SDK is, or put adb on the PATH.",
                e,
            )
        }
        if (result.exitValue != 0) {
            throw GradleException("adb couldn't reach the robot over USB. Is it plugged in and on?\n" + output.toString().trim())
        }
    }

    internal companion object {
        const val ROBOT_PORT = 8080
    }
}
