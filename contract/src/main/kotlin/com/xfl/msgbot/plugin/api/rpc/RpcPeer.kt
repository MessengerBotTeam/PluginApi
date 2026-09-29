/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.api.rpc

import com.xfl.msgbot.plugin.api.call.CallResult
import com.xfl.msgbot.plugin.api.serialization.MalformedFrameException
import com.xfl.msgbot.plugin.api.serialization.ValueCodec
import com.xfl.msgbot.plugin.api.value.Value
import com.xfl.msgbot.plugin.api.value.asLongOrNull
import com.xfl.msgbot.plugin.api.value.asObjectOrNull
import com.xfl.msgbot.plugin.api.value.asStringOrNull
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

interface RpcHandler {
    /** Call [reply] exactly once, from any thread. Runs on the delivery thread, so offload slow work. */
    fun onRequest(
        method: String,
        params: Value,
        reply: (CallResult) -> Unit,
    )

    fun onNotify(
        method: String,
        params: Value,
    ) = Unit
}

/**
 * One end of a symmetric request/response/notify channel.
 *
 * [requestAsync] callbacks run on the transport or timeout thread.
 */
class RpcPeer(
    private val transport: PluginTransport,
    private val handler: RpcHandler,
    private val onProtocolError: (String) -> Unit = {},
) : AutoCloseable {
    private class Pending(val method: String, val onResult: (CallResult) -> Unit) {
        @Volatile var timeout: ScheduledFuture<*>? = null
    }

    private val seq = AtomicLong()
    private val pending = ConcurrentHashMap<Long, Pending>()
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean get() = closed.get()

    init {
        transport.setListener(::onFrame)
    }

    /** Blocks until answered, timed out ([timeoutMs] 0 means no timeout), or closed. */
    fun request(
        method: String,
        params: Value = Value.VNull,
        timeoutMs: Long,
    ): CallResult {
        val answer = CompletableFuture<CallResult>()
        requestAsync(method, params, timeoutMs, answer::complete)
        return try {
            answer.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            CallResult.unavailable("Interrupted while waiting for '$method'")
        }
    }

    fun requestAsync(
        method: String,
        params: Value = Value.VNull,
        timeoutMs: Long,
        onResult: (CallResult) -> Unit,
    ) {
        if (closed.get()) {
            onResult(CallResult.unavailable("The connection is closed"))
            return
        }
        val id = seq.incrementAndGet()
        val call = Pending(method, onResult)
        pending[id] = call
        if (timeoutMs > 0) {
            call.timeout =
                TIMER.schedule(
                    { complete(id, CallResult.unavailable("No answer to '$method' within ${timeoutMs}ms")) },
                    timeoutMs,
                    TimeUnit.MILLISECONDS,
                )
        }
        // close() may have run after the check above and missed this entry.
        if (closed.get()) {
            complete(id, CallResult.unavailable("The connection is closed"))
            return
        }
        send(frame(REQUEST, ID to Value.VInt(id), METHOD to Value.VString(method), PARAMS to params)) {
            complete(id, CallResult.unavailable("Could not send '$method': $it"))
        }
    }

    fun notify(
        method: String,
        params: Value = Value.VNull,
    ) {
        if (closed.get()) return
        send(frame(NOTIFY, METHOD to Value.VString(method), PARAMS to params)) { onProtocolError("Could not send '$method': $it") }
    }

    /** Fails pending requests and closes the transport. Idempotent. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending.keys.toList().forEach { complete(it, CallResult.unavailable("The connection closed")) }
        runCatching { transport.close() }
    }

    private fun complete(
        id: Long,
        result: CallResult,
    ) {
        val call = pending.remove(id) ?: return
        call.timeout?.cancel(false)
        try {
            call.onResult(result)
        } catch (e: Throwable) {
            onProtocolError("Handling the answer to '${call.method}' failed: ${describe(e)}")
        }
    }

    /** Catches every Throwable: anything escaping to a Binder thread crashes the process. */
    private fun onFrame(bytes: ByteArray) {
        if (closed.get()) return
        val frame =
            try {
                open(bytes)
            } catch (e: Throwable) {
                onProtocolError("Dropped a malformed frame: ${describe(e)}")
                return
            }
        when (frame[KIND]?.asLongOrNull()) {
            REQUEST -> {
                val id = frame[ID]?.asLongOrNull()
                val method = frame[METHOD]?.asStringOrNull()
                if (id == null || method == null) return onProtocolError("A request without an id or method")
                val answered = AtomicBoolean(false)
                val reply: (CallResult) -> Unit = { result ->
                    if (answered.compareAndSet(false, true) && !closed.get()) {
                        send(frame(RESPONSE, ID to Value.VInt(id), *resultFields(result))) { problem ->
                            // Unsendable result (too deep or large): send an error so the caller does not wait for its timeout.
                            val failure = CallResult.failed("The answer to '$method' could not be sent: $problem")
                            send(frame(RESPONSE, ID to Value.VInt(id), *resultFields(failure))) { onProtocolError("Could not answer '$method': $it") }
                        }
                    }
                }
                try {
                    handler.onRequest(method, frame[PARAMS] ?: Value.VNull, reply)
                } catch (e: Throwable) {
                    reply(CallResult.failed(describe(e)))
                }
            }
            RESPONSE -> {
                val id = frame[ID]?.asLongOrNull() ?: return onProtocolError("A response without an id")
                val code = frame[ERROR_CODE]?.asStringOrNull()
                complete(
                    id,
                    if (code != null) CallResult.Err(code, frame[ERROR_MESSAGE]?.asStringOrNull().orEmpty()) else CallResult.Ok(frame[VALUE] ?: Value.VNull),
                )
            }
            NOTIFY -> {
                val method = frame[METHOD]?.asStringOrNull() ?: return onProtocolError("A notification without a method")
                try {
                    handler.onNotify(method, frame[PARAMS] ?: Value.VNull)
                } catch (e: Throwable) {
                    onProtocolError("Handling '$method' failed: ${describe(e)}")
                }
            }
            else -> onProtocolError("A frame of unknown kind")
        }
    }

    /** Frames over [INLINE_FRAME_BYTES] go out of band, since many small values can still exceed a Binder transaction. */
    private inline fun send(
        frame: Value,
        onFailure: (String) -> Unit,
    ) {
        try {
            val encoded = ValueCodec.encode(frame, transport.bytes)
            require(encoded.size <= ValueCodec.MAX_SHARED_BYTES) { "a frame of ${encoded.size} bytes is more than the other side reads" }
            val outOfBand = if (encoded.size > INLINE_FRAME_BYTES) transport.bytes.offload(encoded) else null
            transport.send(
                if (outOfBand == null) {
                    encoded
                } else {
                    ValueCodec.encode(frame(SHARED, ID to Value.VInt(outOfBand), LENGTH to Value.VInt(encoded.size.toLong())))
                },
            )
        } catch (e: Exception) {
            onFailure(describe(e))
        }
    }

    private fun open(bytes: ByteArray): Map<String, Value> {
        val frame = decode(bytes)
        if (frame[KIND]?.asLongOrNull() != SHARED) return frame
        val id = frame[ID]?.asLongOrNull()
        val length = frame[LENGTH]?.asLongOrNull()
        if (id == null || length == null || length !in 0..ValueCodec.MAX_SHARED_BYTES) throw MalformedFrameException("A shared frame without a valid id or length")
        val inner = decode(transport.bytes.resolve(id, length.toInt()))
        if (inner[KIND]?.asLongOrNull() == SHARED) throw MalformedFrameException("A shared frame inside a shared frame")
        return inner
    }

    private fun decode(bytes: ByteArray): Map<String, Value> =
        ValueCodec.decode(bytes, transport.bytes).asObjectOrNull() ?: throw MalformedFrameException("A frame is a map")

    private companion object {
        const val KIND = "t"
        const val ID = "i"
        const val METHOD = "m"
        const val PARAMS = "p"
        const val VALUE = "v"
        const val ERROR_CODE = "e"
        const val ERROR_MESSAGE = "x"
        const val LENGTH = "l"

        const val REQUEST = 1L
        const val RESPONSE = 2L
        const val NOTIFY = 3L

        /** Out-of-band frame pointer: [ID] names the transfer, [LENGTH] its size. */
        const val SHARED = 4L

        const val INLINE_FRAME_BYTES = 64 * 1024

        val TIMER =
            ScheduledThreadPoolExecutor(1) { r -> Thread(r, "plugin-rpc-timeouts").apply { isDaemon = true } }
                .apply { removeOnCancelPolicy = true }

        fun describe(e: Throwable): String = e.message ?: e.javaClass.simpleName

        fun frame(
            kind: Long,
            vararg fields: Pair<String, Value>,
        ): Value = Value.VObject(mapOf(KIND to Value.VInt(kind), *fields))

        fun resultFields(result: CallResult): Array<Pair<String, Value>> =
            when (result) {
                is CallResult.Ok -> arrayOf(VALUE to result.value)
                is CallResult.Err -> arrayOf(ERROR_CODE to Value.VString(result.code), ERROR_MESSAGE to Value.VString(result.message))
            }
    }
}
