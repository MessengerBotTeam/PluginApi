/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.serialization

import com.xfl.msgbot.plugin.api.value.Blob
import com.xfl.msgbot.plugin.api.value.Value
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Dependency-free tagged binary codec for the [Value] model, used to move values across a
 * process boundary (Phase 2 IPC). Both endpoints being JVM, a compact custom format is enough;
 * swap for standard CBOR when non-JVM plugins arrive.
 *
 * Only [Blob.Transport.Inline] blobs are serialized by value. Non-inline transports (Shm/Pipe/
 * FileRef) carry an out-of-band descriptor handled by the transport layer, so the codec encodes
 * their metadata only and restores them as a [Blob.Transport.FileRef] placeholder.
 */
object ValueCodec {
    private const val T_NULL = 0
    private const val T_FALSE = 1
    private const val T_TRUE = 2
    private const val T_INT = 3
    private const val T_DOUBLE = 4
    private const val T_STRING = 5
    private const val T_BYTES = 6
    private const val T_ARRAY = 7
    private const val T_OBJECT = 8
    private const val T_HANDLE = 9
    private const val T_BLOB = 10

    private const val TRANSPORT_INLINE = 0
    private const val TRANSPORT_REF = 1

    fun encode(value: Value): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { write(it, value) }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): Value =
        DataInputStream(ByteArrayInputStream(bytes)).use { read(it) }

    private fun write(out: DataOutputStream, value: Value) {
        when (value) {
            is Value.VNull -> out.writeByte(T_NULL)
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
                writeString(out, value.value)
            }
            is Value.VBytes -> {
                out.writeByte(T_BYTES)
                writeBytes(out, value.value)
            }
            is Value.VArray -> {
                out.writeByte(T_ARRAY)
                out.writeInt(value.items.size)
                value.items.forEach { write(out, it) }
            }
            is Value.VObject -> {
                out.writeByte(T_OBJECT)
                out.writeInt(value.entries.size)
                value.entries.forEach { (k, v) ->
                    writeString(out, k)
                    write(out, v)
                }
            }
            is Value.VHandle -> {
                out.writeByte(T_HANDLE)
                out.writeLong(value.id)
            }
            is Value.VBlob -> {
                out.writeByte(T_BLOB)
                writeBlob(out, value.blob)
            }
        }
    }

    private fun read(input: DataInputStream): Value =
        when (val tag = input.readByte().toInt()) {
            T_NULL -> Value.VNull
            T_FALSE -> Value.VBool(false)
            T_TRUE -> Value.VBool(true)
            T_INT -> Value.VInt(input.readLong())
            T_DOUBLE -> Value.VDouble(input.readDouble())
            T_STRING -> Value.VString(readString(input))
            T_BYTES -> Value.VBytes(readBytes(input))
            T_ARRAY -> Value.VArray((0 until input.readInt()).map { read(input) })
            T_OBJECT -> {
                val n = input.readInt()
                val entries = LinkedHashMap<String, Value>(n)
                repeat(n) { entries[readString(input)] = read(input) }
                Value.VObject(entries)
            }
            T_HANDLE -> Value.VHandle(input.readLong())
            T_BLOB -> Value.VBlob(readBlob(input))
            else -> error("Unknown Value tag: $tag")
        }

    private fun writeBlob(out: DataOutputStream, blob: Blob) {
        out.writeLong(blob.id)
        out.writeLong(blob.size)
        writeString(out, blob.mime)
        val t = blob.transport
        if (t is Blob.Transport.Inline) {
            out.writeByte(TRANSPORT_INLINE)
            writeBytes(out, t.bytes)
        } else {
            out.writeByte(TRANSPORT_REF)
        }
    }

    private fun readBlob(input: DataInputStream): Blob {
        val id = input.readLong()
        val size = input.readLong()
        val mime = readString(input)
        return when (input.readByte().toInt()) {
            TRANSPORT_INLINE -> Blob(id, size, mime, Blob.Transport.Inline(readBytes(input)))
            else -> Blob(id, size, mime, Blob.Transport.FileRef(id))
        }
    }

    private fun writeString(out: DataOutputStream, s: String) = writeBytes(out, s.toByteArray(Charsets.UTF_8))
    private fun readString(input: DataInputStream): String = String(readBytes(input), Charsets.UTF_8)

    private fun writeBytes(out: DataOutputStream, bytes: ByteArray) {
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readBytes(input: DataInputStream): ByteArray {
        val len = input.readInt()
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return bytes
    }
}
