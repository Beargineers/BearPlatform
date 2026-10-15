package nl.beargineers.platform.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonTest {
    @Test
    fun readsWhatTheRobotSends() {
        val data = Json.parse(
            """{"run":3,"running":true,"time":12.5,"lines":["Pose: (1.0cm, 2.0cm) 90.0°"],
               "commands":[{"name":"forward \"60\" cm","started":1,"finished":0},{"name":"turn°","started":0}],
               "series":{"x cm":[[0.1,-1.5e2]]},"nothing":null,"off":false}"""
        ) as Map<*, *>
        assertEquals(3.0, data["run"])
        assertEquals(true, data["running"])
        assertEquals(listOf("Pose: (1.0cm, 2.0cm) 90.0°"), data["lines"])
        val commands = data["commands"] as List<*>
        assertEquals("forward \"60\" cm", (commands[0] as Map<*, *>)["name"])
        assertEquals("turn°", (commands[1] as Map<*, *>)["name"])
        assertEquals(listOf(listOf(0.1, -150.0)), (data["series"] as Map<*, *>)["x cm"])
        assertEquals(null, data["nothing"])
        assertEquals(false, data["off"])
        assertEquals(emptyMap<String, Any?>(), Json.parse(" { } "))
        assertEquals(emptyList<Any?>(), Json.parse("[]"))
    }

    @Test
    fun saysWhereItGotLost() {
        assertFailsWith<IllegalArgumentException> { Json.parse("{\"a\":}") }
        assertFailsWith<IllegalArgumentException> { Json.parse("[1,2") }
        assertFailsWith<IllegalArgumentException> { Json.parse("{} x") }
        assertFailsWith<IllegalArgumentException> { Json.parse("\"open") }
    }
}
