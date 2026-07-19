/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.source

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
interface MessageSource : AutoCloseable {
    val descriptor: SourceDescriptor

    /** Bind the channel this source pushes events into. Called before [start]. */
    fun bindSink(sink: EventSink)

    /**
     * Execute a capability this source declared ([SourceDescriptor.capabilities]). Tokens in
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
fun interface EventSink {
    fun emit(event: Value.VObject)
}

/**
 * What a source advertises. Both lists are open and unfiltered: what a source declares is what a
 * script gets. [capabilities] are short names under `bot.*` (e.g. "reply", "sendImage"), [events]
 * are event types (e.g. "message"); declaring one is a promise to implement it.
 */
data class SourceDescriptor(
    val sourceId: String,
    val displayName: String,
    /** What [MessageSource.call] will execute. */
    val capabilities: List<String>,
    /** Event types this source emits. A listener for anything else would wait forever. */
    val events: List<String> = listOf(Events.MESSAGE),
)
