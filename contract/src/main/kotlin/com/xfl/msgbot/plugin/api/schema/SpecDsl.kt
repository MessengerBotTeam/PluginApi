/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.api.schema

@DslMarker
annotation class SchemaDsl

/** Declares a [ModuleSpec] with the builder DSL. */
fun moduleSpec(
    namespace: String,
    version: Int = 1,
    block: ModuleSpecBuilder.() -> Unit,
): ModuleSpec = ModuleSpecBuilder(namespace, version).apply(block).build()

/** An inline struct type. */
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

    /** Shorthand for `field(name, type.nullable())`. */
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

    /** Declaration order is the positional order. */
    fun param(
        name: String,
        type: Type,
        doc: String = "",
    ) {
        params += Field(name, type, doc)
    }

    /** Must follow all required parameters. */
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
