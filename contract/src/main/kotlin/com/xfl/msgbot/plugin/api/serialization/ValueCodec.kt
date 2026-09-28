/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.serialization

import com.xfl.msgbot.plugin.api.value.Value
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * Where large byte payloads travel instead of the frame. A transport that can hand memory across
 * (shared memory over Binder) takes them out in [offload] and gives them back in [resolve]; the
 * frame keeps only an ID and a length.
 */
interface BytesChannel {
    /** An ID when [bytes] now travel out of band; null keeps them in the frame. */
    fun offload(bytes: ByteArray): Long?

    /** The bytes sent out of band under [transferId]. Throws when they never arrived. */
    fun resolve(
        transferId: Long,
        length: Int,
    ): ByteArray

    companion object {
        /** Everything stays in the frame; what an in-process transport wants. */
        val INLINE: BytesChannel =
            object : BytesChannel {
                override fun offload(bytes: ByteArray): Long? = null

                override fun resolve(
                    transferId: Long,
                    length: Int,
                ): ByteArray = throw MalformedFrameException("Out-of-band bytes on a transport that has none")
            }
    }
}

class MalformedFrameException(message: String) : IllegalArgumentException(message)

/**
 * The tagged binary form of [Value]. A frame comes from another app, so decoding trusts nothing:
 * every length is checked against what is actually left, and nesting is bounded, so a broken or
 * hostile plugin gets a [MalformedFrameException] instead of the host's memory or stack.
 */
object ValueCodec {
    const val MAX_DEPTH = 64

    /** The most one out-of-band payload may claim. */
    const val MAX_SHARED_BYTES = 64 * 1024 * 1024

    private const val T_NULL = 0
    private const val T_FALSE = 1
    private const val T_TRUE = 2
    private const val T_INT = 3
    private const val T_DOUBLE = 4
    private const val T_STRING = 5
    private const val T_BYTES = 6
    private const val T_ARRAY = 7
    private const val T_OBJECT = 8
    private const val T_SHARED_BYTES = 9

    fun encode(
        value: Value,
        bytes: BytesChannel = BytesChannel.INLINE,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { write(it, value, bytes, 0) }
        return out.toByteArray()
    }

    fun decode(
        frame: ByteArray,
        bytes: BytesChannel = BytesChannel.INLINE,
    ): Value {
        val buffer = ByteBuffer.wrap(frame)
        val value =
            try {
                read(buffer, bytes, 0)
            } catch (_: BufferUnderflowException) {
                throw MalformedFrameException("Frame ended early")
            }
        if (buffer.hasRemaining()) throw MalformedFrameException("${buffer.remaining()} bytes left after the value")
        return value
    }

    private fun write(
        out: DataOutputStream,
        value: Value,
        channel: BytesChannel,
        depth: Int,
    ) {
        require(depth <= MAX_DEPTH) { "Values nest deeper than $MAX_DEPTH" }
        when (value) {
            Value.VNull -> out.writeByte(T_NULL)
            is Value.VBool -> out.writeByte(if (value.value) T_TRUE else T_FALSE)
            is Value.VInt -> {
                out.writeByte(T_INT)
                out.writeLong(value.value)
            }
            is Value.VDouble -> {
                out.writeByte(T_DOUBLE)
                out.writeDouble(value.value)
            }
            is Value.VString -> {
                out.writeByte(T_STRING)
                writeBytes(out, value.value.toByteArray(Charsets.UTF_8))
            }
            is Value.VBytes -> {
                val transfer = channel.offload(value.value)
                if (transfer != null) {
                    out.writeByte(T_SHARED_BYTES)
                    out.writeLong(transfer)
                    out.writeInt(value.value.size)
                } else {
                    out.writeByte(T_BYTES)
                    writeBytes(out, value.value)
                }
            }
            is Value.VArray -> {
                out.writeByte(T_ARRAY)
                out.writeInt(value.items.size)
                value.items.forEach { write(out, it, channel, depth + 1) }
            }
            is Value.VObject -> {
                out.writeByte(T_OBJECT)
                out.writeInt(value.entries.size)
                value.entries.forEach { (key, item) ->
                    writeBytes(out, key.toByteArray(Charsets.UTF_8))
                    write(out, item, channel, depth + 1)
                }
            }
        }
    }

    private fun read(
        buffer: ByteBuffer,
        channel: BytesChannel,
        depth: Int,
    ): Value {
        if (depth > MAX_DEPTH) throw MalformedFrameException("Values nest deeper than $MAX_DEPTH")
        return when (val tag = buffer.get().toInt()) {
            T_NULL -> Value.VNull
            T_FALSE -> Value.VBool(false)
            T_TRUE -> Value.VBool(true)
            T_INT -> Value.VInt(buffer.long)
            T_DOUBLE -> Value.VDouble(buffer.double)
            T_STRING -> Value.VString(String(readBytes(buffer), Charsets.UTF_8))
            T_BYTES -> Value.VBytes(readBytes(buffer))
            T_SHARED_BYTES -> {
                val transfer = buffer.long
                val length = buffer.int
                if (length !in 0..MAX_SHARED_BYTES) throw MalformedFrameException("Shared payload of $length bytes")
                Value.VBytes(channel.resolve(transfer, length))
            }
            T_ARRAY -> {
                // Every item takes at least its tag byte, so a count beyond that is a lie.
                val count = count(buffer, perItem = 1)
                Value.VArray(List(count) { read(buffer, channel, depth + 1) })
            }
            T_OBJECT -> {
                val count = count(buffer, perItem = 5)
                val entries = LinkedHashMap<String, Value>(count)
                repeat(count) { entries[String(readBytes(buffer), Charsets.UTF_8)] = read(buffer, channel, depth + 1) }
                Value.VObject(entries)
            }
            else -> throw MalformedFrameException("Unknown value tag $tag")
        }
    }

    private fun count(
        buffer: ByteBuffer,
        perItem: Int,
    ): Int {
        val count = buffer.int
        if (count < 0 || count > buffer.remaining() / perItem) throw MalformedFrameException("Collection of $count items in ${buffer.remaining()} bytes")
        return count
    }

    private fun writeBytes(
        out: DataOutputStream,
        bytes: ByteArray,
    ) {
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readBytes(buffer: ByteBuffer): ByteArray {
        val length = buffer.int
        if (length < 0 || length > buffer.remaining()) throw MalformedFrameException("Field of $length bytes in ${buffer.remaining()}")
        return ByteArray(length).also(buffer::get)
    }
}
