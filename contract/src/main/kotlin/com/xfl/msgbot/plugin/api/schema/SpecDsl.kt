/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.api.schema

@DslMarker
annotation class SchemaDsl

/**
 * Declares a module:
 *
 * ```kotlin
 * val Weather = moduleSpec("weather") {
 *     doc = "Forecasts from the weather service."
 *     function("forecast", returns = Type.STRING) {
 *         param("city", Type.STRING)
 *         optional("days", Type.INT)
 *     }
 *     event("alert") { field("text", Type.STRING) }
 * }
 * ```
 */
fun moduleSpec(
    namespace: String,
    version: Int = 1,
    block: ModuleSpecBuilder.() -> Unit,
): ModuleSpec = ModuleSpecBuilder(namespace, version).apply(block).build()

/** An inline struct type, built the same way as event fields. */
fun Type.Companion.struct(block: FieldsBuilder.() -> Unit): Type = Type.Struct(FieldsBuilder().apply(block).fields.toList())

@SchemaDsl
open class FieldsBuilder {
    internal val fields = mutableListOf<Field>()

    fun field(
        name: String,
        type: Type,
        doc: String = "",
    ) {
        fields += Field(name, type, doc)
    }

    /** A field that may be absent; shorthand for `field(name, type.nullable())`. */
    fun optional(
        name: String,
        type: Type,
        doc: String = "",
    ) = field(name, type.nullable(), doc)
}

@SchemaDsl
open class FunctionSpecBuilder internal constructor(
    private val name: String,
    var returns: Type,
    var doc: String,
) {
    internal val params = mutableListOf<Field>()

    /** Parameters keep their declaration order, which is how positional languages pass them. */
    fun param(
        name: String,
        type: Type,
        doc: String = "",
    ) {
        params += Field(name, type, doc)
    }

    /** A parameter that may be left out. Keep these after the required ones. */
    fun optional(
        name: String,
        type: Type,
        doc: String = "",
    ) = param(name, type.nullable(), doc)

    internal fun build(): FunctionSpec {
        val firstOptional = params.indexOfFirst { it.optional }
        require(firstOptional < 0 || params.drop(firstOptional).all { it.optional }) {
            "$name: a required parameter follows an optional one, so it cannot be passed positionally"
        }
        return FunctionSpec(name, params.toList(), returns, doc)
    }
}

@SchemaDsl
class ModuleSpecBuilder internal constructor(
    private val namespace: String,
    private val version: Int,
) {
    var doc: String = ""
    private val functions = mutableListOf<FunctionSpec>()
    private val events = mutableListOf<EventSpec>()

    fun function(
        name: String,
        returns: Type = Type.VOID,
        doc: String = "",
        block: FunctionSpecBuilder.() -> Unit = {},
    ) {
        functions += FunctionSpecBuilder(name, returns, doc).apply(block).build()
    }

    fun event(
        name: String,
        doc: String = "",
        block: FieldsBuilder.() -> Unit = {},
    ) {
        events += EventSpec(name, FieldsBuilder().apply(block).fields.toList(), doc)
    }

    internal fun build() = ModuleSpec(namespace, version, functions.toList(), events.toList(), doc)
}
