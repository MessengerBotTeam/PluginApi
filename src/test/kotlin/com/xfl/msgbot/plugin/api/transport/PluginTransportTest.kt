/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.transport

import com.xfl.msgbot.plugin.api.bridge.HostBridge
import com.xfl.msgbot.plugin.api.engine.EngineDescriptor
import com.xfl.msgbot.plugin.api.engine.ScriptEngine
import com.xfl.msgbot.plugin.api.value.Value
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginTransportTest {
    /** Stands in for a real engine: on dispatch it "replies", on eval it echoes. */
    private class FakeEngine : ScriptEngine {
        override val descriptor = EngineDescriptor("fake", "Fake", listOf("test"))
        private var bridge: HostBridge? = null

        override fun bindHost(bridge: HostBridge) { this.bridge = bridge }
        var loadedCaps: List<String>? = null

        override fun load(apiLevel: String, capabilities: List<String>, shim: String, userScript: String) {
            loadedCaps = capabilities
        }
        override fun dispatch(event: Value.VObject) {
            val token = (event.entries["replyToken"] as? Value.VString)?.value ?: ""
            val content = (event.entries["content"] as? Value.VString)?.value ?: ""
            bridge?.call("reply", listOf(Value.VString(token), Value.VString("pong:$content")))
        }
        override fun eval(source: String): Value = Value.VString("eval:$source")
        override fun close() = Unit
    }

    @Test
    fun `dispatch drives a host-call round-trip across the transport`() {
        val (hostT, pluginT) = LoopbackTransport.pair()
        val calls = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<String, String>>()
        val latch = CountDownLatch(1)

        val hostBridge =
            HostBridge { method, args ->
                if (method == "reply") {
                    calls += (args[0] as Value.VString).value to (args[1] as Value.VString).value
                    latch.countDown()
                }
                Value.VBool(true)
            }

        val host = RemoteScriptEngine(hostT, EngineDescriptor("remote", "Remote", listOf("test")))
        host.bindHost(hostBridge)
        EngineHost(pluginT, { FakeEngine() })

        host.load("API2", listOf("reply"), "<shim>", "<script>")
        host.dispatch(
            Value.VObject(
                mapOf(
                    "type" to Value.VString("message"),
                    "content" to Value.VString("hi"),
                    "replyToken" to Value.VString("tok-1"),
                ),
            ),
        )

        assertTrue(latch.await(5, TimeUnit.SECONDS), "reply capability should be called over the transport")
        assertTrue(calls.contains("tok-1" to "pong:hi"))
        host.close()
    }

    @Test
    fun `capabilities reach a plugin that supplies its own shim`() {
        val (hostT, pluginT) = LoopbackTransport.pair()
        val engine = FakeEngine()
        val host = RemoteScriptEngine(hostT, EngineDescriptor("remote", "Remote", listOf("lua")))
        // Empty shim: the host has none for this language, so the plugin falls back to its own.
        // Capabilities must still arrive, otherwise that shim cannot gate its API surface.
        EngineHost(pluginT, { engine }, shimProvider = { "-- own shim" })

        host.load("API2", listOf("reply", "log"), "", "<script>")

        val deadline = System.currentTimeMillis() + 5_000
        while (engine.loadedCaps == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(listOf("reply", "log"), engine.loadedCaps)
        host.close()
    }

    @Test
    fun `eval returns a value across the transport`() {
        val (hostT, pluginT) = LoopbackTransport.pair()
        val host = RemoteScriptEngine(hostT, EngineDescriptor("remote", "Remote", listOf("test")))
        EngineHost(pluginT, { FakeEngine() })

        assertEquals("eval:typeof x", (host.eval("typeof x") as Value.VString).value)
        host.close()
    }
}
