/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.transport

import com.xfl.msgbot.plugin.api.source.MessageSource
import java.util.concurrent.Executors

/**
 * Plugin-side endpoint for a [MessageSource], mirroring [EngineHost]. Owns the real source and
 * drives it on one dedicated thread, so a source implementation never needs its own locking.
 *
 * Describes the source to the host on construction: the host cannot know a source's capabilities
 * from the manifest alone if they depend on runtime state (a logged-out account has fewer).
 */
class SourceHost(
    private val transport: PluginTransport,
    sourceFactory: () -> MessageSource,
) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "plugin-source") }

    @Volatile private lateinit var source: MessageSource

    init {
        transport.setListener(::onFrame)
        executor.submit {
            source = sourceFactory()
            source.bindSink { event ->
                transport.send(SourceProtocol.encode(SourceProtocol.Frame.Event(event), transport.outbound()))
            }
            val d = source.descriptor
            transport.send(SourceProtocol.encode(SourceProtocol.Frame.Describe(d.sourceId, d.displayName, d.capabilities), transport.outbound()))
        }
    }

    private fun onFrame(bytes: ByteArray) {
        when (val frame = SourceProtocol.decode(bytes, transport.inbound())) {
            is SourceProtocol.Frame.Start -> executor.submit { source.start() }
            is SourceProtocol.Frame.Stop -> executor.submit { source.stop() }
            is SourceProtocol.Frame.Call -> executor.submit {
                val result = runCatching { source.call(frame.method, frame.args) }.getOrElse { com.xfl.msgbot.plugin.api.value.Value.VNull }
                transport.send(SourceProtocol.encode(SourceProtocol.Frame.CallResult(frame.id, result), transport.outbound()))
            }
            else -> Unit // Event/CallResult/Describe are source-to-host only
        }
    }
}
