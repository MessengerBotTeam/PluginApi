/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.provider

import com.xfl.msgbot.plugin.api.protocol.Events
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

    /** Bind the channel this source pushes events into. Called before [start]. */
    fun bindSink(sink: ProviderEventSink)

    /**
     * Execute a capability this source declared ([ProviderDescriptor.capabilities]). Tokens in
     * [args] are the ones this source put in the event it emitted. Fail by throwing
     * ([com.xfl.msgbot.plugin.api.bridge.CapabilityException] to name the kind); the failure
     * travels back to the script as an answer.
     */
    fun call(method: String, args: List<Value>): Value

    /** Begin emitting. The host starts a source only while some project actually uses it. */
    fun start()

    fun stop()

    override fun close() = stop()
}

/** Where a source pushes events. The host fans them out to the projects using that source. */
fun interface ProviderEventSink {
    fun emit(event: Value.VObject)
}

/** A provider of events and capabilities, independent of the language engine. */
data class ProviderDescriptor(
    val providerId: String,
    val displayName: String,
    /** What [CapabilityProvider.call] will execute. */
    val capabilities: List<String>,
    /** Event types this source emits. A listener for anything else would wait forever. */
    val events: List<String> = listOf(Events.MESSAGE),
    /** Capability namespace. Messaging sources use `bot`; other providers own their own namespace. */
    val namespace: String = "bot",
)
