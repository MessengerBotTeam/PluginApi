/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.value

/**
 * Values exchanged between host, plugins and scripts: JSON plus 64-bit integers and bytes. Each
 * language binding maps this set. Opaque host handles travel as strings.
 */
sealed interface Value {
    data object VNull : Value

    @JvmInline
    value class VBool(val value: Boolean) : Value

    /** 64-bit; the JavaScript binding uses BigInt beyond 2^53. */
    @JvmInline
    value class VInt(val value: Long) : Value

    @JvmInline
    value class VDouble(val value: Double) : Value

    @JvmInline
    value class VString(val value: String) : Value

    /** Compared by content. */
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

        /** Converts a Kotlin value (primitives, strings, ByteArray, collections, string-keyed maps). Unit becomes null. */
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
        // Long.MAX_VALUE.toDouble() rounds up to 2^63, which no Long holds.
        is Value.VDouble -> value.takeIf { it % 1.0 == 0.0 && it >= Long.MIN_VALUE.toDouble() && it < -Long.MIN_VALUE.toDouble() }?.toLong()
        else -> null
    }

/** [asLongOrNull] within the range of an Int. */
fun Value.asIntOrNull(): Int? = asLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

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
