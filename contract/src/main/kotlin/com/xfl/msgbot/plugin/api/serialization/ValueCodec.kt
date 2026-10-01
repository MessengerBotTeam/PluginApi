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
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Out-of-band channel for large byte payloads (e.g. shared memory). The frame keeps only an ID and length. */
interface BytesChannel {
    /** Returns a transfer ID, or null to keep [bytes] inline. */
    fun offload(bytes: ByteArray): Long?

    /** Like [offload] for a whole encoded frame, whatever its size; null when this channel cannot. */
    fun offloadFrame(frame: ByteArray): Long? = offload(frame)

    /** Throws if [transferId] is unknown. */
    fun resolve(
        transferId: Long,
        length: Int,
    ): ByteArray

    companion object {
        /** Keeps all bytes inline. */
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
 * Tagged binary encoding of [Value]. Frames come from other apps, so decoding checks every length
 * against the remaining input and bounds nesting and allocation, throwing [MalformedFrameException].
 */
object ValueCodec {
    /** Bindings reject deeper values at the script boundary. */
    const val MAX_VALUE_DEPTH = 64

    /** [MAX_VALUE_DEPTH] plus room for the frame envelope. */
    const val MAX_DEPTH = MAX_VALUE_DEPTH + 8

    /** Limit for one out-of-band payload and for a frame's total. */
    const val MAX_SHARED_BYTES = 64 * 1024 * 1024

    /** Values are much larger in memory than on the wire, so cap the count per frame. */
    const val MAX_VALUES = 1 shl 20

    /**
     * Byte payloads one frame sends out of band; later ones stay inline, and the frame as a whole
     * goes out of band. Bounds the regions that wait on the other side for their frame.
     */
    const val MAX_OFFLOADS = 16

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

    /**
     * Throws [IllegalArgumentException] for a value [decode] would refuse, before sending any of
     * it, so the caller can answer with an error instead of the other side dropping the frame.
     */
    fun encode(
        value: Value,
        bytes: BytesChannel = BytesChannel.INLINE,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { write(it, value, bytes, 0, Budget()) }
        return out.toByteArray()
    }

    fun decode(
        frame: ByteArray,
        bytes: BytesChannel = BytesChannel.INLINE,
    ): Value {
        val buffer = ByteBuffer.wrap(frame)
        val value =
            try {
                read(buffer, bytes, 0, Budget())
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
        budget: Budget,
    ) {
        require(depth <= MAX_DEPTH) { "Values nest deeper than $MAX_DEPTH" }
        require(++budget.values <= MAX_VALUES) { "A frame holds more than $MAX_VALUES values" }
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
                writeBytes(out, utf8(value.value))
            }
            is Value.VBytes -> {
                val size = value.value.size
                require(size <= MAX_SHARED_BYTES) { "$size bytes are more than one value may carry ($MAX_SHARED_BYTES)" }
                val transfer =
                    if (budget.offloads < MAX_OFFLOADS && budget.sharedBytes + size <= MAX_SHARED_BYTES) channel.offload(value.value) else null
                if (transfer != null) {
                    budget.offloads++
                    budget.sharedBytes += size
                    out.writeByte(T_SHARED_BYTES)
                    out.writeLong(transfer)
                    out.writeInt(size)
                } else {
                    out.writeByte(T_BYTES)
                    writeBytes(out, value.value)
                }
            }
            is Value.VArray -> {
                out.writeByte(T_ARRAY)
                out.writeInt(value.items.size)
                value.items.forEach { write(out, it, channel, depth + 1, budget) }
            }
            is Value.VObject -> {
                out.writeByte(T_OBJECT)
                out.writeInt(value.entries.size)
                value.entries.forEach { (key, item) ->
                    writeBytes(out, utf8(key))
                    write(out, item, channel, depth + 1, budget)
                }
            }
        }
    }

    /** Allocation so far for one frame. */
    private class Budget {
        var values = 0

        /** Items the collections read so far declare, so nested ones cannot each claim the whole budget up front. */
        var declared = 0L
        var sharedBytes = 0L
        var offloads = 0
    }

    private fun read(
        buffer: ByteBuffer,
        channel: BytesChannel,
        depth: Int,
        budget: Budget,
    ): Value {
        if (depth > MAX_DEPTH) throw MalformedFrameException("Values nest deeper than $MAX_DEPTH")
        if (++budget.values > MAX_VALUES) throw MalformedFrameException("A frame holds more than $MAX_VALUES values")
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
                budget.sharedBytes += length
                if (length < 0 || budget.sharedBytes > MAX_SHARED_BYTES) {
                    throw MalformedFrameException("Shared payloads of ${budget.sharedBytes} bytes")
                }
                Value.VBytes(channel.resolve(transfer, length))
            }
            T_ARRAY -> {
                // Each item needs at least a tag byte.
                val count = count(buffer, perItem = 1, budget)
                Value.VArray(List(count) { read(buffer, channel, depth + 1, budget) })
            }
            T_OBJECT -> {
                val count = count(buffer, perItem = 5, budget)
                val entries = LinkedHashMap<String, Value>(count)
                repeat(count) { entries[String(readBytes(buffer), Charsets.UTF_8)] = read(buffer, channel, depth + 1, budget) }
                Value.VObject(entries)
            }
            else -> throw MalformedFrameException("Unknown value tag $tag")
        }
    }

    private fun count(
        buffer: ByteBuffer,
        perItem: Int,
        budget: Budget,
    ): Int {
        val count = buffer.int
        if (count < 0 || count > buffer.remaining() / perItem) {
            throw MalformedFrameException("Collection of $count items in ${buffer.remaining()} bytes")
        }
        // Check before allocating the collection.
        budget.declared += count
        if (budget.declared >= MAX_VALUES) throw MalformedFrameException("A frame holds more than $MAX_VALUES values")
        return count
    }

    private fun writeBytes(
        out: DataOutputStream,
        bytes: ByteArray,
    ) {
        require(out.size().toLong() + bytes.size <= MAX_SHARED_BYTES) { "A frame is more than $MAX_SHARED_BYTES bytes" }
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    /** Like [String.toByteArray], but a lone surrogate becomes U+FFFD instead of '?'. */
    private fun utf8(text: String): ByteArray {
        if (text.none(Char::isSurrogate)) return text.toByteArray(Charsets.UTF_8)
        val encoder =
            Charsets.UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .replaceWith(REPLACEMENT)
        val buffer = encoder.encode(CharBuffer.wrap(text))
        return ByteArray(buffer.remaining()).also(buffer::get)
    }

    private val REPLACEMENT = "\uFFFD".toByteArray(Charsets.UTF_8)

    private fun readBytes(buffer: ByteBuffer): ByteArray {
        val length = buffer.int
        if (length < 0 || length > buffer.remaining()) throw MalformedFrameException("Field of $length bytes in ${buffer.remaining()}")
        return ByteArray(length).also(buffer::get)
    }
}
