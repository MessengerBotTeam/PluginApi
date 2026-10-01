package com.xfl.msgbot.plugin.api.remote

import com.xfl.msgbot.plugin.api.call.Args
import com.xfl.msgbot.plugin.api.call.CallException
import com.xfl.msgbot.plugin.api.call.CallResult
import com.xfl.msgbot.plugin.api.call.ErrorCode
import com.xfl.msgbot.plugin.api.provider.Provider
import com.xfl.msgbot.plugin.api.provider.ProviderCall
import com.xfl.msgbot.plugin.api.provider.ProviderContext
import com.xfl.msgbot.plugin.api.provider.emit
import com.xfl.msgbot.plugin.api.provider.implement
import com.xfl.msgbot.plugin.api.provider.provide
import com.xfl.msgbot.plugin.api.rpc.LoopbackTransport
import com.xfl.msgbot.plugin.api.rpc.RpcHandler
import com.xfl.msgbot.plugin.api.rpc.RpcPeer
import com.xfl.msgbot.plugin.api.schema.Type
import com.xfl.msgbot.plugin.api.standard.StandardApi
import com.xfl.msgbot.plugin.api.value.Value
import com.xfl.msgbot.plugin.api.value.asLongOrNull
import com.xfl.msgbot.plugin.api.value.asObjectOrNull
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProviderSessionTest {
    private class Weather : Provider {
        var context: ProviderContext? = null
        val pending = CompletableFuture<String>()
        val waiting = CountDownLatch(1)
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val counted = AtomicInteger()
        val emitFailures = CopyOnWriteArrayList<String>()

        override val modules =
            listOf(
                provide("weather") {
                function("forecast", returns = Type.STRING) {
                    param("city", Type.STRING)
                    optional("days", Type.INT)
                    handle { call ->
                        val unit = call.options["unit"] ?: "C"
                        "${call.args.string("city")}: 20$unit x${call.args.intOrNull("days") ?: 1}"
                    }
                }
                function("broken") { handle { throw CallException.unavailable("service down") } }
                function("slow", returns = Type.STRING) {
                    handleAsync { pending.also { waiting.countDown() } }
                }
                function("hold") {
                    handle {
                        holding.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        null
                    }
                }
                function("count") {
                    handle {
                        counted.incrementAndGet()
                        null
                    }
                }
                function("failsLater") {
                    handleAsync { CompletableFuture.supplyAsync { throw CallException.badArgs("checked later") } }
                }
                function("announce") {
                    handle { call ->
                        context!!.emit("weather.alert", "text" to "rain", projectId = call.projectId)
                        listOf("weather.tornado", "alert", "kakao.read").forEach { name ->
                            try {
                                context!!.emit(name, "text" to "!")
                            } catch (e: IllegalArgumentException) {
                                emitFailures += e.message!!
                            }
                        }
                        try {
                            context!!.emit("weather.alert", "txet" to "typo")
                        } catch (e: IllegalArgumentException) {
                            emitFailures += e.message!!
                        }
                    }
                }
                    event("alert") { field("text", Type.STRING) }
                },
                implement(StandardApi.Bot) {
                    handle("send") { call -> call.args.stringOrNull("channelId") != null }
                    emits("message")
                },
            )

        override fun start(context: ProviderContext) {
            this.context = context
        }

        override fun stop() {
            context = null
        }
    }

    private val transports = LoopbackTransport.pair()
    private val hostSide = transports.first
    private val pluginSide = transports.second
    private val weather = Weather()
    private val endpoint = ProviderEndpoint(pluginSide) { weather }
    private val remote = RemoteProvider.connect(hostSide)
    private val events = LinkedBlockingQueue<Triple<String, Map<String, Value>, String?>>()

    private val hostContext =
        object : ProviderContext {
            override val projects = mapOf("alpha" to mapOf("unit" to "F"), "beta" to emptyMap())

            override fun emit(
                event: String,
                payload: Map<String, Value>,
                projectId: String?,
            ) {
                events += Triple(event, payload, projectId)
            }

            override fun reportError(
                message: String,
                error: Throwable?,
            ) = Unit
        }

    @AfterTest
    fun tearDown() {
        remote.close()
        endpoint.close()
    }

    private fun call(
        project: String,
        function: String,
        args: Args,
    ) = remote.modules.single { it.spec.namespace == function.substringBefore('.') }.call(ProviderCall(project, function.substringAfter('.'), args))

    @Test
    fun `the host learns every module from the provider itself`() {
        assertEquals(weather.modules.map { it.spec }, remote.modules.map { it.spec })
    }

    @Test
    fun `calls carry the project, its options and typed arguments`() {
        remote.start(hostContext)
        assertEquals(Value.VString("Seoul: 20F x3"), call("alpha", "weather.forecast", Args.of("city" to "Seoul", "days" to 3)))
        assertEquals(Value.VString("Busan: 20C x1"), call("beta", "weather.forecast", Args.of("city" to "Busan")))
    }

    @Test
    fun `a failure keeps its code across the boundary`() {
        remote.start(hostContext)
        val e = assertFailsWith<CallException> { call("alpha", "weather.broken", Args.NONE) }
        assertEquals(ErrorCode.UNAVAILABLE, e.code)
        assertEquals("service down", e.message)
    }

    @Test
    fun `declared events reach the host and mistakes fail at the emit`() {
        remote.start(hostContext)
        call("alpha", "weather.announce", Args.NONE)
        assertEquals(Triple("weather.alert", mapOf("text" to Value.VString("rain")), "alpha"), events.poll(5, TimeUnit.SECONDS))
        assertEquals(4, weather.emitFailures.size, "${weather.emitFailures}")
        assertTrue(weather.emitFailures[0].contains("no event 'tornado'"))
        assertTrue(weather.emitFailures[1].contains("not a qualified event name"))
        assertTrue(weather.emitFailures[2].contains("no 'kakao' module"))
        assertTrue(weather.emitFailures[3].contains("txet"))
    }

    @Test
    fun `one provider serves a standard and its own namespace in one session`() {
        remote.start(hostContext)
        assertEquals(Value.TRUE, call("alpha", "bot.send", Args.of("text" to "hi", "channelId" to "42")))
    }

    @Test
    fun `nothing is emitted once stopped`() {
        remote.start(hostContext)
        remote.stop()
        assertEquals(null, weather.context)
    }

    @Test
    fun `an asynchronous handler leaves the provider free until it answers`() {
        remote.start(hostContext)
        val slow = CompletableFuture.supplyAsync { call("alpha", "weather.slow", Args.NONE) }
        assertTrue(weather.waiting.await(5, TimeUnit.SECONDS))
        // The provider's thread is not held by the pending call.
        assertEquals(Value.VString("Seoul: 20F x1"), call("alpha", "weather.forecast", Args.of("city" to "Seoul")))
        assertTrue(!slow.isDone)
        weather.pending.complete("later")
        assertEquals(Value.VString("later"), slow.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `an asynchronous failure keeps its code across the boundary`() {
        remote.start(hostContext)
        val e = assertFailsWith<CallException> { call("alpha", "weather.failsLater", Args.NONE) }
        assertEquals(ErrorCode.BAD_ARGS, e.code)
    }

    @Test
    fun `a stopped provider refuses calls instead of running them without options`() {
        remote.start(hostContext)
        remote.stop()
        val e = assertFailsWith<CallException> { call("alpha", "weather.forecast", Args.of("city" to "Seoul")) }
        assertEquals(ErrorCode.UNAVAILABLE, e.code)
    }

    @Test
    fun `stopping answers a call still waiting for its stage`() {
        remote.start(hostContext)
        val slow = CompletableFuture.supplyAsync { runCatching { call("alpha", "weather.slow", Args.NONE) }.exceptionOrNull() }
        assertTrue(weather.waiting.await(5, TimeUnit.SECONDS))
        remote.stop()
        val e = slow.get(1, TimeUnit.SECONDS)
        assertTrue(e is CallException && e.code == ErrorCode.UNAVAILABLE, "$e")
    }

    @Test
    fun `a call the host gave up on is skipped, not run late`() {
        val (hostEnd, pluginEnd) = LoopbackTransport.pair()
        val held = Weather()
        val endpoint = ProviderEndpoint(pluginEnd) { held }
        val impatient = RemoteProvider.connect(hostEnd, timeoutMs = 300)
        try {
            impatient.start(hostContext)
            val module = impatient.modules.single { it.spec.namespace == "weather" }
            val holding = CompletableFuture.runAsync { runCatching { module.call(ProviderCall("alpha", "hold", Args.NONE)) } }
            assertTrue(held.holding.await(5, TimeUnit.SECONDS))
            // Waits behind the held call until the host gives up on it.
            assertFailsWith<CallException> { module.call(ProviderCall("alpha", "count", Args.NONE)) }
            Thread.sleep(100)
            held.release.countDown()
            holding.get(5, TimeUnit.SECONDS)
            Thread.sleep(200)
            assertEquals(0, held.counted.get())
        } finally {
            held.release.countDown()
            impatient.close()
            endpoint.close()
        }
    }

    @Test
    fun `an emit from before a restart does not reach the new start`() {
        val (hostEnd, pluginEnd) = LoopbackTransport.pair()
        val generations = CopyOnWriteArrayList<Long?>()
        val plugin =
            RpcPeer(
                pluginEnd,
                object : RpcHandler {
                    override fun onRequest(
                        method: String,
                        params: Value,
                        reply: (CallResult) -> Unit,
                    ) {
                        if (method == Wire.HELLO) return reply(Wire.answerHello(params))
                        if (method == Wire.PROVIDER_START) generations += params.asObjectOrNull()?.get(Wire.GENERATION)?.asLongOrNull()
                        reply(CallResult.ok())
                    }
                },
            )
        val provider = RemoteProvider.connect(hostEnd)
        fun emit(
            text: String,
            generation: Long?,
        ) = plugin.notify(Wire.PROVIDER_EMIT, Wire.obj("event" to "weather.alert", "payload" to mapOf("text" to text), Wire.GENERATION to generation))
        try {
            provider.start(hostContext)
            provider.stop()
            provider.start(hostContext)
            emit("stale", generations[0])
            emit("current", generations[1])
            // A plugin built before generations sends none.
            emit("older plugin", null)
            assertEquals(Value.VString("current"), events.poll(5, TimeUnit.SECONDS)!!.second["text"])
            assertEquals(Value.VString("older plugin"), events.poll(5, TimeUnit.SECONDS)!!.second["text"])
            assertEquals(null, events.poll(200, TimeUnit.MILLISECONDS))
        } finally {
            provider.close()
            plugin.close()
        }
    }

    @Test
    fun `a provider that fails to start cannot emit`() {
        val (hostEnd, pluginEnd) = LoopbackTransport.pair()
        var started: ProviderContext? = null
        val failing =
            object : Provider {
                override val modules = weather.modules

                override fun start(context: ProviderContext) {
                    started = context
                    throw IllegalStateException("no network")
                }
            }
        val endpoint = ProviderEndpoint(pluginEnd) { failing }
        val provider = RemoteProvider.connect(hostEnd)
        try {
            assertFailsWith<CallException> { provider.start(hostContext) }
            started!!.emit("weather.alert", "text" to "too soon")
            assertEquals(null, events.poll(300, TimeUnit.MILLISECONDS))
        } finally {
            provider.close()
            endpoint.close()
        }
    }
}
