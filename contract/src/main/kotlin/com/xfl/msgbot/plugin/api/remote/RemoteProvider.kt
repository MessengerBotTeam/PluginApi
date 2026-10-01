/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.remote

import com.xfl.msgbot.plugin.api.call.CallException
import com.xfl.msgbot.plugin.api.call.CallResult
import com.xfl.msgbot.plugin.api.provider.Provider
import com.xfl.msgbot.plugin.api.provider.ProviderContext
import com.xfl.msgbot.plugin.api.provider.ProviderModule
import com.xfl.msgbot.plugin.api.remote.Wire.map
import com.xfl.msgbot.plugin.api.remote.Wire.string
import com.xfl.msgbot.plugin.api.rpc.PluginTransport
import com.xfl.msgbot.plugin.api.rpc.RpcHandler
import com.xfl.msgbot.plugin.api.rpc.RpcPeer
import com.xfl.msgbot.plugin.api.schema.ModuleSpec
import com.xfl.msgbot.plugin.api.value.Value
import com.xfl.msgbot.plugin.api.value.asArrayOrNull
import com.xfl.msgbot.plugin.api.value.asLongOrNull
import com.xfl.msgbot.plugin.api.value.asStringOrNull
import java.util.concurrent.CompletableFuture

/** Host-side [Provider] proxy for a provider in a plugin. [modules] come from the plugin's `hello` reply. */
class RemoteProvider private constructor(
    transport: PluginTransport,
    private val timeoutMs: Long,
    private val onError: (String) -> Unit,
) : Provider {
    @Volatile private var context: ProviderContext? = null

    /** Of the latest [start]; emits from an earlier one are dropped. */
    @Volatile private var generation = 0L

    private lateinit var specs: List<ModuleSpec>

    private val handler =
        object : RpcHandler {
            override fun onRequest(
                method: String,
                params: Value,
                reply: (CallResult) -> Unit,
            ) = reply(CallResult.failed("The host does not answer '$method' from a provider"))

            override fun onNotify(
                method: String,
                params: Value,
            ) {
                when (method) {
                    Wire.PROVIDER_EMIT -> {
                        val current = context ?: return
                        val map = params.map()
                        // Plugins built before generations send none; their emits are taken as current.
                        val from = map[Wire.GENERATION]?.asLongOrNull()
                        if (from != null && from != generation) return
                        val project = map["project"] ?: Value.VNull
                        if (project !is Value.VNull && project !is Value.VString) {
                            return onError("Dropped '${map["event"]?.asStringOrNull()}': its project is not a name")
                        }
                        current.emit(map.string("event"), map["payload"]?.map().orEmpty(), project.asStringOrNull())
                    }
                    Wire.PROVIDER_ERROR -> onError(params.map()["message"]?.asStringOrNull() ?: "The provider reported an error")
                }
            }
        }

    private val peer = RpcPeer(transport, handler, onError)

    override val modules: List<ProviderModule> by lazy {
        specs.map { spec ->
            ProviderModule(
                spec,
                ProviderModule.Dispatch { call ->
                    val answer = CompletableFuture<Value>()
                    val params =
                        Wire.obj(
                            "project" to call.projectId,
                            "function" to spec.qualified(call.function),
                            "args" to call.args.values,
                            Wire.deadline(timeoutMs),
                        )
                    peer.requestAsync(Wire.PROVIDER_CALL, params, timeoutMs) { result ->
                        when (result) {
                            is CallResult.Ok -> answer.complete(result.value)
                            is CallResult.Err -> answer.completeExceptionally(CallException(result.code, result.message))
                        }
                    }
                    answer
                },
            )
        }
    }

    override fun start(context: ProviderContext) {
        val next = generation + 1
        generation = next
        this.context = context
        try {
            peer.request(Wire.PROVIDER_START, Wire.obj("projects" to Wire.projects(context.projects), Wire.GENERATION to next), timeoutMs).getOrThrow()
        } catch (e: Exception) {
            this.context = null
            throw e
        }
    }

    override fun stop() {
        context = null
        val result = peer.request(Wire.PROVIDER_STOP, Value.VNull, timeoutMs)
        if (result is CallResult.Err) onError("The provider did not stop cleanly: ${result.message}")
    }

    override fun close() {
        context = null
        peer.notify(Wire.CLOSE)
        peer.close()
    }

    companion object {
        /** Performs the `hello` handshake and reads the provider's modules. On failure closes the transport and throws. */
        fun connect(
            transport: PluginTransport,
            timeoutMs: Long = 15_000,
            onError: (String) -> Unit = {},
        ): RemoteProvider {
            val provider = RemoteProvider(transport, timeoutMs, onError)
            try {
                val hello = Wire.checkHello(provider.peer.request(Wire.HELLO, Wire.hello(), Wire.HELLO_TIMEOUT_MS))
                val described =
                    hello["modules"]?.asArrayOrNull() ?: throw IllegalStateException("The provider did not describe its modules")
                provider.specs =
                    described.map { value ->
                        ModuleSpec.read(value).also { read ->
                            read.skipped.forEach { onError("Left out $it") }
                        }.spec
                    }
            } catch (e: Exception) {
                provider.peer.close()
                throw e
            }
            return provider
        }
    }
}
