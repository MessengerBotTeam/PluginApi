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

/** Answers what the other side of an [RpcPeer] sends. */
interface RpcHandler {
    /**
     * [reply] exactly once, from any thread, whenever the answer is ready. This runs on the
     * transport's delivery thread, so anything slow belongs on another one.
     */
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
 * One end of a symmetric request/response channel. Both sides can ask, answer and notify, so the
 * host and a plugin speak the same protocol whatever roles they play on top of it.
 *
 * Answers to [requestAsync] arrive on the transport or timeout thread; hop to your own thread
 * before touching state that lives on one.
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

    /** Blocks until the answer arrives, [timeoutMs] passes (0: never), or the peer closes. */
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
        // Closed between the check and the registration: nobody else will answer it.
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

    /** Fails everything still waiting, then closes the transport. Safe to call twice. */
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
        } catch (e: Exception) {
            onProtocolError("Handling the answer to '${call.method}' failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun onFrame(bytes: ByteArray) {
        if (closed.get()) return
        val frame =
            try {
                ValueCodec.decode(bytes, transport.bytes).asObjectOrNull() ?: throw MalformedFrameException("A frame is a map")
            } catch (e: Exception) {
                onProtocolError("Dropped a malformed frame: ${e.message}")
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
                        send(frame(RESPONSE, ID to Value.VInt(id), *resultFields(result))) { onProtocolError("Could not answer '$method': $it") }
                    }
                }
                try {
                    handler.onRequest(method, frame[PARAMS] ?: Value.VNull, reply)
                } catch (e: Exception) {
                    reply(CallResult.failed(e.message ?: e.javaClass.simpleName))
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
                } catch (e: Exception) {
                    onProtocolError("Handling '$method' failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            else -> onProtocolError("A frame of unknown kind")
        }
    }

    private inline fun send(
        frame: Value,
        onFailure: (String) -> Unit,
    ) {
        try {
            transport.send(ValueCodec.encode(frame, transport.bytes))
        } catch (e: Exception) {
            onFailure(e.message ?: e.javaClass.simpleName)
        }
    }

    private companion object {
        const val KIND = "t"
        const val ID = "i"
        const val METHOD = "m"
        const val PARAMS = "p"
        const val VALUE = "v"
        const val ERROR_CODE = "e"
        const val ERROR_MESSAGE = "x"

        const val REQUEST = 1L
        const val RESPONSE = 2L
        const val NOTIFY = 3L

        val TIMER =
            ScheduledThreadPoolExecutor(1) { r -> Thread(r, "plugin-rpc-timeouts").apply { isDaemon = true } }
                .apply { removeOnCancelPolicy = true }

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
