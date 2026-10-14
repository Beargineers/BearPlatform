package nl.beargineers.platform.ftc

import com.qualcomm.robotcore.util.WebHandlerManager
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import nl.beargineers.platform.component.RobotRunner
import nl.beargineers.platform.config.Config
import nl.beargineers.platform.config.config
import nl.beargineers.platform.units.cm
import org.firstinspires.ftc.robotcore.internal.webserver.WebHandler
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource

private class Lift {
    val height by config(10.cm)
}

class WebPagesTest {
    private val directory = Files.createTempDirectory("bear-config").toFile()
    private val config = RobotConfig(ConfigFiles(directory, "1") { "Lift.height = 12" })
    private val log = mutableListOf<String>()
    private val handlers = mutableMapOf<String, WebHandler>()

    @BeforeTest
    fun setUp() {
        Config.load("")
        Lift()
        val manager: WebHandlerManager = fake { method, args ->
            if (method == "register") handlers[args[0] as String] = args[1] as WebHandler else Unanswered
        }
        WebPages.registerPages(manager, config) { log += it }
    }

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun request(
        path: String,
        method: Method = Method.GET,
        parameters: Map<String, String> = emptyMap(),
        body: String? = null,
    ): Response {
        val session: IHTTPSession = fake { name, args ->
            when (name) {
                "getMethod" -> method
                "getUri" -> path
                "getParameters" -> parameters.mapValues { listOf(it.value) }
                "parseBody" -> {
                    @Suppress("UNCHECKED_CAST")
                    if (body != null) (args[0] as MutableMap<String, String>)["postData"] = body
                }
                else -> Unanswered
            }
        }
        return handlers.getValue(path).getResponse(session)
    }

    private fun Response.text() = data.bufferedReader().readText()

    @Test
    fun whenTheFileCantBeWrittenNothingChangesAndThePageSaysWhy() {
        // A file where the config's folder should be: nothing can be written into it
        val blocked = RobotConfig(ConfigFiles(File(directory, "file").also { it.writeText("") }, "1") { "Lift.height = 12" })
        val failures = mutableListOf<String>()
        val manager: WebHandlerManager = fake { method, args ->
            if (method == "register") handlers[args[0] as String] = args[1] as WebHandler else Unanswered
        }
        WebPages.registerPages(manager, blocked) { failures += it }
        Config.load("Lift.height = 15")

        val response = request(SettingsPage.PATH, Method.POST, mapOf("settings" to "Lift.height = 20"))
        assertEquals(Status.INTERNAL_ERROR, response.status)
        val page = response.text()
        assertTrue("Not saved: the robot couldn't store it" in page, page)
        assertTrue("something there isn&#39;t a folder" in page || "something there isn't a folder" in page, page)
        assertEquals("Lift.height = 15", Config.text, "the previous config stays in effect")
        assertTrue(failures.single().startsWith("Config from the settings page couldn't be saved: "), "$failures")
    }

    @Test
    fun commandsStartFromTheWeb() {
        val runner = RobotRunner(TestTimeSource())
        var runs = 0
        runner.start { runner.commands.offer("spin") { runs++ } }
        runner.tick()
        LiveTelemetry.publish(runner.telemetry, runner.commands)
        LiveTelemetry.running = true
        try {
            val started = request(GraphsPage.COMMAND_PATH, Method.POST, mapOf("name" to "spin"))
            assertEquals(Status.OK, started.status)
            assertEquals("""{"name":"spin","run":1}""", started.text())
            assertEquals(listOf("Command 'spin' started from the web, run 1"), log)
            repeat(2) { runner.tick() }
            assertEquals(1, runs)
            assertTrue(""""commands":[{"name":"spin","started":1,"finished":1}]""" in request(GraphsPage.DATA_PATH).text())

            assertEquals(Status.NOT_FOUND, request(GraphsPage.COMMAND_PATH, Method.POST, mapOf("name" to "fly")).status)
            assertEquals(Status.METHOD_NOT_ALLOWED, request(GraphsPage.COMMAND_PATH, parameters = mapOf("name" to "spin")).status)
        } finally {
            LiveTelemetry.running = false
        }
    }

    @Test
    fun savingRedirectsSoReloadingDoesntSendTheEditAgain() {
        val response = request(SettingsPage.PATH, Method.POST, mapOf("settings" to "Lift.height = 20"))

        assertEquals(Status.REDIRECT_SEE_OTHER, response.status)
        assertEquals("/bear/config?saved", response.getHeader("Location"))
        assertEquals("Lift.height = 20", config.text)
        assertEquals(listOf("Config saved from the settings page"), log)

        val page = request(SettingsPage.PATH, parameters = mapOf("saved" to "")).text()
        assertTrue("Saved." in page, page)
    }

    @Test
    fun rejectedSaveShowsThePostedText() {
        val response = request(SettingsPage.PATH, Method.POST, mapOf("settings" to "Lift.height = tall"))

        assertEquals(Status.BAD_REQUEST, response.status)
        val page = response.text()
        assertTrue(">Lift.height = tall</textarea>" in page, page)
        assertTrue("Lift.height = tall: expected a distance" in page, page)
        assertTrue(log.single().startsWith("Config from the settings page rejected: Lift.height = tall"), "$log")
    }

    @Test
    fun resetRedirectsToo() {
        config.edit("Lift.height = 20")
        val response = request(SettingsPage.RESET_PATH, Method.POST)

        assertEquals(Status.REDIRECT_SEE_OTHER, response.status)
        assertEquals("/bear/config?reset", response.getHeader("Location"))
        assertEquals("Lift.height = 12", config.text)
        assertEquals(listOf("Config back to the one bundled with the app"), log)
    }

    @Test
    fun resetIsOnlyEverAPost() {
        config.edit("Lift.height = 20")
        val response = request(SettingsPage.RESET_PATH)

        assertEquals(Status.REDIRECT_SEE_OTHER, response.status)
        assertEquals("Lift.height = 20", config.text)
    }

    @Test
    fun scriptsGetPlainAnswers() {
        val saved = request(SettingsPage.RAW_PATH, Method.POST, body = "Lift.height = 30")
        assertEquals(Status.OK, saved.status)
        assertEquals("Saved\n", saved.text())
        assertEquals("Lift.height = 30", request(SettingsPage.RAW_PATH).text())
        assertEquals(listOf("Config saved from a script"), log)

        val rejected = request(SettingsPage.RAW_PATH, Method.POST, body = "Lift.height = tall")
        assertEquals(Status.BAD_REQUEST, rejected.status)
        assertTrue(rejected.text().startsWith("Lift.height = tall: expected a distance"))

        val empty = request(SettingsPage.RAW_PATH, Method.POST)
        assertEquals(Status.BAD_REQUEST, empty.status)
    }
}
