/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.provider

import com.xfl.msgbot.plugin.api.value.Value

/**
 * Contract a message source implements: where events come from and how to act on them.
 *
 * A source is the mirror image of an engine. An engine receives events and calls capabilities;
 * a source emits events and executes capabilities. Both know only this host contract, so sources
 * and engines never learn about each other and N sources x M engines stays N + M.
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
