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
    fun `a partial implementation is covered by the standard it came from`() {
        val part = StandardApi.Bot.restrictTo(listOf("reply"), listOf("message"))
        assertEquals(listOf("reply"), part.functions.map { it.name })
        assertTrue(StandardApi.Bot.covers(part))

        val forged = moduleSpec("bot") { function("reply", returns = Type.BOOL) { param("text", Type.STRING) } }
        assertFalse(StandardApi.Bot.covers(forged))
    }
}
