/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.transport

import com.xfl.msgbot.plugin.api.serialization.ValueCodec
import com.xfl.msgbot.plugin.api.value.Value

/**
 * Frames exchanged with a message source. Deliberately separate from [PluginProtocol]: a source
 * inverts the direction (it produces events and consumes capability calls), so sharing frame kinds
 * would only blur which side may send what.
 *
 * Host -> source: [Frame.Start], [Frame.Stop], [Frame.Call].
 * Source -> host: [Frame.Event], [Frame.CallResult], [Frame.Describe].
 */
object SourceProtocol {
    private const val KIND = "k"
    private const val ID = "id"
    private const val METHOD = "m"
    private const val ARGS = "a"
    private const val VALUE = "v"
    private const val SOURCE_ID = "s"
    private const val NAME = "n"
    private const val CAPS = "p"

    private const val K_START = 1L
    private const val K_STOP = 2L
    private const val K_CALL = 3L
    private const val K_EVENT = 4L
    private const val K_CALL_RESULT = 5L
    private const val K_DESCRIBE = 6L

    sealed interface Frame {
        object Start : Frame
        object Stop : Frame
        data class Call(val id: Long, val method: String, val args: List<Value>) : Frame
        data class Event(val event: Value.VObject) : Frame
        data class CallResult(val id: Long, val value: Value) : Frame

        /** Sent once on connect so the host learns the sourceId and what it can execute. */
        data class Describe(val sourceId: String, val displayName: String, val capabilities: List<String>) : Frame
    }

    fun encode(frame: Frame): ByteArray = ValueCodec.encode(toValue(frame))

    fun decode(bytes: ByteArray): Frame = fromValue(ValueCodec.decode(bytes))

    private fun toValue(frame: Frame): Value =
        when (frame) {
            is Frame.Start -> obj(K_START)
            is Frame.Stop -> obj(K_STOP)
            is Frame.Call ->
                obj(K_CALL, ID to Value.VInt(frame.id), METHOD to Value.VString(frame.method), ARGS to Value.VArray(frame.args))
            is Frame.Event -> obj(K_EVENT, VALUE to frame.event)
            is Frame.CallResult -> obj(K_CALL_RESULT, ID to Value.VInt(frame.id), VALUE to frame.value)
            is Frame.Describe ->
                obj(
                    K_DESCRIBE,
                    SOURCE_ID to Value.VString(frame.sourceId),
                    NAME to Value.VString(frame.displayName),
                    CAPS to Value.VArray(frame.capabilities.map(Value::VString)),
                )
        }

    private fun fromValue(value: Value): Frame {
        val map = (value as Value.VObject).entries
        return when ((map.getValue(KIND) as Value.VInt).value) {
            K_START -> Frame.Start
            K_STOP -> Frame.Stop
            K_CALL -> Frame.Call(intOf(map, ID), strOf(map, METHOD), (map.getValue(ARGS) as Value.VArray).items)
            K_EVENT -> Frame.Event(map.getValue(VALUE) as Value.VObject)
            K_CALL_RESULT -> Frame.CallResult(intOf(map, ID), map.getValue(VALUE))
            K_DESCRIBE ->
                Frame.Describe(
                    strOf(map, SOURCE_ID),
                    strOf(map, NAME),
                    (map.getValue(CAPS) as Value.VArray).items.map { (it as Value.VString).value },
                )
            else -> error("Unknown source frame kind")
        }
    }

    private fun obj(kind: Long, vararg fields: Pair<String, Value>): Value.VObject =
        Value.VObject(buildMap { put(KIND, Value.VInt(kind)); fields.forEach { put(it.first, it.second) } })

    private fun strOf(map: Map<String, Value>, key: String) = (map.getValue(key) as Value.VString).value
    private fun intOf(map: Map<String, Value>, key: String) = (map.getValue(key) as Value.VInt).value
}
