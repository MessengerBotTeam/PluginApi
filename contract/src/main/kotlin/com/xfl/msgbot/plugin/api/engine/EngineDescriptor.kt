/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.engine

import com.xfl.msgbot.plugin.api.protocol.ProtocolVersion

/** Engine identity/metadata, reused by Phase 2 plugin discovery. */
data class EngineDescriptor(
    val engineId: String,
    val displayName: String,
    val languages: List<String>,
    val protocolVersion: Int = ProtocolVersion.CURRENT,
)
