package com.xfl.msgbot.plugin.api.schema

import com.xfl.msgbot.plugin.api.standard.StandardApi
import com.xfl.msgbot.plugin.api.value.Value
import com.xfl.msgbot.plugin.api.value.vObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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
    fun `a partial implementation of a standard fits, a changed signature does not`() {
        val part = StandardApi.Bot.restrictTo(listOf("reply"), listOf("message"))
        assertEquals(listOf("reply"), part.functions.map { it.name })
        assertEquals(Fit.Accepted(part, emptyList()), StandardApi.Bot.fit(part))

        val forged = moduleSpec("bot") { function("image", returns = Type.BYTES.nullable()) }
        assertEquals(Fit.Refused("bot.image leaves out the required parameter 'token'"), StandardApi.Bot.fit(forged))
        val retyped = moduleSpec("bot") { function("reply", returns = Type.STRING) { param("text", Type.STRING) } }
        assertTrue((StandardApi.Bot.fit(retyped) as Fit.Refused).reason.contains("answers string"))
        // The host may leave room out, so a provider cannot insist on it.
        val demanding =
            moduleSpec("bot") {
                function("send", returns = Type.BOOL) {
                    param("text", Type.STRING)
                    param("room", Type.STRING)
                }
            }
        assertIs<Fit.Refused>(StandardApi.Bot.fit(demanding))
    }

    @Test
    fun `a provider built against an older edition fits`() {
        // Written before send learned channelId, packageName and extra, and before message had extra.
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
        val fit = assertIs<Fit.Accepted>(StandardApi.Bot.fit(older))
        assertEquals(emptyList(), fit.ignored)
        assertEquals(listOf("text", "room"), fit.spec.function("send")!!.params.map { it.name })
        assertIs<Fit.Refused>(StandardApi.Bot.fit(older.copy(version = 2)))
    }

    @Test
    fun `a provider built against a newer edition fits, without what this one does not know`() {
        val newer =
            moduleSpec("bot") {
                function("send", returns = Type.BOOL) {
                    param("text", Type.STRING)
                    optional("room", Type.STRING)
                    optional("silent", Type.BOOL)
                }
                function("edit", returns = Type.BOOL) {
                    param("logId", Type.STRING)
                    param("text", Type.STRING)
                }
                event("message") {
                    field("room", Type.STRING)
                    field("content", Type.STRING)
                    field(
                        "author",
                        Type.struct {
                            field("name", Type.STRING)
                            optional("nickname", Type.STRING)
                        },
                    )
                    optional("thread", Type.STRING)
                }
            }
        val fit = assertIs<Fit.Accepted>(StandardApi.Bot.fit(newer))
        assertEquals(listOf("bot.send(silent)", "bot.edit", "bot.message.thread"), fit.ignored)
        assertEquals(listOf("send"), fit.spec.functions.map { it.name })
        assertEquals(listOf("text", "room"), fit.spec.function("send")!!.params.map { it.name })

        val message = fit.spec.event("message")!!
        val payload =
            mapOf(
                "room" to Value.VString("r"),
                "content" to Value.VString("c"),
                "author" to vObject("name" to "a", "nickname" to "n"),
                "thread" to Value.VString("t"),
            )
        val conformed = message.conform(payload)
        assertEquals(mapOf("room" to Value.VString("r"), "content" to Value.VString("c"), "author" to vObject("name" to "a")), conformed)
        assertNull(message.checkPayload(conformed))

        val requiresUnknown =
            moduleSpec("bot") {
                function("send", returns = Type.BOOL) {
                    param("text", Type.STRING)
                    param("priority", Type.INT)
                }
            }
        assertIs<Fit.Refused>(StandardApi.Bot.fit(requiresUnknown))
    }
}
