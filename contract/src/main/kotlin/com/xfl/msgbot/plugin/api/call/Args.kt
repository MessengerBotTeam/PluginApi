/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.api.call

import com.xfl.msgbot.plugin.api.value.Value
import com.xfl.msgbot.plugin.api.value.asArrayOrNull
import com.xfl.msgbot.plugin.api.value.asBooleanOrNull
import com.xfl.msgbot.plugin.api.value.asBytesOrNull
import com.xfl.msgbot.plugin.api.value.asDoubleOrNull
import com.xfl.msgbot.plugin.api.value.asLongOrNull
import com.xfl.msgbot.plugin.api.value.asObjectOrNull
import com.xfl.msgbot.plugin.api.value.asStringOrNull

/**
 * Named call arguments, already validated against the schema. Use plain accessors for required
 * parameters and `OrNull` ones for optional parameters.
 */
class Args(val values: Map<String, Value>) {
    operator fun get(name: String): Value = values[name] ?: Value.VNull

    fun has(name: String): Boolean = get(name) != Value.VNull

    fun string(name: String): String = stringOrNull(name) ?: missing(name, "string")

    fun stringOrNull(name: String): String? = get(name).asStringOrNull()

    fun long(name: String): Long = longOrNull(name) ?: missing(name, "int")

    fun longOrNull(name: String): Long? = get(name).asLongOrNull()

    fun int(name: String): Int = intOrNull(name) ?: missing(name, "32-bit int")

    fun intOrNull(name: String): Int? = longOrNull(name)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    fun double(name: String): Double = doubleOrNull(name) ?: missing(name, "double")

    fun doubleOrNull(name: String): Double? = get(name).asDoubleOrNull()

    fun bool(name: String): Boolean = boolOrNull(name) ?: missing(name, "bool")

    fun boolOrNull(name: String): Boolean? = get(name).asBooleanOrNull()

    fun bytes(name: String): ByteArray = bytesOrNull(name) ?: missing(name, "bytes")

    fun bytesOrNull(name: String): ByteArray? = get(name).asBytesOrNull()

    fun list(name: String): List<Value> = listOrNull(name) ?: missing(name, "list")

    fun listOrNull(name: String): List<Value>? = get(name).asArrayOrNull()

    fun map(name: String): Map<String, Value> = mapOrNull(name) ?: missing(name, "map")

    fun mapOrNull(name: String): Map<String, Value>? = get(name).asObjectOrNull()

    override fun toString(): String = "Args($values)"

    private fun missing(
        name: String,
        type: String,
    ): Nothing = throw CallException.badArgs("'$name' must be a $type")

    companion object {
        val NONE = Args(emptyMap())

        fun of(vararg pairs: Pair<String, Any?>): Args = Args(pairs.associate { (k, v) -> k to Value.of(v) })
    }
}
