/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.engine

import com.xfl.msgbot.plugin.api.bridge.HostBridge
import com.xfl.msgbot.plugin.api.value.Value

/**
 * Contract an engine implements. Its only responsibilities: bind a native `__host_call`
 * delegating to [HostBridge], and marshal [Value] <-> native values, delivering host events
 * to a native `__dispatch(event)`. Idiomatic APIs come from the shim, not the engine.
 *
 * All methods must be called on the same single thread (V8/Rhino/Lua thread-affinity),
 * guaranteed by the host runtime.
 */
interface ScriptEngine : AutoCloseable {
    val descriptor: EngineDescriptor

    /** Bind the host bridge. Must be called before [load]. */
    fun bindHost(bridge: HostBridge)

    /**
     * Evaluate the shim then the user script, making the engine ready to receive events.
     *
     * [language]: what [userScript] is written in; a single-language engine may ignore it.
     * [capabilities]: must be bound as a global `__caps` (data, not generated source) before the
     * shim runs, so the shim can gate its API surface.
     * [shim]: the selected script profile's language-specific facade.
     * [options]: this engine's per-project settings, carried by the host unread.
     */
    fun load(
        language: String,
        capabilities: List<String>,
        shim: String,
        userScript: String,
        options: Map<String, String> = emptyMap(),
    )

    /** Evaluate arbitrary source (mainly for tests/REPL). */
    fun eval(source: String): Value

    /** Deliver a host event to the script via the global `__dispatch(event)`. */
    fun dispatch(event: Value.VObject)

    override fun close()
}
