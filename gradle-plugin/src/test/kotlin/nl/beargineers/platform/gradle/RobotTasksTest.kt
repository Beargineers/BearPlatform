package nl.beargineers.platform.gradle

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assumptions.assumeFalse
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The robot's web server, as far as the tasks see it */
private class FakeRobot : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val requests = mutableListOf<String>()
    var configBody: String? = null
    var configType: String? = null
    var configAnswer = 200 to "Saved\n"
    var running = true
    var commands = listOf("forward 60 cm", "turn")
    var matchLogs = mapOf("2026-10-15/match-1.txt" to "first", "match-2.txt" to "second")

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/bear/config.properties") { exchange ->
            requests += "${exchange.requestMethod} ${exchange.requestURI}"
            configBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            configType = exchange.requestHeaders.getFirst("Content-Type")
            answer(exchange, configAnswer.first, configAnswer.second)
        }
        server.createContext("/bear/graphs/data") { exchange ->
            requests += "${exchange.requestMethod} ${exchange.requestURI}"
            val offered = commands.joinToString(",") { """{"name":"$it","started":0,"finished":0}""" }
            answer(exchange, 200, """{"run":1,"running":$running,"seq":0,"time":1.0,"lines":[],"commands":[$offered],"series":{}}""")
        }
        server.createContext("/bear/command") { exchange ->
            requests += "${exchange.requestMethod} ${exchange.requestURI}"
            val name = URLDecoder.decode(exchange.requestURI.rawQuery.removePrefix("name="), "UTF-8")
            if (name in commands) answer(exchange, 200, """{"name":"$name","run":2}""")
            else answer(exchange, 404, "No command '$name' is offered now; is the opmode running?\n")
        }
        server.createContext("/bear/matchLogs") { exchange ->
            requests += "${exchange.requestMethod} ${exchange.requestURI}"
            val zip = ByteArrayOutputStream().also { bytes ->
                ZipOutputStream(bytes).use { zip ->
                    for ((name, text) in matchLogs) {
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(text.toByteArray())
                        zip.closeEntry()
                    }
                }
            }.toByteArray()
            exchange.sendResponseHeaders(200, zip.size.toLong())
            exchange.responseBody.use { it.write(zip) }
        }
        server.start()
    }

    private fun answer(exchange: HttpExchange, status: Int, text: String) {
        val bytes = text.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() = server.stop(0)
}

class RobotTasksTest {
    private val dir: File = Files.createTempDirectory("bear-plugin").toFile()
    private val robot = FakeRobot()
    private val config = File(dir, "src/main/res/raw/config.properties")

    init {
        File(dir, "settings.gradle.kts").writeText("rootProject.name = \"teamcode\"\n")
        File(dir, "build.gradle.kts").writeText("plugins {\n    id(\"nl.beargineers.platform\")\n}\n")
        config.parentFile.mkdirs()
        config.writeText("# The robot's settings\narm.power = 0.7\nclaw.open = 25°\n")
    }

    @AfterTest
    fun cleanUp() {
        robot.close()
        dir.deleteRecursively()
    }

    private fun runner(vararg arguments: String) = GradleRunner.create()
        .withProjectDir(dir)
        .withPluginClasspath()
        .withArguments(*arguments, "-Pbear.robot=${robot.url}", "--stacktrace")

    private fun run(vararg arguments: String): BuildResult = runner(*arguments).build()

    private fun fail(vararg arguments: String): BuildResult = runner(*arguments).buildAndFail()

    @Test
    fun sendsTheModulesConfig() {
        val result = run("sendConfig")
        assertTrue("Robot: Saved" in result.output, result.output)
        assertEquals(config.readText(), robot.configBody)
        assertEquals("text/plain; charset=UTF-8", robot.configType)
        assertEquals(listOf("POST /bear/config.properties"), robot.requests)
    }

    @Test
    fun sendsAnotherFile() {
        File(dir, "practice.properties").writeText("arm.power = 0.5\n")
        run("sendConfig", "--file=practice.properties")
        assertEquals("arm.power = 0.5\n", robot.configBody)
    }

    @Test
    fun aRejectedConfigFailsWithTheRobotsReasons() {
        robot.configAnswer = 400 to "arm.power = strong: expected a number\n"
        val result = fail("sendConfig")
        assertTrue("The robot didn't take config.properties:" in result.output, result.output)
        assertTrue("arm.power = strong: expected a number" in result.output, result.output)
    }

    @Test
    fun listsTheCommandsOffered() {
        val result = run("robotCommand")
        assertTrue("Commands offered now:\n  forward 60 cm\n  turn" in result.output, result.output)

        robot.running = false
        assertTrue("No opmode is running" in run("robotCommand").output)
    }

    @Test
    fun startsACommand() {
        val result = run("robotCommand", "--name=forward 60 cm")
        assertTrue("Started 'forward 60 cm', run 2" in result.output, result.output)
        assertEquals(listOf("POST /bear/command?name=forward%2060%20cm"), robot.requests)

        val failed = fail("robotCommand", "--name=dance")
        assertTrue("The robot didn't start 'dance': No command 'dance' is offered now" in failed.output, failed.output)
    }

    @Test
    fun downloadsMatchLogsAsLogFiles() {
        val result = run("downloadMatchLogs")
        assertTrue("Downloaded 2 match logs into" in result.output, result.output)
        val folder = File(dir, "matchLogs").listFiles()!!.single()
        assertTrue(folder.name.startsWith("match-logs-"), folder.name)
        assertEquals("first", File(folder, "2026-10-15/match-1.log").readText())
        assertEquals("second", File(folder, "match-2.log").readText())

        robot.matchLogs = emptyMap()
        assertTrue("The robot has no match logs" in run("downloadMatchLogs").output)
    }

    @Test
    fun anUnreachableRobotSaysWhatToCheck() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val result = GradleRunner.create().withProjectDir(dir).withPluginClasspath()
            .withArguments("sendConfig", "-Pbear.robot=127.0.0.1:$closedPort").buildAndFail()
        assertTrue("Can't reach the robot at http://127.0.0.1:$closedPort" in result.output, result.output)
        assertTrue("Is the laptop on the robot's WiFi? Over USB, use -Pbear.robot=usb." in result.output, result.output)
    }

    @Test
    fun overUsbItForwardsThePortWithTheSdksAdb() {
        // The fake adb is a shell script
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        // A fake adb where local.properties says the Android SDK is, failing like adb without a robot plugged in
        val sdk = File(dir, "sdk")
        val adb = File(sdk, "platform-tools/adb")
        adb.parentFile.mkdirs()
        adb.writeText("#!/bin/sh\necho \"$@\" > \"$(dirname \"$0\")/called\"\necho 'error: no devices/emulators found'\nexit 1\n")
        adb.setExecutable(true)
        File(dir, "local.properties").writeText("sdk.dir=${sdk.path.replace("\\", "\\\\")}\n")

        val result = GradleRunner.create().withProjectDir(dir).withPluginClasspath()
            .withArguments("sendConfig", "-Pbear.robot=usb").buildAndFail()
        assertEquals("forward tcp:8080 tcp:8080", File(adb.parentFile, "called").readText().trim())
        assertTrue("adb couldn't reach the robot over USB. Is it plugged in and on?" in result.output, result.output)
        assertTrue("error: no devices/emulators found" in result.output, result.output)
    }

    @Test
    fun theTasksWorkWithTheConfigurationCache() {
        run("sendConfig", "--configuration-cache")
        val again = run("sendConfig", "--configuration-cache")
        assertTrue("Reusing configuration cache" in again.output, again.output)
        assertTrue("Robot: Saved" in again.output, again.output)
    }

    @Test
    fun theTasksAreGroupedForAndroidStudio() {
        val result = run("tasks", "--group=robot")
        for (task in listOf("sendConfig", "downloadMatchLogs", "robotCommand")) {
            assertTrue(task in result.output, result.output)
        }
    }
}
