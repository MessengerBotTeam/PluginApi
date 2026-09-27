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
object ProviderProtocol {
    private const val PROVIDER_ID = "s"
    private const val NAME = "n"
    private const val CAPS = "p"
    private const val EVENTS = "ev"
    private const val NAMESPACE = "ns"

    private const val K_START = 1L
    private const val K_STOP = 2L
    private const val K_CALL = 3L
    private const val K_EVENT = 4L
    private const val K_CALL_RESULT = 5L
    private const val K_DESCRIBE = 6L
    private const val K_START_RESULT = 7L
    private const val K_ERROR = 8L

    sealed interface Frame {
        data class Start(val id: Long) : Frame
        object Stop : Frame
        data class Call(val id: Long, val method: String, val args: List<Value>) : Frame
        data class Event(val event: Value.VObject) : Frame

        /** Answers a [Call] with what the source produced, or with why it produced nothing. */
        data class CallResult(val id: Long, val result: com.xfl.msgbot.plugin.api.bridge.CallResult) : Frame
        data class StartResult(val id: Long, val result: com.xfl.msgbot.plugin.api.bridge.CallResult) : Frame
        data class Error(val message: String) : Frame

        /** Sent once on connect: who this source is, what it can execute, and what it emits. */
        data class Describe(
            val providerId: String,
            val displayName: String,
            val capabilities: List<String>,
            val events: List<String>,
            val namespace: String,
        ) : Frame
    }

    fun encode(frame: Frame, onBlob: ValueCodec.BlobHook = ValueCodec.BlobHook { it }): ByteArray =
        ValueCodec.encode(toValue(frame), onBlob)

    fun decode(bytes: ByteArray, onBlob: ValueCodec.BlobHook = ValueCodec.BlobHook { it }): Frame =
        fromValue(ValueCodec.decode(bytes, onBlob))

    private fun toValue(frame: Frame): Value =
        with(FrameCodec) {
            when (frame) {
                is Frame.Start -> obj(K_START, ID to int(frame.id))
                is Frame.Stop -> obj(K_STOP)
                is Frame.Call -> obj(K_CALL, ID to int(frame.id), METHOD to str(frame.method), ARGS to Value.VArray(frame.args))
                is Frame.Event -> obj(K_EVENT, VALUE to frame.event)
                is Frame.CallResult -> obj(K_CALL_RESULT, ID to int(frame.id), *resultFields(frame.result))
                is Frame.StartResult -> obj(K_START_RESULT, ID to int(frame.id), *resultFields(frame.result))
                is Frame.Error -> obj(K_ERROR, VALUE to str(frame.message))
                is Frame.Describe ->
                    obj(
                        K_DESCRIBE,
                        PROVIDER_ID to str(frame.providerId),
                        NAME to str(frame.displayName),
                        CAPS to strs(frame.capabilities),
                        EVENTS to strs(frame.events),
                        NAMESPACE to str(frame.namespace),
                    )
            }
        }

    private fun fromValue(value: Value): Frame {
        val map = (value as Value.VObject).entries
        return with(FrameCodec) {
            when (kindOf(map)) {
                K_START -> Frame.Start(intOf(map, ID))
                K_STOP -> Frame.Stop
                K_CALL -> Frame.Call(intOf(map, ID), strOf(map, METHOD), (map.getValue(ARGS) as Value.VArray).items)
                K_EVENT -> Frame.Event(map.getValue(VALUE) as Value.VObject)
                K_CALL_RESULT -> Frame.CallResult(intOf(map, ID), resultOf(map))
                K_START_RESULT -> Frame.StartResult(intOf(map, ID), resultOf(map))
                K_ERROR -> Frame.Error(strOf(map, VALUE))
                K_DESCRIBE -> Frame.Describe(strOf(map, PROVIDER_ID), strOf(map, NAME), strsOf(map, CAPS), strsOf(map, EVENTS), strOf(map, NAMESPACE))
                else -> error("Unknown provider frame kind")
            }
        }
    }
}
