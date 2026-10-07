/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.api.remote

import com.xfl.msgbot.plugin.api.call.CallException
import com.xfl.msgbot.plugin.api.call.CallResult
import com.xfl.msgbot.plugin.api.engine.EngineThread
import com.xfl.msgbot.plugin.api.remote.Wire.map
import com.xfl.msgbot.plugin.api.remote.Wire.string
import com.xfl.msgbot.plugin.api.rpc.PluginTransport
import com.xfl.msgbot.plugin.api.rpc.RpcHandler
import com.xfl.msgbot.plugin.api.rpc.RpcPeer
import com.xfl.msgbot.plugin.api.tooling.LanguageTools
import com.xfl.msgbot.plugin.api.tooling.ToolingContext
import com.xfl.msgbot.plugin.api.tooling.ToolingFactory
import com.xfl.msgbot.plugin.api.tooling.ToolingHost
import com.xfl.msgbot.plugin.api.value.Value
import com.xfl.msgbot.plugin.api.value.asArrayOrNull
import com.xfl.msgbot.plugin.api.value.asIntOrNull
import com.xfl.msgbot.plugin.api.value.asStringOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plugin side of a tooling session: creates the [LanguageTools] and runs every call on one thread.
 * Created per session by [com.xfl.msgbot.plugin.ipc.PluginService].
 *
 * A question that is picked up after the host stopped waiting for it is skipped, so typing fast
 * does not queue up a stale analysis behind each keystroke. [configure][LanguageTools.configure]
 * and [sync][LanguageTools.sync] are never skipped.
 */
class ToolingEndpoint(
    transport: PluginTransport,
    private val factory: ToolingFactory,
    private val callTimeoutMs: Long = 10_000,
    private val onProtocolError: (String) -> Unit = {},
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val thread: EngineThread = EngineThread("plugin-tooling") { message, _ -> reportError(message) }

    @Volatile private var tools: LanguageTools? = null

    @Volatile private var startupFailure: String? = null

    private val context: ToolingContext =
        object : ToolingContext {
            override val host: ToolingHost =
                object : ToolingHost {
                    override fun read(path: String): String? = ask(Wire.TOOLING_READ, path)?.asStringOrNull()

                    override fun list(path: String): List<String>? = ask(Wire.TOOLING_LIST, path)?.asArrayOrNull()?.mapNotNull { it.asStringOrNull() }

                    private fun ask(
                        method: String,
                        path: String,
                    ): Value? {
                        val params = Wire.obj("path" to path, Wire.deadline(callTimeoutMs))
                        return (peer.request(method, params, callTimeoutMs) as? CallResult.Ok)?.value?.takeIf { it !is Value.VNull }
                    }
                }

            override fun reportError(
                message: String,
                error: Throwable?,
            ) = this@ToolingEndpoint.reportError(message)
        }

    private val handler: RpcHandler =
        object : RpcHandler {
            override fun onRequest(
                method: String,
                params: Value,
                reply: (CallResult) -> Unit,
            ) {
                val map: Map<String, Value>
                try {
                    map = if (method == Wire.HELLO) emptyMap() else params.map()
                } catch (e: Exception) {
                    return reply(CallResult.badArgs("Malformed $method: ${e.message}"))
                }
                when (method) {
                    Wire.HELLO -> onTools(reply) { tools -> Wire.answerHello(params, extra = { capabilities(tools) }).getOrThrow() }
                    Wire.TOOLING_CONFIGURE ->
                        onTools(reply) { tools ->
                            tools.configure(ToolingWire.workspaceOf(params))
                            Value.VNull
                        }
                    Wire.TOOLING_SYNC ->
                        onTools(reply) { tools ->
                            tools.sync(ToolingWire.changeOf(params))
                            Value.VNull
                        }
                    Wire.TOOLING_DIAGNOSTICS ->
                        query(map, reply) { tools, path -> ToolingWire.diagnostics(tools.diagnostics(path)) }
                    Wire.TOOLING_COMPLETE ->
                        query(map, reply) { tools, path -> ToolingWire.completions(tools.complete(path, offset(map))) }
                    Wire.TOOLING_HOVER -> query(map, reply) { tools, path -> ToolingWire.hover(tools.hover(path, offset(map))) }
                    Wire.TOOLING_SIGNATURE ->
                        query(map, reply) { tools, path -> ToolingWire.signatureHelp(tools.signatureHelp(path, offset(map))) }
                    else -> reply(CallResult.failed("A tooling plugin does not answer '$method'"))
                }
            }

            override fun onNotify(
                method: String,
                params: Value,
            ) {
                if (method == Wire.CLOSE) close()
            }
        }

    private val constructed = CountDownLatch(1)

    init {
        thread.execute {
            constructed.await()
            try {
                tools = factory.create(context)
            } catch (e: Throwable) {
                startupFailure = "The tooling could not start: ${e.message ?: e.javaClass.simpleName}"
                reportError(startupFailure!!)
            }
        }
    }

    private val peer: RpcPeer = RpcPeer(transport, handler, onProtocolError)

    init {
        constructed.countDown()
    }

    /** Closed, and the tooling's thread has stopped. */
    val isStopped: Boolean get() = closed.get() && thread.isTerminated

    private fun capabilities(tools: LanguageTools): Map<String, Any?> = mapOf("capabilities" to tools.capabilities.map { it.wire })

    private fun offset(map: Map<String, Value>): Int = map["offset"]?.asIntOrNull() ?: throw CallException.badArgs("'offset' is missing")

    private fun query(
        map: Map<String, Value>,
        reply: (CallResult) -> Unit,
        block: (LanguageTools, String) -> Value,
    ) {
        val path =
            try {
                map.string("path")
            } catch (e: Exception) {
                return reply(CallResult.badArgs("Malformed query: ${e.message}"))
            }
        onTools(reply) { tools ->
            if (Wire.expired(map)) throw CallException.unavailable("The question waited past the host's deadline and was skipped")
            block(tools, path)
        }
    }

    private fun reportError(message: String) = peer.notify(Wire.TOOLING_ERROR, Wire.obj("message" to message))

    /** Catches Throwable so every request gets a reply. */
    private fun onTools(
        reply: (CallResult) -> Unit,
        block: (LanguageTools) -> Value,
    ) {
        try {
            thread.execute {
                val result =
                    if (closed.get()) {
                        CallResult.unavailable("The tooling session is closed")
                    } else {
                        try {
                            val tools = tools ?: throw IllegalStateException(startupFailure ?: "The tooling is not running")
                            CallResult.ok(block(tools))
                        } catch (e: Throwable) {
                            Wire.errorOf(e)
                        }
                    }
                reply(result)
            }
        } catch (_: RejectedExecutionException) {
            reply(CallResult.unavailable("The tooling session is closed"))
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        peer.close()
        try {
            thread.execute { runCatching { tools?.close() } }
        } catch (_: RejectedExecutionException) {
        }
        thread.shutdown()
    }
}
