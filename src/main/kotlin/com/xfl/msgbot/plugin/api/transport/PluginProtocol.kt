/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.transport

import com.xfl.msgbot.plugin.api.serialization.ValueCodec
import com.xfl.msgbot.plugin.api.value.Value

/**
 * Frame types exchanged over a [PluginTransport] and their binary encoding (via [ValueCodec]).
 *
 * Host -> plugin: [Frame.Load], [Frame.Dispatch], [Frame.Eval], [Frame.Close], [Frame.Result].
 * Plugin -> host: [Frame.HostCall], [Frame.EvalResult].
 * ([Frame.Result] answers a HostCall; [Frame.EvalResult] answers an Eval.)
 */
object PluginProtocol {
    private const val KIND = "k"
    private const val ID = "id"
    private const val METHOD = "m"
    private const val ARGS = "a"
    private const val VALUE = "v"
    private const val SHIM = "s"
    private const val SCRIPT = "c"

    private const val K_LOAD = 1L
    private const val K_DISPATCH = 2L
    private const val K_CLOSE = 3L
    private const val K_HOST_CALL = 4L
    private const val K_RESULT = 5L
    private const val K_EVAL = 6L
    private const val K_EVAL_RESULT = 7L

    sealed interface Frame {
        /** [shim] empty => the plugin supplies its own shim for [apiLevel]. */
        data class Load(val apiLevel: String, val shim: String, val userScript: String) : Frame
        data class Dispatch(val event: Value.VObject) : Frame
        object Close : Frame
        data class HostCall(val id: Long, val method: String, val args: List<Value>) : Frame
        data class Result(val id: Long, val value: Value) : Frame
        data class Eval(val id: Long, val source: String) : Frame
        data class EvalResult(val id: Long, val value: Value) : Frame
    }

    fun encode(frame: Frame): ByteArray = ValueCodec.encode(toValue(frame))

    fun decode(bytes: ByteArray): Frame = fromValue(ValueCodec.decode(bytes))

    private fun toValue(frame: Frame): Value =
        when (frame) {
            is Frame.Load -> obj(K_LOAD, ID to str(frame.apiLevel), SHIM to str(frame.shim), SCRIPT to str(frame.userScript))
            is Frame.Dispatch -> obj(K_DISPATCH, VALUE to frame.event)
            is Frame.Close -> obj(K_CLOSE)
            is Frame.HostCall -> obj(K_HOST_CALL, ID to int(frame.id), METHOD to str(frame.method), ARGS to Value.VArray(frame.args))
            is Frame.Result -> obj(K_RESULT, ID to int(frame.id), VALUE to frame.value)
            is Frame.Eval -> obj(K_EVAL, ID to int(frame.id), SCRIPT to str(frame.source))
            is Frame.EvalResult -> obj(K_EVAL_RESULT, ID to int(frame.id), VALUE to frame.value)
        }

    private fun fromValue(value: Value): Frame {
        val map = (value as Value.VObject).entries
        return when ((map.getValue(KIND) as Value.VInt).value) {
            K_LOAD -> Frame.Load(strOf(map, ID), strOf(map, SHIM), strOf(map, SCRIPT))
            K_DISPATCH -> Frame.Dispatch(map.getValue(VALUE) as Value.VObject)
            K_CLOSE -> Frame.Close
            K_HOST_CALL -> Frame.HostCall(intOf(map, ID), strOf(map, METHOD), (map.getValue(ARGS) as Value.VArray).items)
            K_RESULT -> Frame.Result(intOf(map, ID), map.getValue(VALUE))
            K_EVAL -> Frame.Eval(intOf(map, ID), strOf(map, SCRIPT))
            K_EVAL_RESULT -> Frame.EvalResult(intOf(map, ID), map.getValue(VALUE))
            else -> error("Unknown frame kind")
        }
    }

    private fun obj(kind: Long, vararg fields: Pair<String, Value>): Value.VObject =
        Value.VObject(buildMap { put(KIND, Value.VInt(kind)); fields.forEach { put(it.first, it.second) } })

    private fun str(s: String) = Value.VString(s)
    private fun int(v: Long) = Value.VInt(v)
    private fun strOf(map: Map<String, Value>, key: String) = (map.getValue(key) as Value.VString).value
    private fun intOf(map: Map<String, Value>, key: String) = (map.getValue(key) as Value.VInt).value
}
