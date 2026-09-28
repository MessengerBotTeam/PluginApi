/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.remote

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
import com.xfl.msgbot.plugin.api.value.asStringOrNull

/**
 * The host side of a provider running in a plugin: a [Provider] like any builtin one, so the host
 * has a single way of running providers. Its [modules] are what the plugin described itself as.
 */
class RemoteProvider private constructor(
    transport: PluginTransport,
    private val timeoutMs: Long,
    private val onError: (String) -> Unit,
) : Provider {
    @Volatile private var context: ProviderContext? = null

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
                        current.emit(map.string("event"), map["payload"]?.map().orEmpty(), map["project"]?.asStringOrNull())
                    }
                    Wire.PROVIDER_ERROR -> onError(params.map()["message"]?.asStringOrNull() ?: "The provider reported an error")
                }
            }
        }

    private val peer = RpcPeer(transport, handler, onError)

    override val modules: List<ProviderModule> by lazy {
        specs.map { spec ->
            ProviderModule(spec) { call ->
                peer
                    .request(
                        Wire.PROVIDER_CALL,
                        Wire.obj("project" to call.projectId, "function" to spec.qualified(call.function), "args" to call.args.values),
                        timeoutMs,
                    ).getOrThrow()
            }
        }
    }

    override fun start(context: ProviderContext) {
        this.context = context
        peer.request(Wire.PROVIDER_START, Wire.obj("projects" to Wire.projects(context.projects)), timeoutMs).getOrThrow()
    }

    override fun stop() {
        context = null
        peer.request(Wire.PROVIDER_STOP, Value.VNull, timeoutMs)
    }

    override fun close() {
        context = null
        peer.notify(Wire.CLOSE)
        peer.close()
    }

    companion object {
        /** Opens the session and reads the module the provider offers. Throws when it will not say. */
        fun connect(
            transport: PluginTransport,
            timeoutMs: Long = 15_000,
            onError: (String) -> Unit = {},
        ): RemoteProvider {
            val provider = RemoteProvider(transport, timeoutMs, onError)
            try {
                val hello = Wire.checkHello(provider.peer.request(Wire.HELLO, Wire.hello(), Wire.HELLO_TIMEOUT_MS))
                provider.specs = (hello["modules"]?.asArrayOrNull() ?: throw IllegalStateException("The provider did not describe its modules")).map(ModuleSpec::fromValue)
            } catch (e: Exception) {
                provider.peer.close()
                throw e
            }
            return provider
        }
    }
}
