/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.bridge

import com.xfl.msgbot.plugin.api.value.Value

/**
 * The single script -> host downcall (capability invocation). The engine binds one native
 * `__host_call(method, args)` global that delegates here; idiomatic APIs are layered by the
 * shim, so the host has no language-specific code.
 *
 * Called synchronously on the engine's dedicated thread. In-process this is a local call;
 * over IPC the caller blocks while the host services the call on a separate worker thread.
 *
 * @param method one of [com.xfl.msgbot.plugin.api.protocol.Capabilities].
 * @return the value, or why there is none. An implementation should not throw: a failure it knows
 *   about is a [CallResult.Err], which is the one the script can be told about.
 */
fun interface HostBridge {
    fun call(method: String, args: List<Value>): CallResult
}
