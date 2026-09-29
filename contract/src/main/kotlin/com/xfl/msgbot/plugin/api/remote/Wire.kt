/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.api.remote

import com.xfl.msgbot.plugin.api.call.CallException
import com.xfl.msgbot.plugin.api.call.CallResult
import com.xfl.msgbot.plugin.api.engine.EngineException
import com.xfl.msgbot.plugin.api.engine.LoadRequest
import com.xfl.msgbot.plugin.api.engine.ProfileScript
import com.xfl.msgbot.plugin.api.protocol.ProtocolVersion
import com.xfl.msgbot.plugin.api.schema.ModuleSpec
import com.xfl.msgbot.plugin.api.value.Value
import com.xfl.msgbot.plugin.api.value.asArrayOrNull
import com.xfl.msgbot.plugin.api.value.asLongOrNull
import com.xfl.msgbot.plugin.api.value.asObjectOrNull
import com.xfl.msgbot.plugin.api.value.asStringOrNull

/** RPC method names and parameter encoding shared by both ends. */
internal object Wire {
    // Every role.
    const val HELLO = "hello"
    const val CLOSE = "close"

    // Engine role: host -> plugin.
    const val ENGINE_LOAD = "engine.load"
    const val ENGINE_DISPATCH = "engine.dispatch"
    const val ENGINE_EVAL = "engine.eval"

    /** A notification, so it is not queued behind the busy request it stops. */
    const val ENGINE_INTERRUPT = "engine.interrupt"

    // Engine role: plugin -> host.
    const val HOST_CALL = "host.call"
    const val ENGINE_ERROR = "engine.error"

    // Provider role: host -> plugin.
    const val PROVIDER_START = "provider.start"
    const val PROVIDER_STOP = "provider.stop"
    const val PROVIDER_CALL = "provider.call"

    // Provider role: plugin -> host.
    const val PROVIDER_EMIT = "provider.emit"
    const val PROVIDER_ERROR = "provider.error"

    const val HELLO_TIMEOUT_MS = 10_000L

    fun obj(vararg fields: Pair<String, Any?>): Value.VObject = Value.VObject(fields.associate { (k, v) -> k to Value.of(v) })

    /** Host's opening request: its protocol range. */
    fun hello(): Value = obj("protocol" to ProtocolVersion.CURRENT, "minProtocol" to ProtocolVersion.MIN_SUPPORTED)

    /** Plugin's reply to [hello]: agreed protocol and, for a provider, its [modules]. Unavailable if ranges do not overlap. */
    fun answerHello(
        params: Value,
        modules: () -> List<ModuleSpec> = { emptyList() },
    ): CallResult {
        val offer = params.asObjectOrNull().orEmpty()
        val hostMax = offer["protocol"]?.asLongOrNull()?.toInt() ?: return CallResult.badArgs("The host named no protocol")
        val hostMin = offer["minProtocol"]?.asLongOrNull()?.toInt() ?: hostMax
        val agreed =
            ProtocolVersion.negotiate(hostMin, hostMax)
                ?: return CallResult.unavailable(
                    "This plugin speaks protocol ${ProtocolVersion.MIN_SUPPORTED}..${ProtocolVersion.CURRENT}; the host speaks $hostMin..$hostMax",
                )
        return CallResult.ok(obj("protocol" to agreed, "modules" to modules().map { it.toValue() }))
    }

    /** Validates the plugin's [hello] reply. */
    fun checkHello(answer: CallResult): Map<String, Value> {
        val map = answer.getOrThrow().asObjectOrNull() ?: throw IllegalStateException("The plugin answered hello with nothing")
        val protocol = map["protocol"]?.asLongOrNull()?.toInt() ?: throw IllegalStateException("The plugin named no protocol")
        check(protocol in ProtocolVersion.MIN_SUPPORTED..ProtocolVersion.CURRENT) {
            "The plugin chose protocol $protocol; this side speaks ${ProtocolVersion.MIN_SUPPORTED}..${ProtocolVersion.CURRENT}"
        }
        return map
    }

    fun loadRequest(request: LoadRequest): Value =
        obj(
            "language" to request.language,
            "api" to request.apiValue(),
            "profile" to obj("name" to request.profile.name, "source" to request.profile.source),
            "entry" to request.entry,
            "sources" to request.sources,
            "options" to request.options,
        )

    fun loadRequestOf(value: Value): LoadRequest {
        val map = value.map()
        val profile = map.getValue("profile").map()
        return LoadRequest(
            language = map.string("language"),
            api = map.getValue("api").list().map(ModuleSpec::fromValue),
            profile = ProfileScript(profile.string("name"), profile.string("source")),
            entry = map.string("entry"),
            sources = map.getValue("sources").map().mapValues { (_, v) -> v.asStringOrNull() ?: throw IllegalArgumentException("A source is text") },
            options = map["options"]?.map()?.mapValues { (_, v) -> v.asStringOrNull().orEmpty() }.orEmpty(),
        )
    }

    fun projects(projects: Map<String, Map<String, String>>): Value = Value.of(projects)

    fun projectsOf(value: Value?): Map<String, Map<String, String>> =
        value?.asObjectOrNull()?.mapValues { (_, options) -> options.map().mapValues { (_, v) -> v.asStringOrNull().orEmpty() } }.orEmpty()

    fun Value.map(): Map<String, Value> = asObjectOrNull() ?: throw IllegalArgumentException("Expected a map")

    fun Value.list(): List<Value> = asArrayOrNull() ?: throw IllegalArgumentException("Expected a list")

    fun Map<String, Value>.string(key: String): String = this[key]?.asStringOrNull() ?: throw IllegalArgumentException("'$key' is missing")

    fun CallResult.orEngineException(what: String): Value =
        when (this) {
            is CallResult.Ok -> value
            is CallResult.Err -> throw EngineException("$what failed: $message")
        }

    fun errorOf(e: Throwable): CallResult =
        when (e) {
            is CallException -> CallResult.Err(e.code, e.message ?: e.code)
            is StackOverflowError -> CallResult.failed("Stack overflow: the script recursed too deeply")
            is OutOfMemoryError -> CallResult.failed("Out of memory: ${e.message ?: "the script used more than there is"}")
            else -> CallResult.failed(e.message ?: e.javaClass.simpleName)
        }
}
