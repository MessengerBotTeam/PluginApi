/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.tck

import com.xfl.msgbot.plugin.api.call.Args
import com.xfl.msgbot.plugin.api.call.CallException
import com.xfl.msgbot.plugin.api.call.CallResult
import com.xfl.msgbot.plugin.api.provider.Provider
import com.xfl.msgbot.plugin.api.provider.ProviderCall
import com.xfl.msgbot.plugin.api.provider.ProviderContext
import com.xfl.msgbot.plugin.api.remote.ProviderEndpoint
import com.xfl.msgbot.plugin.api.remote.RemoteProvider
import com.xfl.msgbot.plugin.api.rpc.LoopbackTransport
import com.xfl.msgbot.plugin.api.schema.ModuleSpec
import com.xfl.msgbot.plugin.api.schema.Names
import com.xfl.msgbot.plugin.api.value.Value
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Runs a provider the way the host runs a plugin's: behind a [ProviderEndpoint], reached through
 * [RemoteProvider] over a loopback transport, so everything crosses the same wire as on a phone.
 */
class ProviderHarness(factory: () -> Provider) : AutoCloseable {
    data class Emitted(val event: String, val payload: Map<String, Value>, val projectId: String?)

    val events = LinkedBlockingQueue<Emitted>()
    val errors = CopyOnWriteArrayList<String>()

    /** The instance the endpoint created, for tests that drive it directly. */
    lateinit var provider: Provider
        private set

    private val transports = LoopbackTransport.pair()
    private val endpoint = ProviderEndpoint(transports.second) { factory().also { provider = it } }
    private val remote = RemoteProvider.connect(transports.first, onError = { errors += it })

    /** What the host learned from the provider's hello. */
    val modules: List<ModuleSpec> = remote.modules.map { it.spec }

    fun start(projects: Map<String, Map<String, String>>) = remote.start(context(projects))

    fun stop() = remote.stop()

    /** Calls [function] (`namespace.name`) for [projectId], as the host would after checking the arguments. */
    fun call(
        projectId: String,
        function: String,
        args: Map<String, Value> = emptyMap(),
    ): CallResult {
        val (namespace, name) = Names.split(function) ?: return CallResult.unknownFunction(function)
        val module = remote.modules.firstOrNull { it.spec.namespace == namespace } ?: return CallResult.unknownFunction(function)
        return try {
            CallResult.ok(module.call(ProviderCall(projectId, name, Args(args))))
        } catch (e: CallException) {
            CallResult.Err(e.code, e.message ?: e.code)
        }
    }

    /** The events that arrive within [timeoutMs]. */
    fun drainEvents(timeoutMs: Long = 500): List<Emitted> {
        val drained = mutableListOf<Emitted>()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return drained
            drained += events.poll(remaining, TimeUnit.NANOSECONDS) ?: return drained
        }
    }

    override fun close() {
        remote.close()
        endpoint.close()
    }

    private fun context(projects: Map<String, Map<String, String>>): ProviderContext =
        object : ProviderContext {
            override val projects = projects

            override fun emit(
                event: String,
                payload: Map<String, Value>,
                projectId: String?,
            ) {
                events += Emitted(event, payload, projectId)
            }

            override fun reportError(
                message: String,
                error: Throwable?,
            ) {
                errors += message
            }
        }
}
