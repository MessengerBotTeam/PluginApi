/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.tck

import com.xfl.msgbot.plugin.api.provider.Provider
import com.xfl.msgbot.plugin.api.provider.ProviderContext
import com.xfl.msgbot.plugin.api.provider.emit
import com.xfl.msgbot.plugin.api.provider.implement
import com.xfl.msgbot.plugin.api.standard.StandardApi
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** The conformance suite has to catch a provider that keeps emitting after it is stopped. */
class LateEmitterTest {
    /** Forgets to stop its receiver: it keeps the context it was started with. */
    private class LateEmitter : Provider {
        @Volatile var context: ProviderContext? = null

        override val modules =
            listOf(
                implement(StandardApi.Bot) {
                    handle("send") { true }
                    emits("message")
                },
            )

        override fun start(context: ProviderContext) {
            this.context = context
        }
    }

    @Test
    fun `emitting after stop fails the check`() {
        // Anonymous, so the test runner does not run the failing suite on its own.
        val suite =
            object : ProviderConformance() {
                override fun createProvider(): Provider = LateEmitter()

                override fun provokeEvents(provider: Provider) {
                    (provider as LateEmitter).context?.emit("bot.message", "room" to "r", "content" to "late", "author" to mapOf("name" to "a"))
                }
            }
        suite.openHarness()
        try {
            assertFailsWith<AssertionError> { suite.nothingIsEmittedOnceStopped() }
        } finally {
            suite.closeHarness()
        }
    }
}
