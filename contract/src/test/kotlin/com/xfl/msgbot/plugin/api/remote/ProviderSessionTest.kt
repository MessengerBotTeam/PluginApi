package com.xfl.msgbot.plugin.api.remote

import com.xfl.msgbot.plugin.api.call.Args
import com.xfl.msgbot.plugin.api.call.CallException
import com.xfl.msgbot.plugin.api.call.ErrorCode
import com.xfl.msgbot.plugin.api.provider.Provider
import com.xfl.msgbot.plugin.api.provider.ProviderCall
import com.xfl.msgbot.plugin.api.provider.ProviderContext
import com.xfl.msgbot.plugin.api.provider.emit
import com.xfl.msgbot.plugin.api.provider.provide
import com.xfl.msgbot.plugin.api.rpc.LoopbackTransport
import com.xfl.msgbot.plugin.api.schema.Type
import com.xfl.msgbot.plugin.api.value.Value
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProviderSessionTest {
    private class Weather : Provider {
        var context: ProviderContext? = null
        val emitFailures = CopyOnWriteArrayList<String>()

        override val module =
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
                function("announce") {
                    handle { call ->
                        context!!.emit("alert", "text" to "rain", projectId = call.projectId)
                        try {
                            context!!.emit("tornado", "text" to "!")
                        } catch (e: IllegalArgumentException) {
                            emitFailures += e.message!!
                        }
                        try {
                            context!!.emit("alert", "txet" to "typo")
                        } catch (e: IllegalArgumentException) {
                            emitFailures += e.message!!
                        }
                    }
                }
                event("alert") { field("text", Type.STRING) }
            }

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

    @Test
    fun `the host learns the module from the provider itself`() {
        assertEquals(weather.module.spec, remote.module.spec)
    }

    @Test
    fun `calls carry the project, its options and typed arguments`() {
        remote.start(hostContext)
        assertEquals(Value.VString("Seoul: 20F x3"), remote.module.call(ProviderCall("alpha", "forecast", Args.of("city" to "Seoul", "days" to 3))))
        assertEquals(Value.VString("Busan: 20C x1"), remote.module.call(ProviderCall("beta", "forecast", Args.of("city" to "Busan"))))
    }

    @Test
    fun `a failure keeps its code across the boundary`() {
        remote.start(hostContext)
        val e = assertFailsWith<CallException> { remote.module.call(ProviderCall("alpha", "broken", Args.NONE)) }
        assertEquals(ErrorCode.UNAVAILABLE, e.code)
        assertEquals("service down", e.message)
    }

    @Test
    fun `declared events reach the host and mistakes fail at the emit`() {
        remote.start(hostContext)
        remote.module.call(ProviderCall("alpha", "announce", Args.NONE))
        assertEquals(Triple("alert", mapOf("text" to Value.VString("rain")), "alpha"), events.poll(5, TimeUnit.SECONDS))
        assertEquals(2, weather.emitFailures.size)
        assertTrue(weather.emitFailures[0].contains("no event 'tornado'"))
        assertTrue(weather.emitFailures[1].contains("txet"))
    }

    @Test
    fun `nothing is emitted once stopped`() {
        remote.start(hostContext)
        remote.stop()
        assertEquals(null, weather.context)
    }
}
