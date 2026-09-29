/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.tck

import com.xfl.msgbot.plugin.api.provider.Provider
import com.xfl.msgbot.plugin.api.provider.ProviderContext
import com.xfl.msgbot.plugin.api.provider.emit
import com.xfl.msgbot.plugin.api.provider.implement
import com.xfl.msgbot.plugin.api.provider.provide
import com.xfl.msgbot.plugin.api.schema.Type
import com.xfl.msgbot.plugin.api.standard.StandardApi
import com.xfl.msgbot.plugin.api.value.Value
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals

class ExampleProviderConformanceTest : ProviderConformance() {
    private class Echo : Provider {
        @Volatile var context: ProviderContext? = null

        override val modules =
            listOf(
                implement(StandardApi.Bot) {
                    handle("send") { true }
                    emits("message")
                },
                provide("echo") {
                    function("later", returns = Type.STRING) {
                        param("text", Type.STRING)
                        handleAsync { call -> CompletableFuture.supplyAsync { call.args.string("text") } }
                    }
                },
            )

        override fun start(context: ProviderContext) {
            this.context = context
        }

        override fun stop() {
            context = null
        }

        fun receive(text: String) {
            context?.emit("bot.message", "room" to "r", "content" to text, "author" to mapOf("name" to "a"))
        }
    }

    override fun createProvider(): Provider = Echo()

    override fun sampleCalls() =
        listOf(
            SampleCall("bot.send", mapOf("text" to Value.VString("hi"))),
            SampleCall("echo.later", mapOf("text" to Value.VString("hi"))) { assertEquals(Value.VString("hi"), it) },
        )

    override fun provokeEvents(provider: Provider) = (provider as Echo).receive("hello")

    override val expectedEvents = setOf("bot.message")
}
