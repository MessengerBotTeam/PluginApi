/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.transport

import com.xfl.msgbot.plugin.api.bridge.CallResult
import com.xfl.msgbot.plugin.api.bridge.HostBridge
import com.xfl.msgbot.plugin.api.engine.EngineDescriptor
import com.xfl.msgbot.plugin.api.engine.PollingScriptEngine
import com.xfl.msgbot.plugin.api.engine.ScriptEngine
import com.xfl.msgbot.plugin.api.value.Value
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PluginTransportTest {
    @Test
    fun `event loop polling runs on the engine thread without a host event`() {
        val (hostT, pluginT) = LoopbackTransport.pair()
        val called = CountDownLatch(1)
        val threads = AtomicReference<Pair<Thread, Thread>>()
        val engine = object : PollingScriptEngine {
            override val descriptor = EngineDescriptor("async", "Async", listOf("javascript"))
            private lateinit var bridge: HostBridge
            private lateinit var loadThread: Thread

            override fun bindHost(bridge: HostBridge) { this.bridge = bridge }
            override fun load(language: String, capabilities: List<String>, shim: String, userScript: String, options: Map<String, String>) {
                loadThread = Thread.currentThread()
            }
            override fun poll() {
                threads.set(loadThread to Thread.currentThread())
                bridge.call("async.done", emptyList())
            }
            override fun eval(source: String): Value = Value.VNull
            override fun dispatch(event: Value.VObject) = Unit
            override fun close() = Unit
        }
        val host = RemoteScriptEngine(hostT, engine.descriptor)
        host.bindHost { method, _ ->
            if (method == "async.done") called.countDown()
            CallResult.of(Value.VNull)
        }
        EngineHost(pluginT, { engine }, pollIntervalMs = 10)

        try {
            host.load("javascript", listOf("async.done"), "<shim>", "<script>")
            assertTrue(called.await(5, TimeUnit.SECONDS), "poll must run without dispatch")
            assertEquals(threads.get().first, threads.get().second)
        } finally {
            host.close()
        }
    }

    /** Stands in for a real engine: on dispatch it "replies", on eval it echoes. */
    private class FakeEngine : ScriptEngine {
        override val descriptor = EngineDescriptor("fake", "Fake", listOf("test"))
        private var bridge: HostBridge? = null

        override fun bindHost(bridge: HostBridge) { this.bridge = bridge }
        var loadedCaps: List<String>? = null
        var loadedShim: String? = null
        var loadedLanguage: String? = null

        @Volatile var loadedOptions: Map<String, String>? = null

        /** What the host answered, as the engine saw it after the round trip. */
        @Volatile var lastResult: CallResult? = null

        override fun load(
            language: String,
            capabilities: List<String>,
            shim: String,
            userScript: String,
            options: Map<String, String>,
        ) {
            loadedLanguage = language
            loadedCaps = capabilities
            loadedShim = shim
            loadedOptions = options
        }
        override fun dispatch(event: Value.VObject) {
            val token = (event.entries["replyToken"] as? Value.VString)?.value ?: ""
            val content = (event.entries["content"] as? Value.VString)?.value ?: ""
            lastResult = bridge?.call("reply", listOf(Value.VString(token), Value.VString("pong:$content")))
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
                CallResult.of(Value.VBool(true))
            }

        val host = RemoteScriptEngine(hostT, EngineDescriptor("remote", "Remote", listOf("test")))
        host.bindHost(hostBridge)
        EngineHost(pluginT, { FakeEngine() })

        host.load("test", listOf("reply"), "<shim>", "<script>")
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
    fun `a failure survives the round trip instead of arriving as an empty value`() {
        val (hostT, pluginT) = LoopbackTransport.pair()
        val engine = FakeEngine()

        val host = RemoteScriptEngine(hostT, EngineDescriptor("remote", "Remote", listOf("test")))
        host.bindHost { _, _ -> CallResult.Err(CallResult.Code.FAILED, "no network") }
        EngineHost(pluginT, { engine })

        host.load("test", listOf("reply"), "<shim>", "<script>")
        host.dispatch(Value.VObject(mapOf("content" to Value.VString("hi"))))

        val result = awaitResult(engine)
        assertIs<CallResult.Err>(result, "the engine should see the failure, not a value")
        assertEquals(CallResult.Code.FAILED, result.code)
        // The message is the only part that says what went wrong, so it has to cross too.
        assertEquals("no network", result.message)
        host.close()
    }

    @Test
    fun `a bridge that throws becomes a failure rather than a hung engine`() {
        val (hostT, pluginT) = LoopbackTransport.pair()
        val engine = FakeEngine()

        val host = RemoteScriptEngine(hostT, EngineDescriptor("remote", "Remote", listOf("test")))
        host.bindHost { _, _ -> throw IllegalStateException("backend exploded") }
        EngineHost(pluginT, { engine })

        host.load("test", listOf("reply"), "<shim>", "<script>")
        host.dispatch(Value.VObject(mapOf("content" to Value.VString("hi"))))

        // Without the host answering at all, this would block for the full call timeout.
        val result = awaitResult(engine)
        assertIs<CallResult.Err>(result, "an exception on the host side still owes the script an answer")
        assertTrue("backend exploded" in result.message, "the cause should reach the script: ${result.message}")
        host.close()
    }

    @Test
    fun `the settings a plugin declared reach it, and the host never reads them`() {
        val (hostT, pluginT) = LoopbackTransport.pair()
        val engine = FakeEngine()
        val host = RemoteScriptEngine(hostT, EngineDescriptor("remote", "Remote", listOf("test")))
        EngineHost(pluginT, { engine })

        // Keys the host has never heard of: that is the point. A closed type here would put the
        // host back in the business of knowing what every engine can be told.
        host.load("test", emptyList(), "<shim>", "<script>", mapOf("npm" to "true", "registry" to "https://x"))

        val deadline = System.currentTimeMillis() + 5_000
        while (engine.loadedOptions == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(mapOf("npm" to "true", "registry" to "https://x"), engine.loadedOptions)
        host.close()
    }

    @Test
    fun `an engine that cannot load says so instead of going quiet`() {
        val (hostT, pluginT) = LoopbackTransport.pair()
        val refusing =
            object : ScriptEngine by FakeEngine() {
                override fun load(
                    language: String,
                    capabilities: List<String>,
                    shim: String,
                    userScript: String,
                    options: Map<String, String>,
                ): Unit = throw IllegalStateException("bad profile shim")
            }

        val reported = LinkedBlockingQueue<String>()
        EngineHost(pluginT, { refusing }, onError = { reported.put(it) })
        val host = RemoteScriptEngine(hostT, EngineDescriptor("remote", "Remote", listOf("test")))

        // A profile can supply invalid source. The engine must answer the load while the host can
        // still report the compile failure to the user.
        val started = System.currentTimeMillis()
        val error = runCatching { host.load("test", emptyList(), "<bad shim>", "<script>") }.exceptionOrNull()
        val elapsed = System.currentTimeMillis() - started

        assertTrue(error?.message?.contains("bad profile shim") == true, "the load call itself should fail, got: ${error?.message}")
        assertTrue(elapsed < 5_000, "the refusal should come as an answer, not a timeout; took ${elapsed}ms")

        val message = reported.poll(5, TimeUnit.SECONDS)
        assertTrue(message?.contains("bad profile shim") == true, "the plugin's own log should hear it too, got: $message")
    }

    @Test
    fun `a dispatch failure crosses back to the host instead of staying in the plugin's log`() {
        val (hostT, pluginT) = LoopbackTransport.pair()
        val exploding =
            object : ScriptEngine by FakeEngine() {
                override fun dispatch(event: Value.VObject): Unit = throw IllegalStateException("listener blew up")
            }
        EngineHost(pluginT, { exploding })

        val heard = LinkedBlockingQueue<String>()
        val host =
            RemoteScriptEngine(hostT, EngineDescriptor("remote", "Remote", listOf("test")), onError = { heard.put(it) })

        host.load("test", emptyList(), "<shim>", "<script>")
        val message = runCatching { host.dispatch(Value.VObject(mapOf("type" to Value.VString("message")))) }.exceptionOrNull()?.message
        assertTrue(message?.contains("listener blew up") == true, "the host should hear why, got: $message")
        host.close()
    }

    @Test
    fun `an eval the engine cannot run fails at once instead of timing out`() {
        val (hostT, pluginT) = LoopbackTransport.pair()
        val exploding =
            object : ScriptEngine by FakeEngine() {
                override fun eval(source: String): Value = throw IllegalStateException("syntax error near 'end'")
            }
        EngineHost(pluginT, { exploding })
        // Short, so a regression here fails the test rather than stalling it for the real timeout.
        val host = RemoteScriptEngine(hostT, EngineDescriptor("remote", "Remote", listOf("test")), callTimeoutMs = 2_000)

        val started = System.currentTimeMillis()
        val error = runCatching { host.eval("bad source") }.exceptionOrNull()
        val elapsed = System.currentTimeMillis() - started

        // Nobody answered used to mean nobody ever would: the engine threw, no EvalResult was sent,
        // and the caller sat on the timeout to be told only that time had passed.
        assertTrue(elapsed < 2_000, "should fail immediately, took ${elapsed}ms")
        assertTrue(
            error?.message?.contains("syntax error near 'end'") == true,
            "the engine's reason should reach the caller, got: ${error?.message}",
        )
        host.close()
    }

    @Test
    fun `a frame arriving after close is dropped, not a crash`() {
        val (hostT, pluginT) = LoopbackTransport.pair()
        val pluginSide = EngineHost(pluginT, { FakeEngine() })
        // Short timeout: the load below has nobody left to answer it and must only time out.
        val host = RemoteScriptEngine(hostT, EngineDescriptor("remote", "Remote", listOf("test")), callTimeoutMs = 500)

        // The plugin side is torn down while the host still has frames to send: its executor is
        // shut down, and a dispatch that lands after would submit onto it and throw
        // RejectedExecutionException out of the transport callback -- a crash on a device.
        pluginSide.close()
        runCatching { host.dispatch(Value.VObject(mapOf("type" to Value.VString("message")))) }
        runCatching { host.load("test", emptyList(), "<shim>", "<script>") }
        Thread.sleep(100)

        // Getting here at all is the assertion: the late frames were dropped instead of thrown.
        host.close()
    }

    @Test
    fun `closing twice is the ordinary path and not a crash`() {
        val (hostT, pluginT) = LoopbackTransport.pair()
        val pluginSide = EngineHost(pluginT, { FakeEngine() })
        val host = RemoteScriptEngine(hostT, EngineDescriptor("remote", "Remote", listOf("test")))

        // An orderly shutdown does both: the host sends Close, and then the service that owns the
        // EngineHost is destroyed and closes it again. The second one used to be a
        // RejectedExecutionException thrown out of onDestroy, which Android turns into a crash of
        // the plugin process -- after every single session.
        host.close()
        Thread.sleep(100)

        pluginSide.close()
    }

    private fun awaitResult(engine: FakeEngine): CallResult? {
        val deadline = System.currentTimeMillis() + 5_000
        while (engine.lastResult == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return engine.lastResult
    }

    @Test
    fun `capabilities reach an engine alongside a profile shim`() {
        val (hostT, pluginT) = LoopbackTransport.pair()
        val engine = FakeEngine()
        val host = RemoteScriptEngine(hostT, EngineDescriptor("remote", "Remote", listOf("lua")))
        EngineHost(pluginT, { engine })

        host.load("lua", listOf("reply", "log"), "-- profile shim", "<script>")

        val deadline = System.currentTimeMillis() + 5_000
        while (engine.loadedCaps == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(listOf("reply", "log"), engine.loadedCaps)
        host.close()
    }

    @Test
    fun `a polyglot plugin is told which language it was handed`() {
        val (hostT, pluginT) = LoopbackTransport.pair()
        val engine = FakeEngine()
        val host = RemoteScriptEngine(hostT, EngineDescriptor("remote", "Remote", listOf("javascript", "python")))
        EngineHost(pluginT, { engine })

        host.load("python", listOf("reply"), "# shim for python", "print()")

        val deadline = System.currentTimeMillis() + 5_000
        while (engine.loadedShim == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("python", engine.loadedLanguage)
        assertEquals("# shim for python", engine.loadedShim)
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
