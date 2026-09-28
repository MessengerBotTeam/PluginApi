/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.remote

import com.xfl.msgbot.plugin.api.call.CallResult
import com.xfl.msgbot.plugin.api.engine.EngineContext
import com.xfl.msgbot.plugin.api.engine.LoadRequest
import com.xfl.msgbot.plugin.api.engine.ScriptEngine
import com.xfl.msgbot.plugin.api.engine.ScriptEvent
import com.xfl.msgbot.plugin.api.remote.Wire.map
import com.xfl.msgbot.plugin.api.remote.Wire.orEngineException
import com.xfl.msgbot.plugin.api.remote.Wire.string
import com.xfl.msgbot.plugin.api.rpc.PluginTransport
import com.xfl.msgbot.plugin.api.rpc.RpcHandler
import com.xfl.msgbot.plugin.api.rpc.RpcPeer
import com.xfl.msgbot.plugin.api.value.Value
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * The host side of an engine running in a plugin: a [ScriptEngine] like any builtin one. Calls
 * from the script are answered on [callExecutor], never on the transport thread, so a slow host
 * function cannot hold up the frames behind it.
 */
class RemoteScriptEngine private constructor(
    transport: PluginTransport,
    private val context: EngineContext,
    private val callExecutor: Executor,
    private val timeoutMs: Long,
) : ScriptEngine {
    private val handler =
        object : RpcHandler {
            override fun onRequest(
                method: String,
                params: Value,
                reply: (CallResult) -> Unit,
            ) {
                if (method != Wire.HOST_CALL) return reply(CallResult.failed("The host does not answer '$method'"))
                val call =
                    try {
                        val map = params.map()
                        map.string("function") to map.getValue("args").map()
                    } catch (e: Exception) {
                        return reply(CallResult.badArgs("Malformed call: ${e.message}"))
                    }
                try {
                    callExecutor.execute { reply(context.host.call(call.first, call.second)) }
                } catch (_: RejectedExecutionException) {
                    reply(CallResult.unavailable("The host is shutting down"))
                }
            }

            override fun onNotify(
                method: String,
                params: Value,
            ) {
                if (method == Wire.ENGINE_ERROR) {
                    context.reportError((params.map()["message"] as? Value.VString)?.value ?: "The engine reported an error")
                }
            }
        }

    private val peer = RpcPeer(transport, handler) { context.reportError(it) }

    override fun load(request: LoadRequest) {
        peer.request(Wire.ENGINE_LOAD, Wire.loadRequest(request), timeoutMs).orEngineException("load")
    }

    override fun dispatch(event: ScriptEvent) {
        peer
            .request(Wire.ENGINE_DISPATCH, Wire.obj("event" to event.name, "payload" to event.payload), timeoutMs)
            .orEngineException("'${event.name}'")
    }

    override fun eval(source: String): Value = peer.request(Wire.ENGINE_EVAL, Wire.obj("source" to source), timeoutMs).orEngineException("eval")

    override fun close() {
        peer.notify(Wire.CLOSE)
        peer.close()
    }

    companion object {
        /**
         * Opens the session over [transport] and checks that the plugin speaks this protocol.
         * Throws when it does not answer or cannot; the transport is closed then.
         */
        fun connect(
            transport: PluginTransport,
            context: EngineContext,
            callExecutor: Executor,
            timeoutMs: Long = 30_000,
        ): RemoteScriptEngine {
            val engine = RemoteScriptEngine(transport, context, callExecutor, timeoutMs)
            try {
                Wire.checkHello(engine.peer.request(Wire.HELLO, Wire.hello(), Wire.HELLO_TIMEOUT_MS))
            } catch (e: Exception) {
                engine.peer.close()
                throw e
            }
            return engine
        }
    }
}
