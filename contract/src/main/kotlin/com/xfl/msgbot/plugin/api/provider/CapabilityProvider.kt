/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.provider

import com.xfl.msgbot.plugin.api.value.Value

/**
 * Event and capability provider, independent of the language engine.
 *
 * A provider emits events and executes qualified calls. The host passes project identity in
 * [ProviderCall] and delivers [ProviderEvent] only to projects that selected this provider.
 *
 * All methods run on a single dedicated thread, guaranteed by the host runtime.
 */
interface CapabilityProvider : AutoCloseable {
    val descriptor: ProviderDescriptor

    /** Bind the channel this provider pushes events into. Called before [start]. */
    fun bindSink(sink: ProviderEventSink)

    /**
     * Execute a declared capability for one project. Tokens in [ProviderCall.args] are the ones
     * the provider put in an event it emitted. Fail by throwing
     * ([com.xfl.msgbot.plugin.api.bridge.CapabilityException] to name the kind); the failure
     * travels back to the script as an answer.
     */
    fun call(call: ProviderCall): Value

    /**
     * Per-project settings for every active project selecting this provider. Called on the
     * provider thread before [start]. Keys are local to this provider; the host never interprets
     * values. Reconfiguration stops and starts the provider with a new snapshot.
     */
    fun configure(projects: Map<String, Map<String, String>>) = Unit

    /** Begin emitting. The host starts a provider only while some project uses it. */
    fun start()

    fun stop()

    override fun close() = stop()
}

/** One call carries the caller's project identity without binding a provider per project. */
data class ProviderCall(val projectId: String, val method: String, val args: List<Value>)

/** A null target broadcasts to subscribed projects; otherwise delivery is limited to one. */
data class ProviderEvent(val projectId: String?, val payload: Value.VObject)

/** Where a provider pushes events. */
fun interface ProviderEventSink {
    fun emit(event: ProviderEvent)
}

/** A provider of events and capabilities, independent of the language engine. */
data class ProviderDescriptor(
    val providerId: String,
    val displayName: String,
    /** What [CapabilityProvider.call] will execute. */
    val capabilities: List<String>,
    /** Event types this provider emits. */
    val events: List<String> = emptyList(),
    /** Capability namespace. Messaging sources use `bot`; other providers own their own namespace. */
    val namespace: String = "bot",
)
