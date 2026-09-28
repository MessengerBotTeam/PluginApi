/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.provider

import com.xfl.msgbot.plugin.api.call.Args
import com.xfl.msgbot.plugin.api.value.Value

/**
 * Offers modules to scripts: functions they call and events they hear. A provider can publish
 * several namespaces at once, standard ones such as `bot` (any compatible part of the standard)
 * and its own; a project combines as many providers as it likes. Providers know nothing about
 * engines or languages.
 *
 * ```kotlin
 * class KakaoProvider : Provider {
 *     private var context: ProviderContext? = null
 *
 *     override val modules = listOf(
 *         implement(StandardApi.Bot) {
 *             handle("send") { call -> sendIntent(call.args.stringOrNull("channelId"), call.args.string("text")) }
 *             emits("message")
 *         },
 *         provide("kakao") {
 *             function("members", returns = Type.list(Type.STRING)) {
 *                 param("channelId", Type.STRING)
 *                 handle { call -> membersOf(call.args.string("channelId")) }
 *             }
 *         },
 *     )
 *
 *     override fun start(context: ProviderContext) { this.context = context }
 *     override fun stop() { context = null }
 * }
 * ```
 *
 * [start], [stop], [close] and every handler run on one thread, so a provider needs no locking of
 * its own. [ProviderContext.emit] may be called from any thread.
 */
interface Provider : AutoCloseable {
    /** Every namespace this provider publishes, at most one module each. */
    val modules: List<ProviderModule>

    /**
     * Begins work for the projects in [ProviderContext.projects]. When their selection or options
     * change the host stops the provider and starts it again with the new set.
     */
    fun start(context: ProviderContext) = Unit

    /** Ends what [start] began. Can be followed by another [start]. */
    fun stop() = Unit

    /** Releases the provider for good, after a final [stop]. */
    override fun close() = Unit
}

/** A provider's view of the host while it runs. */
interface ProviderContext {
    /** Project ID -> this provider's options for that project, as declared in its manifest. */
    val projects: Map<String, Map<String, String>>

    /**
     * Sends [event], qualified (`bot.message`, `kakao.read`), to one project, or to every project
     * using this provider when [projectId] is null. The event must be one of this provider's
     * modules' and [payload] must match its schema; a mistake throws here, where it was made.
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

/** [emit] with plain Kotlin values: `context.emit("weather.alert", "text" to "Rain")`. */
fun ProviderContext.emit(
    event: String,
    vararg fields: Pair<String, Any?>,
    projectId: String? = null,
) = emit(event, fields.associate { (key, value) -> key to Value.of(value) }, projectId)

/** One call from one project to a function of one module. [options] are that project's settings for this provider. */
class ProviderCall(
    val projectId: String,
    val function: String,
    val args: Args,
    val options: Map<String, String> = emptyMap(),
)
