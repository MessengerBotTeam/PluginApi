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

/**
 * The methods each role speaks over [com.xfl.msgbot.plugin.api.rpc.RpcPeer], and how their
 * parameters are spelled. Both ends are in this package, so the spelling lives in one place.
 */
internal object Wire {
    // Every role.
    const val HELLO = "hello"
    const val CLOSE = "close"

    // Engine role: host -> plugin.
    const val ENGINE_LOAD = "engine.load"
    const val ENGINE_DISPATCH = "engine.dispatch"
    const val ENGINE_EVAL = "engine.eval"

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

    fun hello(modules: List<ModuleSpec> = emptyList()): Value = obj("protocol" to ProtocolVersion.CURRENT, "modules" to modules.map { it.toValue() })

    /** The protocol a hello answer names; refuses one this side cannot speak. */
    fun checkHello(answer: CallResult): Map<String, Value> {
        val map = answer.getOrThrow().asObjectOrNull() ?: throw IllegalStateException("The plugin answered hello with nothing")
        val protocol = map["protocol"]?.asLongOrNull()?.toInt() ?: 0
        check(ProtocolVersion.isCompatible(protocol)) {
            "The plugin speaks protocol $protocol; this side speaks ${ProtocolVersion.MIN_SUPPORTED}..${ProtocolVersion.CURRENT}"
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

    /** The value, or the failure as an [EngineException] a compile or dispatch can show. */
    fun CallResult.orEngineException(what: String): Value =
        when (this) {
            is CallResult.Ok -> value
            is CallResult.Err -> throw EngineException("$what failed: $message")
        }

    fun errorOf(e: Throwable): CallResult =
        when (e) {
            is CallException -> CallResult.Err(e.code, e.message ?: e.code)
            else -> CallResult.failed(e.message ?: e.javaClass.simpleName)
        }
}
