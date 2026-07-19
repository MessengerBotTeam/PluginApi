/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.value

/**
 * Canonical, language-neutral value model crossing the host <-> engine boundary.
 *
 * This is a semantic model, not a wire format: in-process the objects pass through directly,
 * over IPC they are encoded (e.g. CBOR). Each engine implements a marshaller between this and
 * its native representation. Keep the type set closed: large binaries use [VBlob], non-
 * serializable host resources use [VHandle].
 */
sealed interface Value {
    object VNull : Value

    @JvmInline
    value class VBool(val value: Boolean) : Value

    /** 64-bit to absorb per-language integer widths. */
    @JvmInline
    value class VInt(val value: Long) : Value

    @JvmInline
    value class VDouble(val value: Double) : Value

    @JvmInline
    value class VString(val value: String) : Value

    /** Small binary; use [VBlob] for large payloads. */
    class VBytes(val value: ByteArray) : Value

    @JvmInline
    value class VArray(val items: List<Value>) : Value

    @JvmInline
    value class VObject(val entries: Map<String, Value>) : Value

    /** Opaque reference to a non-serializable host resource (stream, cursor, bitmap...). */
    @JvmInline
    value class VHandle(val id: Long) : Value

    /** Descriptor for a large/binary payload; bytes travel via [Blob.transport]. */
    @JvmInline
    value class VBlob(val blob: Blob) : Value
}

fun valueOf(value: Boolean): Value = Value.VBool(value)
fun valueOf(value: Int): Value = Value.VInt(value.toLong())
fun valueOf(value: Long): Value = Value.VInt(value)
fun valueOf(value: Double): Value = Value.VDouble(value)
fun valueOf(value: String): Value = Value.VString(value)
fun valueOf(value: ByteArray): Value = Value.VBytes(value)
fun valueOf(value: Blob): Value = Value.VBlob(value)

fun vObject(vararg pairs: Pair<String, Value>): Value.VObject = Value.VObject(pairs.toMap())
fun vArray(vararg items: Value): Value.VArray = Value.VArray(items.toList())

fun Value.asBooleanOrNull(): Boolean? = (this as? Value.VBool)?.value
fun Value.asLongOrNull(): Long? = when (this) {
    is Value.VInt -> value
    is Value.VDouble -> value.toLong()
    else -> null
}

fun Value.asDoubleOrNull(): Double? = when (this) {
    is Value.VDouble -> value
    is Value.VInt -> value.toDouble()
    else -> null
}

fun Value.asStringOrNull(): String? = (this as? Value.VString)?.value
fun Value.asObjectOrNull(): Map<String, Value>? = (this as? Value.VObject)?.entries
fun Value.asArrayOrNull(): List<Value>? = (this as? Value.VArray)?.items
fun Value.asHandleOrNull(): Long? = (this as? Value.VHandle)?.id
fun Value.asBlobOrNull(): Blob? = (this as? Value.VBlob)?.blob

/** Positional arg accessor; missing -> [Value.VNull]. */
fun List<Value>.arg(index: Int): Value = getOrElse(index) { Value.VNull }
