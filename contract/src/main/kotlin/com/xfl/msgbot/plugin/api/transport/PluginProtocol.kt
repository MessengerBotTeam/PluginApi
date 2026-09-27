/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.transport

import com.xfl.msgbot.plugin.api.bridge.CallResult
import com.xfl.msgbot.plugin.api.serialization.ValueCodec
import com.xfl.msgbot.plugin.api.value.Value

/**
 * Frame types exchanged over a [PluginTransport] and their binary encoding (via [ValueCodec]).
 *
 * Host -> plugin: [Frame.Load], [Frame.Dispatch], [Frame.Eval], [Frame.Close], [Frame.Result].
 * Plugin -> host: [Frame.HostCall], [Frame.LoadResult], [Frame.DispatchResult], [Frame.EvalResult], [Frame.Error].
 * ([Frame.Result] answers a HostCall; [Frame.LoadResult] answers a Load; [Frame.EvalResult]
 * answers an Eval. [Frame.DispatchResult] acknowledges completed handlers; [Frame.Error]
 * carries failures of startup or event-loop polling.)
 */
object PluginProtocol {
    private const val SHIM = "s"
    private const val SCRIPT = "c"
    private const val CAPS = "p"
    private const val LANGUAGE = "l"
    private const val OPTIONS = "o"

    private const val K_LOAD = 1L
    private const val K_DISPATCH = 2L
    private const val K_CLOSE = 3L
    private const val K_HOST_CALL = 4L
    private const val K_RESULT = 5L
    private const val K_EVAL = 6L
    private const val K_EVAL_RESULT = 7L
    private const val K_LOAD_RESULT = 8L
    private const val K_ERROR = 9L
    private const val K_DISPATCH_RESULT = 10L

    sealed interface Frame {
        /** The profile shim is opaque source in [language]; the engine never selects an API. */
        data class Load(
            val id: Long,
            /** Which language [userScript] is written in; a polyglot engine cannot infer it. */
            val language: String,
            val capabilities: List<String>,
            val shim: String,
            val userScript: String,
            /** Per-project settings the engine declared; the host carries them without reading them. */
            val options: Map<String, String> = emptyMap(),
        ) : Frame
        data class Dispatch(val event: Value.VObject, val id: Long = 0) : Frame
        data class DispatchResult(val id: Long, val result: CallResult) : Frame
        object Close : Frame
        data class HostCall(val id: Long, val method: String, val args: List<Value>) : Frame

        /** Answers a [HostCall] with what the capability produced, or with why it produced nothing. */
        data class Result(val id: Long, val result: CallResult) : Frame

        /** Answers a [Load]: a script that cannot even load must fail the compile, not the first message. */
        data class LoadResult(val id: Long, val result: CallResult) : Frame
        data class Eval(val id: Long, val source: String) : Frame

        /** Answers an [Eval] with what it produced, or with why it produced nothing. */
        data class EvalResult(val id: Long, val result: CallResult) : Frame

        /** A failure of one-way work (a dispatch, the engine's own startup) that would otherwise stay in the plugin's log. */
        data class Error(val message: String) : Frame
    }

    fun encode(frame: Frame, onBlob: ValueCodec.BlobHook = ValueCodec.BlobHook { it }): ByteArray =
        ValueCodec.encode(toValue(frame), onBlob)

    fun decode(bytes: ByteArray, onBlob: ValueCodec.BlobHook = ValueCodec.BlobHook { it }): Frame =
        fromValue(ValueCodec.decode(bytes, onBlob))

    private fun toValue(frame: Frame): Value =
        with(FrameCodec) {
            when (frame) {
                is Frame.Load ->
                    obj(
                        K_LOAD,
                        ID to int(frame.id),
                        LANGUAGE to str(frame.language),
                        CAPS to strs(frame.capabilities),
                        SHIM to str(frame.shim),
                        SCRIPT to str(frame.userScript),
                        OPTIONS to Value.VObject(frame.options.mapValues { (_, v) -> str(v) }),
                    )
                is Frame.Dispatch -> obj(K_DISPATCH, ID to int(frame.id), VALUE to frame.event)
                is Frame.DispatchResult -> obj(K_DISPATCH_RESULT, ID to int(frame.id), *resultFields(frame.result))
                is Frame.Close -> obj(K_CLOSE)
                is Frame.HostCall -> obj(K_HOST_CALL, ID to int(frame.id), METHOD to str(frame.method), ARGS to Value.VArray(frame.args))
                is Frame.Result -> obj(K_RESULT, ID to int(frame.id), *resultFields(frame.result))
                is Frame.LoadResult -> obj(K_LOAD_RESULT, ID to int(frame.id), *resultFields(frame.result))
                is Frame.Eval -> obj(K_EVAL, ID to int(frame.id), SCRIPT to str(frame.source))
                is Frame.EvalResult -> obj(K_EVAL_RESULT, ID to int(frame.id), *resultFields(frame.result))
                is Frame.Error -> obj(K_ERROR, ERROR_MESSAGE to str(frame.message))
            }
        }

    private fun fromValue(value: Value): Frame {
        val map = (value as Value.VObject).entries
        return with(FrameCodec) {
            when (kindOf(map)) {
                K_LOAD ->
                    Frame.Load(
                        intOf(map, ID),
                        strOf(map, LANGUAGE),
                        strsOf(map, CAPS),
                        strOf(map, SHIM),
                        strOf(map, SCRIPT),
                        (map[OPTIONS] as? Value.VObject)?.entries.orEmpty().mapValues { (_, v) -> (v as Value.VString).value },
                    )
                K_DISPATCH -> Frame.Dispatch(map.getValue(VALUE) as Value.VObject, (map[ID] as? Value.VInt)?.value ?: 0)
                K_DISPATCH_RESULT -> Frame.DispatchResult(intOf(map, ID), resultOf(map))
                K_CLOSE -> Frame.Close
                K_HOST_CALL -> Frame.HostCall(intOf(map, ID), strOf(map, METHOD), (map.getValue(ARGS) as Value.VArray).items)
                K_RESULT -> Frame.Result(intOf(map, ID), resultOf(map))
                K_LOAD_RESULT -> Frame.LoadResult(intOf(map, ID), resultOf(map))
                K_EVAL -> Frame.Eval(intOf(map, ID), strOf(map, SCRIPT))
                K_EVAL_RESULT -> Frame.EvalResult(intOf(map, ID), resultOf(map))
                K_ERROR -> Frame.Error(strOf(map, ERROR_MESSAGE))
                else -> error("Unknown frame kind")
            }
        }
    }
}
