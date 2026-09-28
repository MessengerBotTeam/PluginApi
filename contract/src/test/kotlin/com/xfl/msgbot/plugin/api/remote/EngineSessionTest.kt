package com.xfl.msgbot.plugin.api.remote

import com.xfl.msgbot.plugin.api.call.CallResult
import com.xfl.msgbot.plugin.api.engine.EngineContext
import com.xfl.msgbot.plugin.api.engine.EngineException
import com.xfl.msgbot.plugin.api.engine.EngineScheduler
import com.xfl.msgbot.plugin.api.engine.EngineThread
import com.xfl.msgbot.plugin.api.engine.HostBridge
import com.xfl.msgbot.plugin.api.engine.LoadRequest
import com.xfl.msgbot.plugin.api.engine.ProfileScript
import com.xfl.msgbot.plugin.api.engine.ScriptEngine
import com.xfl.msgbot.plugin.api.engine.ScriptEngineFactory
import com.xfl.msgbot.plugin.api.engine.ScriptEvent
import com.xfl.msgbot.plugin.api.rpc.LoopbackTransport
import com.xfl.msgbot.plugin.api.standard.StandardApi
import com.xfl.msgbot.plugin.api.value.Value
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A remote engine behaves like a local one: same requests in, same host calls out. */
class EngineSessionTest {
    private val hostCalls = CopyOnWriteArrayList<Pair<String, Map<String, Value>>>()
    private val errors = CopyOnWriteArrayList<String>()
    private val hostThread = EngineThread("host-engine")
    private val callPool = Executors.newCachedThreadPool()
    private val closers = mutableListOf<AutoCloseable>()

    private val hostContext =
        object : EngineContext {
            override val host =
                object : HostBridge {
                    override fun call(
                        function: String,
                        args: Map<String, Value>,
                    ): CallResult {
                        hostCalls += function to args
                        return when (function) {
                            "log.write" -> CallResult.ok()
                            "slow.wait" -> {
                                Thread.sleep(200)
                                CallResult.ok(Value.VString("slow"))
                            }
                            else -> CallResult.unknownFunction(function)
                        }
                    }

                    override fun callAsync(
                        function: String,
                        args: Map<String, Value>,
                        onResult: (CallResult) -> Unit,
                    ) = error("the remote engine answers on its own pool")
                }
            override val scheduler: EngineScheduler = hostThread

            override fun reportError(
                message: String,
                error: Throwable?,
            ) {
                errors += message
            }
        }

    /** Stands in for a language: understands a few commands in place of real source. */
    private class FakeEngine(private val context: EngineContext) : ScriptEngine {
        var loaded: LoadRequest? = null
        val seen = CopyOnWriteArrayList<String>()

        override fun load(request: LoadRequest) {
            if (request.entrySource == "syntax error") throw EngineException("line 1: unexpected token")
            loaded = request
        }

        override fun dispatch(event: ScriptEvent) {
            seen += event.name
            when (event.name) {
                "bot.message" -> context.host.call("log.write", mapOf("level" to Value.VString("info"), "message" to event.payload.getValue("content")))
                "test.slow" -> context.host.call("slow.wait", emptyMap())
                "test.async" ->
                    context.host.callAsync("log.write", mapOf("level" to Value.VString("async"))) { result ->
                        seen += "async answered on ${Thread.currentThread().name}: $result"
                    }
                "test.throw" -> throw EngineException("handler threw")
            }
        }

        override fun eval(source: String): Value =
            when (source) {
                "loaded" -> Value.of(loaded?.let { listOf(it.language, it.entry, it.options["strict"], it.api.map { spec -> spec.namespace }) })
                "seen" -> Value.of(seen.toList())
                else -> Value.VNull
            }

        override fun close() = Unit
    }

    private fun connect(factory: ScriptEngineFactory = ScriptEngineFactory(::FakeEngine)): RemoteScriptEngine {
        val (hostSide, pluginSide) = LoopbackTransport.pair()
        closers += EngineEndpoint(pluginSide, factory, maxPendingEvents = 2)
        return RemoteScriptEngine.connect(hostSide, hostContext, callPool).also { closers += it }
    }

    private val request =
        LoadRequest(
            language = "fake",
            api = StandardApi.HOST,
            profile = ProfileScript("profile.fake", "profile"),
            entry = "main.fake",
            sources = mapOf("main.fake" to "main", "lib/util.fake" to "util"),
            options = mapOf("strict" to "true"),
        )

    @AfterTest
    fun tearDown() {
        closers.reversed().forEach { it.close() }
        hostThread.shutdownNow()
        callPool.shutdownNow()
    }

    @Test
    fun `the load arrives whole`() {
        val engine = connect()
        engine.load(request)
        assertEquals(Value.of(listOf("fake", "main.fake", "true", StandardApi.HOST.map { it.namespace })), engine.eval("loaded"))
    }

    @Test
    fun `a script that cannot load fails the load with its own message`() {
        val engine = connect()
        val e = assertFailsWith<EngineException> { engine.load(request.copy(sources = mapOf("main.fake" to "syntax error"))) }
        assertTrue(e.message!!.contains("line 1: unexpected token"), e.message)
    }

    @Test
    fun `dispatch returns after the handler's host calls`() {
        val engine = connect()
        engine.load(request)
        engine.dispatch(ScriptEvent("bot.message", mapOf("content" to Value.VString("hi"))))
        assertEquals(listOf("log.write" to mapOf("level" to Value.VString("info"), "message" to Value.VString("hi"))), hostCalls.toList())
    }

    @Test
    fun `a handler failure reaches the dispatcher`() {
        val engine = connect()
        engine.load(request)
        val e = assertFailsWith<EngineException> { engine.dispatch(ScriptEvent("test.throw")) }
        assertTrue(e.message!!.contains("handler threw"))
    }

    @Test
    fun `an asynchronous call answers on the engine thread`() {
        val engine = connect()
        engine.load(request)
        engine.dispatch(ScriptEvent("test.async"))
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && (engine.eval("seen") as Value.VArray).items.size < 2) Thread.sleep(10)
        val answer = (engine.eval("seen") as Value.VArray).items.last()
        assertEquals(Value.VString("async answered on plugin-engine: ${CallResult.ok()}"), answer)
    }

    @Test
    fun `events beyond the queue are refused, not buffered without end`() {
        val engine = connect()
        engine.load(request)
        val refused = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(4)
        repeat(4) {
            Thread {
                try {
                    engine.dispatch(ScriptEvent("test.slow"))
                } catch (e: EngineException) {
                    refused += e.message!!
                } finally {
                    done.countDown()
                }
            }.start()
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertTrue(refused.isNotEmpty() && refused.all { "queue is full" in it }, "$refused")
    }

    @Test
    fun `an engine that cannot start says why on load`() {
        val engine = connect { throw IllegalStateException("no native library") }
        val e = assertFailsWith<EngineException> { engine.load(request) }
        assertTrue(e.message!!.contains("no native library"), e.message)
        val deadline = System.currentTimeMillis() + 5_000
        while (errors.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(errors.single().contains("no native library"))
    }
}
