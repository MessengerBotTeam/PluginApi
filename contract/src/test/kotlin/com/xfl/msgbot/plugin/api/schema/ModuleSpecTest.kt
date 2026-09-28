package com.xfl.msgbot.plugin.api.schema

import com.xfl.msgbot.plugin.api.standard.StandardApi
import com.xfl.msgbot.plugin.api.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModuleSpecTest {
    private val weather =
        moduleSpec("weather", version = 2) {
            doc = "Forecasts"
            function("forecast", returns = Type.STRING, doc = "Today's weather") {
                param("city", Type.STRING)
                optional("days", Type.INT)
            }
            event("alert") { field("text", Type.STRING) }
        }

    @Test
    fun `a spec survives the trip through a value`() {
        assertEquals(weather, ModuleSpec.fromValue(weather.toValue()))
        StandardApi.HOST.plus(StandardApi.Bot).forEach { assertEquals(it, ModuleSpec.fromValue(it.toValue())) }
    }

    @Test
    fun `a namespace is one lowercase word, so none can contain another`() {
        assertFailsWith<IllegalArgumentException> { moduleSpec("weather.radar") {} }
        assertFailsWith<IllegalArgumentException> { moduleSpec("Weather") {} }
        assertEquals(null, Names.split("weather.radar.update"))
        assertEquals("weather" to "alert", Names.split("weather.alert"))
    }

    @Test
    fun `a required parameter after an optional one is refused`() {
        assertFailsWith<IllegalArgumentException> {
            moduleSpec("x") {
                function("f") {
                    optional("a", Type.INT)
                    param("b", Type.INT)
                }
            }
        }
    }

    @Test
    fun `arguments are checked by name`() {
        val forecast = weather.function("forecast")!!
        assertNull(forecast.checkArgs(mapOf("city" to Value.VString("Seoul"))))
        assertNull(forecast.checkArgs(mapOf("city" to Value.VString("Seoul"), "days" to Value.VInt(3))))
        assertEquals("city: required (string), but missing", forecast.checkArgs(emptyMap()))
        assertTrue(forecast.checkArgs(mapOf("city" to Value.VString("Seoul"), "day" to Value.VInt(3)))!!.startsWith("day: unknown field"))
        assertEquals("result: expected string, got int", forecast.checkResult(Value.VInt(1)))
    }

    @Test
    fun `a partial implementation of a standard is accepted, a changed signature is not`() {
        val part = StandardApi.Bot.restrictTo(listOf("reply"), listOf("message"))
        assertEquals(listOf("reply"), part.functions.map { it.name })
        assertNull(StandardApi.Bot.incompatibility(part))

        val forged = moduleSpec("bot") { function("reply", returns = Type.BOOL) { param("text", Type.STRING) } }
        assertEquals("bot.reply leaves out the required parameter 'token'", StandardApi.Bot.incompatibility(forged))
        val retyped = moduleSpec("bot") { function("reply", returns = Type.STRING) { param("token", Type.STRING); param("text", Type.STRING) } }
        assertTrue(StandardApi.Bot.incompatibility(retyped)!!.contains("answers string"))
        val invented = moduleSpec("bot") { function("teleport") }
        assertTrue(StandardApi.Bot.incompatibility(invented)!!.contains("no function 'teleport'"))
    }

    @Test
    fun `a provider built before an optional addition stays accepted`() {
        // Written before bot.send learned channelId and packageName, and before message had extra.
        val older =
            moduleSpec("bot") {
                function("send", returns = Type.BOOL) {
                    param("text", Type.STRING)
                    optional("room", Type.STRING)
                }
                event("message") {
                    StandardApi.Bot.event("message")!!.fields.filter { it.name != "extra" }.forEach { field(it.name, it.type) }
                }
            }
        assertTrue(StandardApi.Bot.accepts(older))
        assertFalse(StandardApi.Bot.accepts(older.copy(version = 2)))
    }
}
