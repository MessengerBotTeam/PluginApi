/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.api.remote

import com.xfl.msgbot.plugin.api.call.CallResult
import com.xfl.msgbot.plugin.api.remote.Wire.map
import com.xfl.msgbot.plugin.api.remote.Wire.string
import com.xfl.msgbot.plugin.api.rpc.PluginTransport
import com.xfl.msgbot.plugin.api.rpc.RpcHandler
import com.xfl.msgbot.plugin.api.rpc.RpcPeer
import com.xfl.msgbot.plugin.api.tooling.LanguageTools
import com.xfl.msgbot.plugin.api.tooling.ToolCompletion
import com.xfl.msgbot.plugin.api.tooling.ToolDiagnostic
import com.xfl.msgbot.plugin.api.tooling.ToolHover
import com.xfl.msgbot.plugin.api.tooling.ToolSignatureHelp
import com.xfl.msgbot.plugin.api.tooling.ToolingCapability
import com.xfl.msgbot.plugin.api.tooling.ToolingChange
import com.xfl.msgbot.plugin.api.tooling.ToolingHost
import com.xfl.msgbot.plugin.api.tooling.ToolingWorkspace
import com.xfl.msgbot.plugin.api.value.Value
import com.xfl.msgbot.plugin.api.value.asArrayOrNull
import com.xfl.msgbot.plugin.api.value.asStringOrNull
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore

/**
 * Host-side [LanguageTools] proxy for the tooling in a plugin. A call fails with
 * [com.xfl.msgbot.plugin.api.call.CallException] when the plugin answers with an error or does not
 * answer within [timeoutMs]; [diagnostics], [complete], [hover] and [signatureHelp] carry that
 * deadline, so a plugin that falls behind skips the questions that are already stale.
 *
 * The plugin's reads of [files] run on [callExecutor], so a slow disk does not hold the transport.
 */
class RemoteLanguageTools private constructor(
    transport: PluginTransport,
    private val files: ToolingHost,
    private val callExecutor: Executor,
    private val timeoutMs: Long,
    maxPendingCalls: Int,
    private val onError: (String) -> Unit,
) : LanguageTools {
    private val callSlots = Semaphore(maxPendingCalls.also { require(it > 0) })

    override var capabilities: Set<ToolingCapability> = emptySet()
        private set

    private val handler =
        object : RpcHandler {
            override fun onRequest(
                method: String,
                params: Value,
                reply: (CallResult) -> Unit,
            ) {
                if (method != Wire.TOOLING_READ && method != Wire.TOOLING_LIST) {
                    return reply(CallResult.failed("The host does not answer '$method' from a tooling plugin"))
                }
                val map: Map<String, Value>
                val path: String
                try {
                    map = params.map()
                    path = map.string("path")
                } catch (e: Exception) {
                    return reply(CallResult.badArgs("Malformed $method: ${e.message}"))
                }
                if (!callSlots.tryAcquire()) return reply(CallResult.unavailable("Too many reads are waiting"))
                try {
                    callExecutor.execute {
                        try {
                            val result =
                                when {
                                    Wire.expired(map) -> CallResult.unavailable("The read waited past the plugin's deadline and was skipped")
                                    method == Wire.TOOLING_READ -> CallResult.ok(files.read(path)?.let(Value::of) ?: Value.VNull)
                                    else -> CallResult.ok(files.list(path)?.let { names -> Value.VArray(names.map(Value::of)) } ?: Value.VNull)
                                }
                            reply(result)
                        } catch (e: Throwable) {
                            reply(Wire.errorOf(e))
                        } finally {
                            callSlots.release()
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    callSlots.release()
                    reply(CallResult.unavailable("The host is shutting down"))
                }
            }

            override fun onNotify(
                method: String,
                params: Value,
            ) {
                if (method == Wire.TOOLING_ERROR) {
                    onError(params.map()["message"]?.asStringOrNull() ?: "The tooling reported an error")
                }
            }
        }

    private val peer = RpcPeer(transport, handler, onError)

    override fun configure(workspace: ToolingWorkspace) {
        peer.request(Wire.TOOLING_CONFIGURE, ToolingWire.workspace(workspace), timeoutMs).getOrThrow()
    }

    override fun sync(change: ToolingChange) {
        peer.request(Wire.TOOLING_SYNC, ToolingWire.change(change), timeoutMs).getOrThrow()
    }

    override fun diagnostics(path: String): List<ToolDiagnostic> =
        ToolingWire.diagnosticsOf(ask(Wire.TOOLING_DIAGNOSTICS, path))

    override fun complete(
        path: String,
        offset: Int,
    ): List<ToolCompletion> = ToolingWire.completionsOf(ask(Wire.TOOLING_COMPLETE, path, offset))

    override fun hover(
        path: String,
        offset: Int,
    ): ToolHover? = ToolingWire.hoverOf(ask(Wire.TOOLING_HOVER, path, offset))

    override fun signatureHelp(
        path: String,
        offset: Int,
    ): ToolSignatureHelp? = ToolingWire.signatureHelpOf(ask(Wire.TOOLING_SIGNATURE, path, offset))

    private fun ask(
        method: String,
        path: String,
        offset: Int? = null,
    ): Value =
        peer
            .request(method, Wire.obj("path" to path, "offset" to offset, Wire.deadline(timeoutMs)), timeoutMs)
            .getOrThrow()

    override fun close() {
        peer.notify(Wire.CLOSE)
        peer.close()
    }

    companion object {
        /** Performs the `hello` handshake. On failure closes the transport and throws. */
        fun connect(
            transport: PluginTransport,
            files: ToolingHost,
            callExecutor: Executor,
            timeoutMs: Long = 10_000,
            maxPendingCalls: Int = 32,
            onError: (String) -> Unit = {},
        ): RemoteLanguageTools {
            val tools = RemoteLanguageTools(transport, files, callExecutor, timeoutMs, maxPendingCalls, onError)
            try {
                val hello = Wire.checkHello(tools.peer.request(Wire.HELLO, Wire.hello(), Wire.HELLO_TIMEOUT_MS))
                tools.capabilities =
                    hello["capabilities"]?.asArrayOrNull()?.mapNotNull { it.asStringOrNull()?.let(ToolingCapability::of) }?.toSet().orEmpty()
            } catch (e: Exception) {
                tools.peer.close()
                throw e
            }
            return tools
        }
    }
}
