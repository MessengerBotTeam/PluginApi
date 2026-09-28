package com.xfl.msgbot.plugin.api.provider

import com.xfl.msgbot.plugin.api.call.Args
import com.xfl.msgbot.plugin.api.call.CallException
import com.xfl.msgbot.plugin.api.call.ErrorCode
import com.xfl.msgbot.plugin.api.schema.Type
import com.xfl.msgbot.plugin.api.standard.StandardApi
import com.xfl.msgbot.plugin.api.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProviderModuleTest {
    @Test
    fun `provide declares and implements in one place`() {
        val module =
            provide("weather") {
                function("forecast", returns = Type.STRING) {
                    param("city", Type.STRING)
                    handle { call -> "Sunny in ${call.args.string("city")}" }
                }
                event("alert") { field("text", Type.STRING) }
            }
        assertEquals(listOf("forecast"), module.spec.functions.map { it.name })
        assertEquals(listOf("alert"), module.spec.events.map { it.name })
        assertEquals(Value.VString("Sunny in Seoul"), module.call(ProviderCall("p", "forecast", Args.of("city" to "Seoul"))))
    }

    @Test
    fun `a function without a handler is a mistake caught at once`() {
        assertFailsWith<IllegalArgumentException> { provide("weather") { function("forecast") {} } }
    }

    @Test
    fun `a source implements the parts of the bot standard it supports`() {
        val module =
            implement(StandardApi.Bot) {
                handle("reply") { true }
                emits("message")
            }
        assertEquals(listOf("reply"), module.spec.functions.map { it.name })
        assertEquals(listOf("message"), module.spec.events.map { it.name })
        assertTrue(StandardApi.Bot.covers(module.spec))
        val e = assertFailsWith<CallException> { module.call(ProviderCall("p", "send", Args.NONE)) }
        assertEquals(ErrorCode.UNKNOWN_FUNCTION, e.code)
        assertFailsWith<IllegalArgumentException> { implement(StandardApi.Bot) { handle("teleport") { } } }
    }
}
