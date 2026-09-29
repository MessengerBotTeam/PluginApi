package com.xfl.msgbot.plugin.api.schema

import com.xfl.msgbot.plugin.api.call.Args
import com.xfl.msgbot.plugin.api.provider.ProviderCall
import com.xfl.msgbot.plugin.api.provider.fittedTo
import com.xfl.msgbot.plugin.api.provider.implement
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
        assertEquals(emptyList<String>(), fit.ignored)
        // Scripts see this edition's signature whoever answers; the provider hears what it knows.
        assertEquals(StandardApi.Bot.function("send"), fit.spec.function("send"))
        assertEquals(setOf("text", "room"), fit.accepts["send"])
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
        assertEquals(StandardApi.Bot.function("send"), fit.spec.function("send"))
        assertEquals(setOf("text", "room"), fit.accepts["send"])

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

    @Test
    fun `a fitted module passes a provider only the arguments it understands`() {
        val heard = mutableListOf<Map<String, Value>>()
        val older =
            implement(moduleSpec("bot") {
                function("reply", returns = Type.BOOL) {
                    param("text", Type.STRING)
                    optional("room", Type.STRING)
                }
            }) {
                handle("reply") { call ->
                    heard += call.args.values
                    true
                }
            }
        val fit = assertIs<Fit.Accepted>(StandardApi.Bot.fit(older.spec))
        val published = older.fittedTo(fit)
        val args = mapOf("text" to Value.VString("hi"), "token" to Value.VString("t1"), "room" to Value.VString("r"))
        assertNull(published.spec.function("reply")!!.checkArgs(args), "a script may pass the whole address")
        assertEquals<Value>(Value.TRUE, published.call(ProviderCall("p", "reply", Args(args))))
        assertEquals<List<Map<String, Value>>>(listOf(mapOf("text" to Value.VString("hi"), "room" to Value.VString("r"))), heard)
    }

    @Test
    fun `a spec is read member by member, leaving out what this side cannot read`() {
        val value = weather.toValue()
        val functions = (value.entries.getValue("functions") as Value.VArray).items
        val later = vObject("name" to "radar", "params" to emptyList<Any>(), "returns" to "tuple<int, int>")
        val twice = functions.first()
        val read = ModuleSpec.read(Value.VObject(value.entries + ("functions" to Value.VArray(functions + later + twice))))
        assertEquals(weather, read.spec)
        assertEquals(2, read.skipped.size, "${read.skipped}")
        assertTrue(read.skipped.first().startsWith("weather.radar: "), read.skipped.first())
        assertTrue(read.skipped.last().contains("declared twice"), read.skipped.last())
    }
}
