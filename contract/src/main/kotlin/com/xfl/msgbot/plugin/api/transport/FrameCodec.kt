/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.transport

import com.xfl.msgbot.plugin.api.bridge.CallResult
import com.xfl.msgbot.plugin.api.value.Value

/**
 * Field names and helpers shared by [PluginProtocol] and [ProviderProtocol]; the frame vocabularies
 * stay separate, the spelling of ids, args and failures does not.
 */
internal object FrameCodec {
    const val KIND = "k"
    const val ID = "id"
    const val METHOD = "m"
    const val ARGS = "a"
    const val VALUE = "v"

    /** Present exactly when the call failed; [VALUE] is then absent. */
    const val ERROR_CODE = "e"
    const val ERROR_MESSAGE = "em"

    fun obj(kind: Long, vararg fields: Pair<String, Value>): Value.VObject =
        Value.VObject(buildMap { put(KIND, Value.VInt(kind)); fields.forEach { put(it.first, it.second) } })

    fun str(s: String) = Value.VString(s)

    fun int(v: Long) = Value.VInt(v)

    fun strs(items: List<String>) = Value.VArray(items.map(Value::VString))

    fun kindOf(map: Map<String, Value>): Long = (map.getValue(KIND) as Value.VInt).value

    fun strOf(map: Map<String, Value>, key: String): String = (map.getValue(key) as Value.VString).value

    fun intOf(map: Map<String, Value>, key: String): Long = (map.getValue(key) as Value.VInt).value

    fun strsOf(map: Map<String, Value>, key: String): List<String> =
        (map.getValue(key) as Value.VArray).items.map { (it as Value.VString).value }

    /** The fields answering a call: the value on success, the code and message on failure. */
    fun resultFields(result: CallResult): Array<Pair<String, Value>> =
        when (result) {
            is CallResult.Ok -> arrayOf(VALUE to result.value)
            is CallResult.Err -> arrayOf(ERROR_CODE to str(result.code), ERROR_MESSAGE to str(result.message))
        }

    /** A result body read back: an error when one is named, the value otherwise. */
    fun resultOf(map: Map<String, Value>): CallResult {
        val code = map[ERROR_CODE]
        return if (code is Value.VString) {
            CallResult.Err(code.value, strOf(map, ERROR_MESSAGE))
        } else {
            CallResult.Ok(map.getValue(VALUE))
        }
    }
}
