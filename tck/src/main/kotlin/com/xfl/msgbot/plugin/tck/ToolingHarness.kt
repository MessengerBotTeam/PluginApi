/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.tck

import com.xfl.msgbot.plugin.api.remote.RemoteLanguageTools
import com.xfl.msgbot.plugin.api.remote.ToolingEndpoint
import com.xfl.msgbot.plugin.api.rpc.LoopbackTransport
import com.xfl.msgbot.plugin.api.tooling.LanguageTools
import com.xfl.msgbot.plugin.api.tooling.ToolingFactory
import com.xfl.msgbot.plugin.api.tooling.ToolingHost
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Runs tooling the way the host runs a plugin's: behind a [ToolingEndpoint], reached through
 * [RemoteLanguageTools] over a loopback transport. [files] is what the tooling may read from the host.
 */
class ToolingHarness(
    factory: ToolingFactory,
    val files: MutableMap<String, String> = linkedMapOf(),
) : AutoCloseable {
    val errors = CopyOnWriteArrayList<String>()

    private val transports = LoopbackTransport.pair()
    private val endpoint = ToolingEndpoint(transports.second, factory)
    private val reads = Executors.newCachedThreadPool { r -> Thread(r, "tooling-harness-reads").apply { isDaemon = true } }

    /** The tooling as the host sees it; its capabilities come from the plugin's hello. */
    val tools: LanguageTools =
        RemoteLanguageTools.connect(
            transports.first,
            object : ToolingHost {
                override fun read(path: String): String? = files[path]

                override fun list(path: String): List<String>? =
                    files.keys
                        .filter { it.startsWith("$path/") }
                        .map { it.removePrefix("$path/").substringBefore('/') }
                        .distinct()
                        .takeIf { it.isNotEmpty() }
            },
            reads,
            timeoutMs = 5_000,
            onError = { errors += it },
        )

    override fun close() {
        tools.close()
        endpoint.close()
        reads.shutdownNow()
    }
}
