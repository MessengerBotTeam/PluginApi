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

/** Qualified names are `namespace.member`. */
object Names {
    /** One lowercase segment, so namespace ownership is plain string equality. */
    val NAMESPACE = Regex("[a-z][a-z0-9]*")

    val MEMBER = Regex("[A-Za-z][A-Za-z0-9_]*")

    fun qualify(
        namespace: String,
        member: String,
    ): String = "$namespace.$member"

    /** `bot.reply` -> (`bot`, `reply`); null if malformed. */
    fun split(qualified: String): Pair<String, String>? {
        val dot = qualified.indexOf('.')
        if (dot < 0 || qualified.indexOf('.', dot + 1) >= 0) return null
        val namespace = qualified.substring(0, dot)
        val member = qualified.substring(dot + 1)
        return if (namespace.matches(NAMESPACE) && member.matches(MEMBER)) namespace to member else null
    }
}

/** A function a script can call. */
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

    /** Returns why [args] are invalid, or null. */
    fun checkArgs(args: Map<String, Value>): String? = Type.checkFields(params, args, "")

    fun checkResult(value: Value): String? = returns.check(value, "result")

    /** Drops result fields [returns] does not declare. */
    fun conformResult(value: Value): Value = returns.conform(value)
}

/** An event a script can listen for. The payload is a struct of [fields]. */
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

    /** Drops payload fields not in [fields]. */
    fun conform(payload: Map<String, Value>): Map<String, Value> = conformFields(fields, payload)
}

/**
 * The typed functions and events of one namespace. The host validates calls and events against it;
 * engines pass it to profiles as data.
 *
 * Bump [version] only for incompatible signature changes, not for added functions or optional parameters.
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

    /** This module limited to [functions] and [events], for partial implementations. */
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
     * Maps a provider's partial implementation [part], possibly from an older or newer edition,
     * onto this edition. Members unknown here go to [Fit.Accepted.ignored]; kept members use this
     * edition's signatures, and [Fit.Accepted.accepts] lists the parameters the provider takes.
     * Refused on a version mismatch, a changed type, or a required field missing on one side.
     */
    fun fit(part: ModuleSpec): Fit {
        if (part.namespace != namespace) return Fit.Refused("it implements '${part.namespace}', not '$namespace'")
        if (part.version != version) return Fit.Refused("it implements $namespace v${part.version}, the standard is v$version")
        val ignored = mutableListOf<String>()
        val accepts = linkedMapOf<String, Set<String>>()
        val functions =
            part.functions.mapNotNull { declared ->
                val member = qualified(declared.name)
                val standard = function(declared.name) ?: return@mapNotNull null.also { ignored += member }
                if (!declared.returns.fits(standard.returns)) return Fit.Refused("$member answers ${declared.returns}, not ${standard.returns}")
                fieldsProblem(member, "parameter", declared.params, standard.params)?.let { return Fit.Refused(it) }
                val names = declared.params.map { it.name }.toSet()
                names.filter { standard.params.none { p -> p.name == it } }.forEach { ignored += "$member($it)" }
                accepts[declared.name] = standard.params.map { it.name }.filterTo(linkedSetOf()) { it in names }
                standard
            }
        val events =
            part.events.mapNotNull { declared ->
                val member = qualified(declared.name)
                val standard = event(declared.name) ?: return@mapNotNull null.also { ignored += member }
                fieldsProblem(member, "field", declared.fields, standard.fields)?.let { return Fit.Refused(it) }
                declared.fields.filter { standard.fields.none { f -> f.name == it.name } }.forEach { ignored += "$member.${it.name}" }
                standard
            }
        return Fit.Accepted(copy(functions = functions, events = events), ignored, accepts)
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

    /** Result of [ModuleSpec.read]: the spec and the members skipped, with reasons. */
    data class Read(val spec: ModuleSpec, val skipped: List<String>)

    companion object {
        /** Like [read], discarding the skipped list. */
        fun fromValue(value: Value): ModuleSpec = read(value).spec

        /**
         * Reads what [toValue] wrote. Unreadable members (e.g. using a newer type) go to
         * [Read.skipped]; throws [IllegalArgumentException] only if the module itself is unreadable.
         */
        fun read(value: Value): Read {
            val map = value.asObjectOrNull() ?: throw IllegalArgumentException("A module spec is a map")
            val namespace = map.string("namespace")
            val skipped = mutableListOf<String>()

            fun <T> members(
                key: String,
                name: (T) -> String,
                parse: (Map<String, Value>) -> T,
            ): List<T> {
                val seen = mutableSetOf<String>()
                return map.list(key).mapIndexedNotNull { i, item ->
                    val fields = item.asObjectOrNull()
                    val label = "$namespace.${fields?.get("name")?.asStringOrNull() ?: "$key[$i]"}"
                    try {
                        parse(fields ?: throw IllegalArgumentException("not a map")).takeIf { seen.add(name(it)) }
                            ?: null.also { skipped += "$label: declared twice" }
                    } catch (e: IllegalArgumentException) {
                        skipped += "$label: ${e.message}"
                        null
                    }
                }
            }
            val functions =
                members("functions", FunctionSpec::name) { fm ->
                    FunctionSpec(
                        name = fm.string("name"),
                        params = fieldsOf(fm["params"]),
                        returns = Type.parse(fm["returns"]?.asStringOrNull() ?: "void"),
                        doc = fm["doc"]?.asStringOrNull().orEmpty(),
                    )
                }
            val events =
                members("events", EventSpec::name) { em ->
                    EventSpec(em.string("name"), fieldsOf(em["fields"]), em["doc"]?.asStringOrNull().orEmpty())
                }
            val spec =
                ModuleSpec(
                    namespace = namespace,
                    version = (map["version"]?.asLongOrNull() ?: 1L).toInt(),
                    functions = functions,
                    events = events,
                    doc = map["doc"]?.asStringOrNull().orEmpty(),
                )
            return Read(spec, skipped)
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

/** Result of [ModuleSpec.fit]. */
sealed interface Fit {
    /** [ignored] lists provider members unknown to the host; [accepts] maps each function to the parameters the provider takes. */
    data class Accepted(
        val spec: ModuleSpec,
        val ignored: List<String>,
        val accepts: Map<String, Set<String>> = spec.functions.associate { f -> f.name to f.params.map { it.name }.toSet() },
    ) : Fit

    data class Refused(val reason: String) : Fit
}
