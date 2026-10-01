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
import com.xfl.msgbot.plugin.api.protocol.ProtocolVersion
import com.xfl.msgbot.plugin.api.rpc.LoopbackTransport
import com.xfl.msgbot.plugin.api.rpc.PluginTransport
import com.xfl.msgbot.plugin.api.standard.StandardApi
import com.xfl.msgbot.plugin.api.value.Value
import com.xfl.msgbot.plugin.api.value.asObjectOrNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EngineSessionTest {
    private val hostCalls = CopyOnWriteArrayList<Pair<String, Map<String, Value>>>()
    private val errors = CopyOnWriteArrayList<String>()
    private val hostThread = EngineThread("host-engine")
    private val callPool = Executors.newCachedThreadPool()

    /** Holds the host's answer to `slow.hang` until the test ends. */
    private val hostHangs = CountDownLatch(1)
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
                            "slow.hang" -> {
                                hostHangs.await(20, TimeUnit.SECONDS)
                                CallResult.ok(Value.VString("too late"))
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

    /** Interprets a few fixed commands instead of real source. */
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
                "test.overflow" -> throw StackOverflowError()
                "test.hostwait" -> {
                    waiting = true
                    val answer = context.host.call("slow.hang", emptyMap())
                    waiting = false
                    if (interrupted) {
                        interrupted = false
                        throw EngineException("interrupted while the host answered $answer")
                    }
                }
                "test.spin" -> {
                    spinning = true
                    while (!interrupted) Thread.onSpinWait()
                    interrupted = false
                    throw EngineException("interrupted")
                }
            }
        }

        @Volatile var spinning = false

        @Volatile var waiting = false

        @Volatile var interrupted = false

        override fun interrupt() {
            if (spinning || waiting) interrupted = true
        }

        override fun eval(source: String): Value =
            when (source) {
                "loaded" -> Value.of(loaded?.let { listOf(it.language, it.entry, it.options["strict"], it.api.map { spec -> spec.namespace }) })
                "seen" -> Value.of(seen.toList())
                else -> Value.VNull
            }

        override fun close() = Unit
    }

    private var pluginSide: PluginTransport? = null

    private fun connect(factory: ScriptEngineFactory = ScriptEngineFactory(::FakeEngine)): RemoteScriptEngine {
        val (hostSide, pluginSide) = LoopbackTransport.pair()
        this.pluginSide = pluginSide
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
        hostHangs.countDown()
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

    @Test
    fun `a handler's Error fails the dispatch and gives its queue slot back`() {
        val engine = connect()
        engine.load(request)
        // Twice the queue size, so a leaked slot would reject a later dispatch.
        repeat(4) {
            val e = assertFailsWith<EngineException> { engine.dispatch(ScriptEvent("test.overflow")) }
            assertTrue(e.message!!.contains("Stack overflow"), e.message)
        }
    }

    @Test
    fun `an interrupt overtakes the queue and stops the running script`() {
        val engine = connect()
        engine.load(request)
        val failure = java.util.concurrent.CompletableFuture<Throwable?>()
        Thread { failure.complete(runCatching { engine.dispatch(ScriptEvent("test.spin")) }.exceptionOrNull()) }.start()
        Thread.sleep(200)
        assertTrue(!failure.isDone, "still spinning")
        engine.interrupt()
        val e = failure.get(5, TimeUnit.SECONDS)
        assertTrue(e is EngineException && e.message!!.contains("interrupted"), "$e")
        engine.load(request)
    }

    @Test
    fun `an interrupt frees a script waiting on the host`() {
        val engine = connect()
        engine.load(request)
        val failure = java.util.concurrent.CompletableFuture<Throwable?>()
        Thread { failure.complete(runCatching { engine.dispatch(ScriptEvent("test.hostwait")) }.exceptionOrNull()) }.start()
        Thread.sleep(200)
        assertTrue(!failure.isDone, "still waiting on the host")
        engine.interrupt()
        val e = failure.get(2, TimeUnit.SECONDS)
        assertTrue(e is EngineException && e.message!!.contains("interrupted"), "$e")
    }

    @Test
    fun `a dispatch fails at once when the plugin is found gone`() {
        val engine = connect()
        engine.load(request)
        val failure = java.util.concurrent.CompletableFuture<Throwable?>()
        Thread { failure.complete(runCatching { engine.dispatch(ScriptEvent("test.spin")) }.exceptionOrNull()) }.start()
        Thread.sleep(200)
        pluginSide!!.close()
        // The interrupt cannot be sent, which shows the plugin is gone.
        engine.interrupt()
        val e = failure.get(1, TimeUnit.SECONDS)
        assertTrue(e is EngineException && e.message!!.contains("closed"), "$e")
        assertEquals(emptyList(), errors.toList())
    }

    @Test
    fun `the two sides agree on the newest protocol both speak`() {
        val offer = { min: Int, max: Int -> Value.of(mapOf("protocol" to max, "minProtocol" to min)) }
        val agreed = Wire.answerHello(offer(ProtocolVersion.MIN_SUPPORTED, ProtocolVersion.CURRENT + 5))
        assertEquals(Value.VInt(ProtocolVersion.CURRENT.toLong()), agreed.getOrThrow().asObjectOrNull()!!["protocol"])
        val tooNew = Wire.answerHello(offer(ProtocolVersion.CURRENT + 1, ProtocolVersion.CURRENT + 5))
        assertEquals("unavailable", (tooNew as CallResult.Err).code)
        assertFailsWith<IllegalStateException> { Wire.checkHello(CallResult.ok(Value.of(mapOf("protocol" to ProtocolVersion.CURRENT + 1)))) }
    }

    @Test
    fun `an event the host stopped waiting for is skipped, not run late`() {
        val (hostSide, pluginSide) = LoopbackTransport.pair()
        closers += EngineEndpoint(pluginSide, ScriptEngineFactory(::FakeEngine))
        val engine = RemoteScriptEngine.connect(hostSide, hostContext, callPool, timeoutMs = 300).also { closers += it }
        engine.load(request)
        val spin = java.util.concurrent.CompletableFuture.runAsync { runCatching { engine.dispatch(ScriptEvent("test.spin")) } }
        Thread.sleep(100)
        // Queued behind the spinning script until the host gives up on it.
        assertFailsWith<EngineException> { engine.dispatch(ScriptEvent("bot.message", mapOf("content" to Value.VString("late")))) }
        Thread.sleep(100)
        engine.interrupt()
        spin.get(5, TimeUnit.SECONDS)
        Thread.sleep(200)
        assertTrue(hostCalls.none { it.first == "log.write" }, "$hostCalls")
    }

    @Test
    fun `a host call the script stopped waiting for is not made late`() {
        val oneThread = Executors.newSingleThreadExecutor()
        val (hostSide, pluginSide) = LoopbackTransport.pair()
        closers += EngineEndpoint(pluginSide, ScriptEngineFactory(::FakeEngine), callTimeoutMs = 300)
        val engine = RemoteScriptEngine.connect(hostSide, hostContext, oneThread).also { closers += it }
        try {
            engine.load(request)
            // Holds the host's only call thread; the script gives up on it after 300 ms.
            engine.dispatch(ScriptEvent("test.hostwait"))
            engine.dispatch(ScriptEvent("bot.message", mapOf("content" to Value.VString("late"))))
            Thread.sleep(100)
            hostHangs.countDown()
            Thread.sleep(300)
            assertTrue(hostCalls.none { it.first == "log.write" }, "$hostCalls")
        } finally {
            oneThread.shutdownNow()
        }
    }
}
