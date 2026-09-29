/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.api.schema

import com.xfl.msgbot.plugin.api.value.Value

/**
 * The type of a parameter, a return value or an event field. Written and read as a short text
 * form so a schema is as legible in a shim or an error message as it is here:
 *
 * ```
 * string   int?   list<string>   map<int>   {status: int, body: string?}
 * ```
 *
 * `T?` is the only way to say "optional": an absent argument or field and a null one are the
 * same thing, because most languages cannot tell them apart.
 */
sealed interface Type {
    /** Why [value] is not of this type, or null when it is. [path] names the value in the answer. */
    fun check(value: Value, path: String = "value"): String?

    enum class Primitive(val keyword: String) : Type {
        ANY("any"),
        BOOL("bool"),
        INT("int"),
        DOUBLE("double"),
        STRING("string"),
        BYTES("bytes"),

        /** Returns nothing; the answer is null. */
        VOID("void"),
        ;

        override fun check(value: Value, path: String): String? {
            val ok =
                when (this) {
                    ANY -> true
                    BOOL -> value is Value.VBool
                    INT -> value is Value.VInt
                    // A whole number is an int on every binding's way in; it is still a double here.
                    DOUBLE -> value is Value.VDouble || value is Value.VInt
                    STRING -> value is Value.VString
                    BYTES -> value is Value.VBytes
                    VOID -> value is Value.VNull
                }
            return if (ok) null else mismatch(path, this, value)
        }

        override fun toString(): String = keyword
    }

    data class Nullable(val inner: Type) : Type {
        init {
            require(inner !is Nullable && inner != Primitive.VOID && inner != Primitive.ANY) { "'$inner?' is not a type" }
        }

        override fun check(value: Value, path: String): String? = if (value is Value.VNull) null else inner.check(value, path)

        override fun toString(): String = "$inner?"
    }

    data class ListOf(val item: Type) : Type {
        override fun check(value: Value, path: String): String? {
            if (value !is Value.VArray) return mismatch(path, this, value)
            value.items.forEachIndexed { i, item -> this.item.check(item, "$path[$i]")?.let { return it } }
            return null
        }

        override fun toString(): String = "list<$item>"
    }

    /** String keys, values of one type. */
    data class MapOf(val value: Type) : Type {
        override fun check(value: Value, path: String): String? {
            if (value !is Value.VObject) return mismatch(path, this, value)
            value.entries.forEach { (key, item) -> this.value.check(item, "$path.$key")?.let { return it } }
            return null
        }

        override fun toString(): String = "map<$value>"
    }

    /** A fixed set of named fields. A field the schema does not name is refused: it is usually a typo. */
    data class Struct(val fields: List<Field>) : Type {
        init {
            val duplicate = fields.groupBy { it.name }.filterValues { it.size > 1 }.keys
            require(duplicate.isEmpty()) { "Fields declared twice: $duplicate" }
        }

        override fun check(value: Value, path: String): String? {
            if (value !is Value.VObject) return mismatch(path, this, value)
            return checkFields(fields, value.entries, path)
        }

        override fun toString(): String = fields.joinToString(prefix = "{", postfix = "}") { "${it.name}: ${it.type}" }
    }

    companion object {
        val ANY: Type = Primitive.ANY
        val BOOL: Type = Primitive.BOOL
        val INT: Type = Primitive.INT
        val DOUBLE: Type = Primitive.DOUBLE
        val STRING: Type = Primitive.STRING
        val BYTES: Type = Primitive.BYTES
        val VOID: Type = Primitive.VOID

        fun list(item: Type): Type = ListOf(item)

        fun map(value: Type): Type = MapOf(value)

        fun struct(vararg fields: Field): Type = Struct(fields.toList())

        /** Reads the text form back. Throws [IllegalArgumentException] naming where it went wrong. */
        fun parse(text: String): Type = TypeParser(text).parseAll()

        internal fun checkFields(
            fields: List<Field>,
            entries: Map<String, Value>,
            path: String,
        ): String? {
            fun at(name: String) = if (path.isEmpty()) name else "$path.$name"
            val known = fields.associateBy { it.name }
            entries.keys.firstOrNull { it !in known }?.let { return "${at(it)}: unknown field (expected one of ${known.keys})" }
            for (field in fields) {
                field.type.check(entries[field.name] ?: Value.VNull, at(field.name))?.let { return it }
            }
            return null
        }

        internal fun describe(value: Value): String =
            when (value) {
                Value.VNull -> "null"
                is Value.VBool -> "bool"
                is Value.VInt -> "int"
                is Value.VDouble -> "double"
                is Value.VString -> "string"
                is Value.VBytes -> "bytes"
                is Value.VArray -> "list"
                is Value.VObject -> "map"
            }

        private fun mismatch(
            path: String,
            expected: Type,
            actual: Value,
        ): String =
            if (actual is Value.VNull) "$path: required ($expected), but missing" else "$path: expected $expected, got ${describe(actual)}"
    }
}

/**
 * A named slot: a function parameter or a struct/event field. Optional exactly when its type is
 * `T?` or `any`. Two fields are equal when name and type are; [doc] is commentary and does not
 * survive the text form of a nested struct.
 */
class Field(
    val name: String,
    val type: Type,
    val doc: String = "",
) {
    init {
        require(name.matches(Names.MEMBER)) { "'$name' is not a valid name" }
    }

    val optional: Boolean get() = type is Type.Nullable || type == Type.ANY

    override fun equals(other: Any?): Boolean = other is Field && name == other.name && type == other.type

    override fun hashCode(): Int = 31 * name.hashCode() + type.hashCode()

    override fun toString(): String = "$name: $type"
}

fun Type.nullable(): Type = if (this is Type.Nullable) this else Type.Nullable(this)

/**
 * Whether this type, as a provider built against another edition of a standard declares it, can
 * stand for [standard]'s. The same type, except that a struct may lack optional fields the
 * standard added later, or carry optional ones this edition does not know yet.
 */
internal fun Type.fits(standard: Type): Boolean =
    when {
        this == standard -> true
        this is Type.Nullable && standard is Type.Nullable -> inner.fits(standard.inner)
        this is Type.ListOf && standard is Type.ListOf -> item.fits(standard.item)
        this is Type.MapOf && standard is Type.MapOf -> value.fits(standard.value)
        this is Type.Struct && standard is Type.Struct -> fieldsProblem("", "field", fields, standard.fields) == null
        else -> false
    }

/**
 * Why [declared] cannot stand for [standard] as the fields of [member], or null when it can: shared
 * fields fit, and a field only one side has is optional there.
 */
internal fun fieldsProblem(
    member: String,
    kind: String,
    declared: List<Field>,
    standard: List<Field>,
): String? {
    fun at(name: String) = if (member.isEmpty()) name else "$member's $kind '$name'"
    val byName = standard.associateBy { it.name }
    for (field in declared) {
        val expected = byName[field.name]
        when {
            expected == null && !field.optional -> return "${at(field.name)} is required, and this edition of the standard has no such $kind"
            expected != null && !field.type.fits(expected.type) -> return "${at(field.name)} is ${field.type}, not ${expected.type}"
        }
    }
    val names = declared.map { it.name }.toSet()
    standard.firstOrNull { !it.optional && it.name !in names }?.let { return "$member leaves out the required $kind '${it.name}'" }
    return null
}

/** [value] without the struct fields this type does not name, at any depth: a newer edition's additions. */
fun Type.conform(value: Value): Value =
    when {
        value is Value.VNull -> value
        this is Type.Nullable -> inner.conform(value)
        this is Type.ListOf && value is Value.VArray -> Value.VArray(value.items.map(item::conform))
        this is Type.MapOf && value is Value.VObject -> Value.VObject(value.entries.mapValues { (_, v) -> this.value.conform(v) })
        this is Type.Struct && value is Value.VObject -> Value.VObject(conformFields(fields, value.entries))
        else -> value
    }

internal fun conformFields(
    fields: List<Field>,
    entries: Map<String, Value>,
): Map<String, Value> {
    val known = fields.associateBy { it.name }
    return entries.filterKeys { it in known }.mapValues { (name, v) -> known.getValue(name).type.conform(v) }
}

/** Reads a type's text form. It comes from other apps, so its length and nesting are bounded. */
private class TypeParser(private val text: String) {
    private var pos = 0
    private var depth = 0

    fun parseAll(): Type {
        if (text.length > MAX_LENGTH) fail("longer than $MAX_LENGTH characters")
        val type = parseType()
        skipSpace()
        if (pos != text.length) fail("unexpected '${text[pos]}'")
        return type
    }

    private fun parseType(): Type {
        if (++depth > MAX_DEPTH) fail("nested deeper than $MAX_DEPTH")
        skipSpace()
        val base =
            if (peek() == '{') {
                parseStruct()
            } else {
                when (val word = word()) {
                    "list" -> Type.ListOf(parseArgument())
                    "map" -> Type.MapOf(parseArgument())
                    else -> Type.Primitive.entries.firstOrNull { it.keyword == word } ?: fail("unknown type '$word'")
                }
            }
        skipSpace()
        depth--
        return if (peek() == '?') {
            pos++
            Type.Nullable(base)
        } else {
            base
        }
    }

    private fun parseArgument(): Type {
        expect('<')
        val type = parseType()
        expect('>')
        return type
    }

    private fun parseStruct(): Type {
        expect('{')
        val fields = mutableListOf<Field>()
        skipSpace()
        if (peek() == '}') {
            pos++
            return Type.Struct(fields)
        }
        while (true) {
            skipSpace()
            val name = word()
            expect(':')
            fields += Field(name, parseType())
            skipSpace()
            when (peek()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return Type.Struct(fields)
                }
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun word(): String {
        skipSpace()
        val start = pos
        while (pos < text.length && (text[pos].isLetterOrDigit() || text[pos] == '_')) pos++
        if (start == pos) fail("expected a name")
        return text.substring(start, pos)
    }

    private fun expect(c: Char) {
        skipSpace()
        if (peek() != c) fail("expected '$c'")
        pos++
    }

    private fun peek(): Char? = text.getOrNull(pos)

    private fun skipSpace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun fail(message: String): Nothing = throw IllegalArgumentException("Bad type '${text.take(80)}' at $pos: $message")

    private companion object {
        const val MAX_LENGTH = 4096
        const val MAX_DEPTH = 32
    }
}
