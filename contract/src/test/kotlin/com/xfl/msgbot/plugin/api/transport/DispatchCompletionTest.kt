package com.xfl.msgbot.plugin.api.transport

import com.xfl.msgbot.plugin.api.bridge.CallResult
import com.xfl.msgbot.plugin.api.bridge.HostBridge
import com.xfl.msgbot.plugin.api.engine.EngineDescriptor
import com.xfl.msgbot.plugin.api.engine.ScriptEngine
import com.xfl.msgbot.plugin.api.value.Value
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DispatchCompletionTest {
    private open class Engine : ScriptEngine {
        override val descriptor = EngineDescriptor("fake", "Fake", listOf("fake"))
        lateinit var bridge: HostBridge
        override fun bindHost(bridge: HostBridge) { this.bridge = bridge }
        override fun load(language: String, capabilities: List<String>, shim: String, userScript: String, options: Map<String, String>) = Unit
        override fun dispatch(event: Value.VObject) = Unit
        override fun eval(source: String): Value = Value.VNull
        override fun close() = Unit
    }

    @Test fun `dispatch completion has a deadline when the remote listener stalls`() {
        val (hostT, pluginT) = LoopbackTransport.pair()
        val release = CountDownLatch(1)
        val engine = object : Engine() {
            override fun dispatch(event: Value.VObject) { release.await(3, TimeUnit.SECONDS) }
        }
        val endpoint = EngineHost(pluginT, { engine })
        val remote = RemoteScriptEngine(hostT, engine.descriptor, callTimeoutMs = 100)
        try {
            val error = assertFailsWith<IllegalStateException> { remote.dispatch(Value.VObject(emptyMap())) }
            assertTrue(error.message.orEmpty().contains("within 100ms"))
        } finally {
            remote.close()
            release.countDown()
            endpoint.close()
        }
    }

    @Test fun `shutdown dispatch finishes its storage call before close`() {
        val (hostT, pluginT) = LoopbackTransport.pair()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val saved = CountDownLatch(1)
        val engine = object : Engine() {
            override fun dispatch(event: Value.VObject) {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                assertIs<CallResult.Ok>(bridge.call("file.write", listOf(Value.VString("saved"))))
            }
            override fun close() { closed.countDown() }
        }
        val endpoint = EngineHost(pluginT, { engine })
        val remote = RemoteScriptEngine(hostT, engine.descriptor, callTimeoutMs = 3000)
        remote.bindHost { method, _ ->
            assertEquals("file.write", method)
            saved.countDown()
            CallResult.of(Value.VNull)
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val completion = executor.submit {
                remote.dispatch(Value.VObject(mapOf("type" to Value.VString("startCompile"))))
                remote.close()
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertFalse(completion.isDone)
            release.countDown()
            completion.get(3, TimeUnit.SECONDS)
            assertEquals(0L, saved.count)
            assertTrue(closed.await(3, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            endpoint.close()
            executor.shutdownNow()
        }
    }

    @Test fun `slow engine rejects excess events instead of retaining an unbounded queue`() {
        val (hostT, pluginT) = LoopbackTransport.pair()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val replies = LinkedBlockingQueue<PluginProtocol.Frame.DispatchResult>()
        hostT.setListener { bytes ->
            (PluginProtocol.decode(bytes) as? PluginProtocol.Frame.DispatchResult)?.let(replies::add)
        }
        val engine = object : Engine() {
            override fun dispatch(event: Value.VObject) {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
            }
        }
        val endpoint = EngineHost(pluginT, { engine }, maxPendingEvents = 2)
        try {
            hostT.send(PluginProtocol.encode(PluginProtocol.Frame.Dispatch(Value.VObject(emptyMap()), 1)))
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            for (id in 2L..4L) hostT.send(PluginProtocol.encode(PluginProtocol.Frame.Dispatch(Value.VObject(emptyMap()), id)))
            val rejected = List(2) { assertNotNull(replies.poll(3, TimeUnit.SECONDS)) }
            assertEquals(setOf(3L, 4L), rejected.map { it.id }.toSet())
            rejected.forEach { assertIs<CallResult.Err>(it.result) }
            release.countDown()
            val completed = List(2) { assertNotNull(replies.poll(3, TimeUnit.SECONDS)) }
            assertEquals(setOf(1L, 2L), completed.map { it.id }.toSet())
        } finally {
            release.countDown()
            endpoint.close()
            hostT.close()
        }
    }
}
