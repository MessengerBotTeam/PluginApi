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
     * [shim] may be empty, meaning the host has no shim for this engine's language: the engine
     * must then supply its own shim for [apiLevel]. This is what lets a new language ship as a
     * self-contained plugin (its shim is language-specific code, so it belongs with the plugin).
     */
    fun load(apiLevel: String, shim: String, userScript: String)

    /** Evaluate arbitrary source (mainly for tests/REPL). */
    fun eval(source: String): Value

    /** Deliver a host event to the script via the global `__dispatch(event)`. */
    fun dispatch(event: Value.VObject)

    override fun close()
}
