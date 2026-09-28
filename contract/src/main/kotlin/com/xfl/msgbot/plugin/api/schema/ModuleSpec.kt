/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.api.schema

import com.xfl.msgbot.plugin.api.value.Value
import com.xfl.msgbot.plugin.api.value.asArrayOrNull
import com.xfl.msgbot.plugin.api.value.asLongOrNull
import com.xfl.msgbot.plugin.api.value.asObjectOrNull
import com.xfl.msgbot.plugin.api.value.asStringOrNull

/** How names are spelled. Everything a script can call or hear is `namespace.member`. */
object Names {
    /**
     * One lowercase segment. With no dots inside a namespace, `weather` can never own anything
     * `weatherradar` does, so ownership is decided by string equality alone.
     */
    val NAMESPACE = Regex("[a-z][a-z0-9]*")

    val MEMBER = Regex("[A-Za-z][A-Za-z0-9_]*")

    fun qualify(
        namespace: String,
        member: String,
    ): String = "$namespace.$member"

    /** `bot.reply` -> (`bot`, `reply`); null for anything not spelled that way. */
    fun split(qualified: String): Pair<String, String>? {
        val dot = qualified.indexOf('.')
        if (dot < 0 || qualified.indexOf('.', dot + 1) >= 0) return null
        val namespace = qualified.substring(0, dot)
        val member = qualified.substring(dot + 1)
        return if (namespace.matches(NAMESPACE) && member.matches(MEMBER)) namespace to member else null
    }
}

/** A function a script can call: its parameters by name, and what it answers. */
data class FunctionSpec(
    val name: String,
    val params: List<Field> = emptyList(),
    val returns: Type = Type.VOID,
    val doc: String = "",
) {
    init {
        require(name.matches(Names.MEMBER)) { "'$name' is not a valid function name" }
        val duplicate = params.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicate.isEmpty()) { "Parameters declared twice in $name: $duplicate" }
    }

    /** Why [args] cannot be passed to this function, or null when they can. */
    fun checkArgs(args: Map<String, Value>): String? = Type.checkFields(params, args, "")

    fun checkResult(value: Value): String? = returns.check(value, "result")
}

/** Something a script can listen for. The payload is always a struct of [fields]. */
data class EventSpec(
    val name: String,
    val fields: List<Field> = emptyList(),
    val doc: String = "",
) {
    init {
        require(name.matches(Names.MEMBER)) { "'$name' is not a valid event name" }
        val duplicate = fields.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicate.isEmpty()) { "Fields declared twice in $name: $duplicate" }
    }

    fun checkPayload(payload: Map<String, Value>): String? = Type.checkFields(fields, payload, "")
}

/**
 * The published face of one namespace: every function and event in it, with types. This is the
 * API standard. The host checks calls and events against it, engines hand it to profiles as data,
 * and tools generate typings from it, so no language has to re-read a comment to learn a signature.
 *
 * [version] rises when a signature changes incompatibly; adding a function or an optional
 * parameter does not need it.
 */
data class ModuleSpec(
    val namespace: String,
    val version: Int = 1,
    val functions: List<FunctionSpec> = emptyList(),
    val events: List<EventSpec> = emptyList(),
    val doc: String = "",
) {
    init {
        require(namespace.matches(Names.NAMESPACE)) { "'$namespace' is not a valid namespace (one lowercase word)" }
        require(version >= 1) { "Module versions start at 1" }
        val duplicateFunctions = functions.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicateFunctions.isEmpty()) { "Functions declared twice in $namespace: $duplicateFunctions" }
        val duplicateEvents = events.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicateEvents.isEmpty()) { "Events declared twice in $namespace: $duplicateEvents" }
    }

    fun function(name: String): FunctionSpec? = functions.firstOrNull { it.name == name }

    fun event(name: String): EventSpec? = events.firstOrNull { it.name == name }

    fun qualified(member: String): String = Names.qualify(namespace, member)

    /** The same module offering only [functions] and [events]; what a partial implementation publishes. */
    fun restrictTo(
        functions: Collection<String>,
        events: Collection<String>,
    ): ModuleSpec {
        val unknownFunctions = functions.filter { function(it) == null }
        require(unknownFunctions.isEmpty()) { "$namespace has no functions $unknownFunctions" }
        val unknownEvents = events.filter { event(it) == null }
        require(unknownEvents.isEmpty()) { "$namespace has no events $unknownEvents" }
        return copy(
            functions = this.functions.filter { it.name in functions },
            events = this.events.filter { it.name in events },
        )
    }

    /**
     * Why [part] is not a compatible implementation of part of this standard, or null when it is.
     *
     * Compatible means: the same version; every function it declares exists here, answers the same
     * type and takes a subset of these parameters with the same types, every required one
     * included; every event it declares exists here and carries a subset of these fields on the
     * same terms. So a provider built before an optional parameter or field was added stays
     * compatible, and what it leaves out is simply absent from the script's `__api`.
     */
    fun incompatibility(part: ModuleSpec): String? {
        if (part.namespace != namespace) return "it implements '${part.namespace}', not '$namespace'"
        if (part.version != version) return "it implements $namespace v${part.version}, the standard is v$version"
        for (declared in part.functions) {
            val standard = function(declared.name) ?: return "$namespace has no function '${declared.name}'"
            if (declared.returns != standard.returns) return "${qualified(declared.name)} answers ${declared.returns}, not ${standard.returns}"
            subsetProblem(qualified(declared.name), "parameter", declared.params, standard.params)?.let { return it }
        }
        for (declared in part.events) {
            val standard = event(declared.name) ?: return "$namespace has no event '${declared.name}'"
            subsetProblem(qualified(declared.name), "field", declared.fields, standard.fields)?.let { return it }
        }
        return null
    }

    fun accepts(part: ModuleSpec): Boolean = incompatibility(part) == null

    private fun subsetProblem(
        member: String,
        kind: String,
        declared: List<Field>,
        standard: List<Field>,
    ): String? {
        val byName = standard.associateBy { it.name }
        for (field in declared) {
            val expected = byName[field.name] ?: return "$member has no $kind '${field.name}'"
            if (field.type != expected.type) return "$member's $kind '${field.name}' is ${field.type}, not ${expected.type}"
        }
        val names = declared.map { it.name }.toSet()
        standard.firstOrNull { !it.optional && it.name !in names }?.let { return "$member leaves out the required $kind '${it.name}'" }
        return null
    }

    fun toValue(): Value.VObject =
        obj(
            "namespace" to Value.VString(namespace),
            "version" to Value.VInt(version.toLong()),
            "doc" to Value.VString(doc),
            "functions" to
                Value.VArray(
                    functions.map { f ->
                        obj(
                            "name" to Value.VString(f.name),
                            "params" to fieldsValue(f.params),
                            "returns" to Value.VString(f.returns.toString()),
                            "doc" to Value.VString(f.doc),
                        )
                    },
                ),
            "events" to
                Value.VArray(
                    events.map { e ->
                        obj("name" to Value.VString(e.name), "fields" to fieldsValue(e.fields), "doc" to Value.VString(e.doc))
                    },
                ),
        )

    companion object {
        /** Reads what [toValue] wrote. Throws [IllegalArgumentException] for anything malformed. */
        fun fromValue(value: Value): ModuleSpec {
            val map = value.asObjectOrNull() ?: throw IllegalArgumentException("A module spec is a map")
            return ModuleSpec(
                namespace = map.string("namespace"),
                version = (map["version"]?.asLongOrNull() ?: 1L).toInt(),
                functions =
                    map.list("functions").map { f ->
                        val fm = f.asObjectOrNull() ?: throw IllegalArgumentException("A function spec is a map")
                        FunctionSpec(
                            name = fm.string("name"),
                            params = fieldsOf(fm["params"]),
                            returns = Type.parse(fm["returns"]?.asStringOrNull() ?: "void"),
                            doc = fm["doc"]?.asStringOrNull().orEmpty(),
                        )
                    },
                events =
                    map.list("events").map { e ->
                        val em = e.asObjectOrNull() ?: throw IllegalArgumentException("An event spec is a map")
                        EventSpec(em.string("name"), fieldsOf(em["fields"]), em["doc"]?.asStringOrNull().orEmpty())
                    },
                doc = map["doc"]?.asStringOrNull().orEmpty(),
            )
        }

        private fun obj(vararg pairs: Pair<String, Value>) = Value.VObject(linkedMapOf(*pairs))

        private fun fieldsValue(fields: List<Field>): Value =
            Value.VArray(
                fields.map { obj("name" to Value.VString(it.name), "type" to Value.VString(it.type.toString()), "doc" to Value.VString(it.doc)) },
            )

        private fun fieldsOf(value: Value?): List<Field> =
            (value?.asArrayOrNull() ?: emptyList()).map { f ->
                val fm = f.asObjectOrNull() ?: throw IllegalArgumentException("A field is a map")
                Field(fm.string("name"), Type.parse(fm.string("type")), fm["doc"]?.asStringOrNull().orEmpty())
            }

        private fun Map<String, Value>.string(key: String): String =
            this[key]?.asStringOrNull() ?: throw IllegalArgumentException("'$key' is missing")

        private fun Map<String, Value>.list(key: String): List<Value> = this[key]?.asArrayOrNull() ?: emptyList()
    }
}
