/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.api.engine

/**
 * Optional hook for runtimes with an event loop (for example Node.js timers and I/O).
 * [com.xfl.msgbot.plugin.api.transport.EngineHost] calls [poll] on the engine thread at a
 * configured interval, including when no host events arrive. Implementations must return
 * promptly; waiting for the next timer would block dispatch and shutdown.
 */
interface PollingScriptEngine : ScriptEngine {
    fun poll()
}
