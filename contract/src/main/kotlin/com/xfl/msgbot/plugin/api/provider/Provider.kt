/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.provider

import com.xfl.msgbot.plugin.api.call.Args
import com.xfl.msgbot.plugin.api.value.Value

/**
 * Publishes modules (functions and events) to scripts, in standard namespaces such as `bot` and/or
 * its own. Language-agnostic.
 *
 * ```kotlin
 * class KakaoProvider : Provider {
 *     override val modules = listOf(
 *         implement(StandardApi.Bot) {
 *             handle("send") { call -> send(call.args.stringOrNull("channelId"), call.args.string("text")) }
 *             emits("message")
 *         },
 *         provide("kakao") {
 *             function("members", returns = Type.list(Type.STRING)) {
 *                 param("channelId", Type.STRING)
 *                 handle { call -> membersOf(call.args.string("channelId")) }
 *             }
 *         },
 *     )
 * }
 * ```
 *
 * [start], [stop], [close] and all handlers run on one thread. [ProviderContext.emit] is thread-safe.
 */
interface Provider : AutoCloseable {
    /** At most one module per namespace. */
    val modules: List<ProviderModule>

    /** Starts serving [ProviderContext.projects]. The host restarts the provider when projects or options change. */
    fun start(context: ProviderContext) = Unit

    /** May be followed by another [start]. */
    fun stop() = Unit

    /** Called once, after the final [stop]. */
    override fun close() = Unit
}

interface ProviderContext {
    /** Project ID -> this provider's options for that project. */
    val projects: Map<String, Map<String, String>>

    /**
     * Sends qualified [event] (`bot.message`) to [projectId], or to all projects when null. Throws
     * if the event is not declared by this provider or [payload] does not match its schema.
     */
    fun emit(
        event: String,
        payload: Map<String, Value>,
        projectId: String? = null,
    )

    fun reportError(
        message: String,
        error: Throwable? = null,
    )
}

/** [emit] with Kotlin values converted by [Value.of]. */
fun ProviderContext.emit(
    event: String,
    vararg fields: Pair<String, Any?>,
    projectId: String? = null,
) = emit(event, fields.associate { (key, value) -> key to Value.of(value) }, projectId)

/** [options] are the calling project's settings for this provider. */
class ProviderCall(
    val projectId: String,
    val function: String,
    val args: Args,
    val options: Map<String, String> = emptyMap(),
)
