package com.xfl.msgbot.plugin.api.rpc

import com.xfl.msgbot.plugin.api.call.CallResult
import com.xfl.msgbot.plugin.api.call.ErrorCode
import com.xfl.msgbot.plugin.api.serialization.BytesChannel
import com.xfl.msgbot.plugin.api.value.Value
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RpcPeerTest {
    private val transports = LoopbackTransport.pair()
    private val hostSide = transports.first
    private val pluginSide = transports.second
    private val notes = CompletableFuture<Pair<String, Value>>()

    private val echo =
        object : RpcHandler {
            override fun onRequest(
                method: String,
                params: Value,
                reply: (CallResult) -> Unit,
            ) = when (method) {
                "echo" -> reply(CallResult.ok(params))
                "fail" -> reply(CallResult.badArgs("no"))
                "later" -> Thread { Thread.sleep(50); reply(CallResult.ok(Value.VString("done"))) }.start()
                "never" -> Unit
                "throw" -> throw IllegalStateException("boom")
                "overflow" -> throw StackOverflowError()
                "deep" -> reply(CallResult.ok((1..100).fold<Int, Value>(Value.VNull) { inner, _ -> Value.VArray(listOf(inner)) }))
                else -> reply(CallResult.failed("unknown"))
            }

            override fun onNotify(
                method: String,
                params: Value,
            ) {
                notes.complete(method to params)
            }
        }

    private val host = RpcPeer(hostSide, echo)
    private val plugin = RpcPeer(pluginSide, echo)

    @AfterTest
    fun close() {
        host.close()
        plugin.close()
    }

    @Test
    fun `both sides ask and answer`() {
        assertEquals(CallResult.ok(Value.VInt(1)), host.request("echo", Value.VInt(1), 5_000))
        assertEquals(CallResult.ok(Value.VInt(2)), plugin.request("echo", Value.VInt(2), 5_000))
    }

    @Test
    fun `errors keep their code`() {
        assertEquals(CallResult.badArgs("no"), host.request("fail", Value.VNull, 5_000))
        assertEquals(CallResult.failed("boom"), host.request("throw", Value.VNull, 5_000))
    }

    @Test
    fun `an answer may come later from another thread`() {
        val answer = CompletableFuture<CallResult>()
        host.requestAsync("later", Value.VNull, 5_000, answer::complete)
        assertEquals(CallResult.ok(Value.VString("done")), answer.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `silence becomes unavailable after the timeout`() {
        val result = host.request("never", Value.VNull, 100)
        assertIs<CallResult.Err>(result)
        assertEquals(ErrorCode.UNAVAILABLE, result.code)
    }

    @Test
    fun `closing fails whatever is still waiting`() {
        val answer = CompletableFuture<CallResult>()
        host.requestAsync("never", Value.VNull, 0, answer::complete)
        host.close()
        assertEquals(ErrorCode.UNAVAILABLE, (answer.get(5, TimeUnit.SECONDS) as CallResult.Err).code)
    }

    @Test
    fun `a peer whose other side is gone fails what waits at once`() {
        val waiting = CompletableFuture<CallResult>()
        host.requestAsync("never", timeoutMs = 10_000, onResult = waiting::complete)
        pluginSide.close()
        val started = System.nanoTime()
        val answer = host.request("echo", timeoutMs = 10_000)
        assertEquals(ErrorCode.UNAVAILABLE, (answer as CallResult.Err).code)
        assertEquals(ErrorCode.UNAVAILABLE, (waiting.get(1, TimeUnit.SECONDS) as CallResult.Err).code)
        assertTrue(System.nanoTime() - started < 1_000_000_000L, "failed at once")
        assertTrue(host.isClosed)
    }

    @Test
    fun `notifications arrive without an answer`() {
        plugin.notify("hi", Value.VString("there"))
        assertEquals("hi" to Value.VString("there"), notes.get(5, TimeUnit.SECONDS))
    }

    /** Mimics Binder: a per-frame size limit plus a side channel for out-of-band data. */
    private class LimitedTransport(
        private val inner: PluginTransport,
        private val store: ConcurrentHashMap<Long, ByteArray>,
        private val ids: AtomicLong,
    ) : PluginTransport by inner {
        val largest = AtomicLong()

        override val bytes =
            object : BytesChannel {
                override fun offload(bytes: ByteArray): Long? = if (bytes.size < LIMIT) null else ids.incrementAndGet().also { store[it] = bytes }

                override fun resolve(
                    transferId: Long,
                    length: Int,
                ): ByteArray = store.remove(transferId)!!
            }

        override fun send(frame: ByteArray) {
            check(frame.size <= LIMIT) { "A ${frame.size}-byte transaction is too large" }
            largest.accumulateAndGet(frame.size.toLong(), ::maxOf)
            inner.send(frame)
        }

        companion object {
            const val LIMIT = 64 * 1024
        }
    }

    @Test
    fun `a frame larger than a transaction travels out of band, however its values are split`() {
        val (hostEnd, pluginEnd) = LoopbackTransport.pair()
        val store = ConcurrentHashMap<Long, ByteArray>()
        val ids = AtomicLong()
        val limitedHost = LimitedTransport(hostEnd, store, ids)
        val limitedPlugin = LimitedTransport(pluginEnd, store, ids)
        val asker = RpcPeer(limitedHost, echo)
        val answerer = RpcPeer(limitedPlugin, echo)
        try {
            // Many small values that together exceed the limit.
            val sources = Value.VObject((1..400).associate { "file$it.js" to Value.VString("x".repeat(1_000)) })
            assertEquals(CallResult.ok(sources), asker.request("echo", sources, 5_000))
            assertEquals(CallResult.ok(Value.VString("y".repeat(300_000))), asker.request("echo", Value.VString("y".repeat(300_000)), 5_000))
            assertEquals(true, limitedHost.largest.get() < LimitedTransport.LIMIT && limitedPlugin.largest.get() < LimitedTransport.LIMIT)
            assertEquals(emptyMap(), store.toMap())
        } finally {
            asker.close()
            answerer.close()
        }
    }

    @Test
    fun `a handler's Error is answered, not thrown at the transport`() {
        val answer = host.request("overflow", timeoutMs = 2_000)
        assertEquals(ErrorCode.FAILED, (answer as CallResult.Err).code)
    }

    @Test
    fun `an answer that cannot travel is answered with why, not left to time out`() {
        val started = System.nanoTime()
        val answer = host.request("deep", timeoutMs = 10_000)
        assertTrue((answer as CallResult.Err).message.contains("could not be sent"), answer.message)
        assertTrue(System.nanoTime() - started < 5_000_000_000L, "answered at once")
    }

    /** Mimics a full one-way Binder buffer: refuses the next [refusals] frames, whatever their size. */
    private class CrowdedTransport(
        private val inner: PluginTransport,
        @Volatile var refusals: Int,
    ) : PluginTransport by inner {
        private val store = ConcurrentHashMap<Long, ByteArray>()
        private val ids = AtomicLong()
        val refused = AtomicLong()

        override val bytes =
            object : BytesChannel {
                override fun offload(bytes: ByteArray): Long? = null

                override fun offloadFrame(frame: ByteArray): Long = ids.incrementAndGet().also { store[it] = frame }

                override fun resolve(
                    transferId: Long,
                    length: Int,
                ): ByteArray = store.remove(transferId)!!
            }

        override fun send(frame: ByteArray) {
            if (refusals > 0) {
                refusals--
                refused.incrementAndGet()
                throw IllegalStateException("no async space left")
            }
            inner.send(frame)
        }
    }

    @Test
    fun `a frame the transport refuses is sent again out of band`() {
        val (hostEnd, pluginEnd) = LoopbackTransport.pair()
        // The plugin's answers share its channel, so both ends resolve from one store.
        val crowdedHost = CrowdedTransport(hostEnd, refusals = 0)
        val crowdedPlugin = CrowdedTransport(pluginEnd, refusals = 0)
        val asker = RpcPeer(crowdedHost, echo)
        val answerer =
            RpcPeer(
                object : PluginTransport by crowdedPlugin {
                    override val bytes = crowdedHost.bytes
                },
                echo,
            )
        try {
            crowdedHost.refusals = 2
            val message = Value.VString("카톡".repeat(2_000))
            assertEquals(CallResult.ok(message), asker.request("echo", message, 5_000))
            assertEquals(2L, crowdedHost.refused.get())
        } finally {
            asker.close()
            answerer.close()
        }
    }
}
