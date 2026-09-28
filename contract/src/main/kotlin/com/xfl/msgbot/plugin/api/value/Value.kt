/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.value

/**
 * Everything that crosses between the host, plugins and scripts: JSON plus 64-bit integers and
 * bytes. Each language maps this closed set exactly once, in its binding, so a value means the
 * same thing to every engine and every profile of that language.
 *
 * Host resources a script only hands back (a reply target, an image) travel as opaque strings:
 * a separate reference type would need its own mapping in every language for no gain.
 */
sealed interface Value {
    data object VNull : Value

    @JvmInline
    value class VBool(val value: Boolean) : Value

    /** 64-bit, so an ID survives every language; a JavaScript binding widens past 2^53 to BigInt. */
    @JvmInline
    value class VInt(val value: Long) : Value

    @JvmInline
    value class VDouble(val value: Double) : Value

    @JvmInline
    value class VString(val value: String) : Value

    /** Compared by content. A transport moves large ones out of the frame on its own. */
    class VBytes(val value: ByteArray) : Value {
        override fun equals(other: Any?): Boolean = other is VBytes && value.contentEquals(other.value)

        override fun hashCode(): Int = value.contentHashCode()

        override fun toString(): String = "VBytes(${value.size} bytes)"
    }

    @JvmInline
    value class VArray(val items: List<Value>) : Value

    @JvmInline
    value class VObject(val entries: Map<String, Value>) : Value

    companion object {
        val TRUE: Value = VBool(true)
        val FALSE: Value = VBool(false)

        /**
         * Converts an ordinary Kotlin value: null, Boolean, whole and floating numbers, strings,
         * ByteArray, lists/arrays, and maps with string keys, nested freely. Unit is null.
         */
        fun of(value: Any?): Value =
            when (value) {
                null, Unit -> VNull
                is Value -> value
                is Boolean -> VBool(value)
                is Byte -> VInt(value.toLong())
                is Short -> VInt(value.toLong())
                is Int -> VInt(value.toLong())
                is Long -> VInt(value)
                is Float -> VDouble(value.toDouble())
                is Double -> VDouble(value)
                is CharSequence -> VString(value.toString())
                is Char -> VString(value.toString())
                is Enum<*> -> VString(value.name)
                is ByteArray -> VBytes(value)
                is Map<*, *> ->
                    VObject(
                        value.entries.associate { (key, item) ->
                            require(key is String) { "Map keys must be strings, not ${key?.javaClass?.simpleName}" }
                            key to of(item)
                        },
                    )
                is Iterable<*> -> VArray(value.map(::of))
                is Array<*> -> VArray(value.map(::of))
                else -> throw IllegalArgumentException("${value.javaClass.name} has no Value form")
            }
    }
}

/** The plain Kotlin form of this value: null, Boolean, Long, Double, String, ByteArray, List, Map. */
fun Value.toKotlin(): Any? =
    when (this) {
        Value.VNull -> null
        is Value.VBool -> value
        is Value.VInt -> value
        is Value.VDouble -> value
        is Value.VString -> value
        is Value.VBytes -> value
        is Value.VArray -> items.map { it.toKotlin() }
        is Value.VObject -> entries.mapValues { (_, item) -> item.toKotlin() }
    }

fun vObject(vararg pairs: Pair<String, Any?>): Value.VObject = Value.VObject(pairs.associate { (key, item) -> key to Value.of(item) })

fun vArray(vararg items: Any?): Value.VArray = Value.VArray(items.map(Value::of))

fun Value.asBooleanOrNull(): Boolean? = (this as? Value.VBool)?.value

fun Value.asLongOrNull(): Long? =
    when (this) {
        is Value.VInt -> value
        is Value.VDouble -> value.takeIf { it % 1.0 == 0.0 && it >= Long.MIN_VALUE.toDouble() && it <= Long.MAX_VALUE.toDouble() }?.toLong()
        else -> null
    }

fun Value.asDoubleOrNull(): Double? =
    when (this) {
        is Value.VDouble -> value
        is Value.VInt -> value.toDouble()
        else -> null
    }

fun Value.asStringOrNull(): String? = (this as? Value.VString)?.value

fun Value.asBytesOrNull(): ByteArray? = (this as? Value.VBytes)?.value

fun Value.asArrayOrNull(): List<Value>? = (this as? Value.VArray)?.items

fun Value.asObjectOrNull(): Map<String, Value>? = (this as? Value.VObject)?.entries
