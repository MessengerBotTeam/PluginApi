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
 * Blob bytes are serialized only when [Blob.Transport.Inline]. A [Blob.Transport.Shm] is written as
 * its descriptor, since the region itself is handed over out-of-band; a [BlobHook] is what turns
 * one into the other. Pipe/FileRef still degrade to a bare reference.
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
    private const val TRANSPORT_SHM = 2

    /**
     * Rewrites a blob on its way through the codec, letting a transport move big payloads
     * out-of-band ([Blob.Transport.Inline] -> [Blob.Transport.Shm] on the way out, and back on the
     * way in). Defaults to identity, which keeps everything inline.
     */
    fun interface BlobHook {
        fun apply(blob: Blob): Blob
    }

    private val identity = BlobHook { it }

    fun encode(value: Value, onBlob: BlobHook = identity): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { write(it, value, onBlob) }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray, onBlob: BlobHook = identity): Value =
        DataInputStream(ByteArrayInputStream(bytes)).use { read(it, onBlob) }

    private fun write(out: DataOutputStream, value: Value, onBlob: BlobHook) {
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
                value.items.forEach { write(out, it, onBlob) }
            }
            is Value.VObject -> {
                out.writeByte(T_OBJECT)
                out.writeInt(value.entries.size)
                value.entries.forEach { (k, v) ->
                    writeString(out, k)
                    write(out, v, onBlob)
                }
            }
            is Value.VHandle -> {
                out.writeByte(T_HANDLE)
                out.writeLong(value.id)
            }
            is Value.VBlob -> {
                out.writeByte(T_BLOB)
                writeBlob(out, onBlob.apply(value.blob))
            }
        }
    }

    private fun read(input: DataInputStream, onBlob: BlobHook): Value =
        when (val tag = input.readByte().toInt()) {
            T_NULL -> Value.VNull
            T_FALSE -> Value.VBool(false)
            T_TRUE -> Value.VBool(true)
            T_INT -> Value.VInt(input.readLong())
            T_DOUBLE -> Value.VDouble(input.readDouble())
            T_STRING -> Value.VString(readString(input))
            T_BYTES -> Value.VBytes(readBytes(input))
            T_ARRAY -> Value.VArray((0 until input.readInt()).map { read(input, onBlob) })
            T_OBJECT -> {
                val n = input.readInt()
                val entries = LinkedHashMap<String, Value>(n)
                repeat(n) { entries[readString(input)] = read(input, onBlob) }
                Value.VObject(entries)
            }
            T_HANDLE -> Value.VHandle(input.readLong())
            T_BLOB -> Value.VBlob(onBlob.apply(readBlob(input)))
            else -> error("Unknown Value tag: $tag")
        }

    private fun writeBlob(out: DataOutputStream, blob: Blob) {
        out.writeLong(blob.id)
        out.writeLong(blob.size)
        writeString(out, blob.mime)
        when (val t = blob.transport) {
            is Blob.Transport.Inline -> {
                out.writeByte(TRANSPORT_INLINE)
                writeBytes(out, t.bytes)
            }
            is Blob.Transport.Shm -> {
                out.writeByte(TRANSPORT_SHM)
                out.writeLong(t.id)
                out.writeLong(t.offset)
                out.writeLong(t.length)
            }
            else -> out.writeByte(TRANSPORT_REF)
        }
    }

    private fun readBlob(input: DataInputStream): Blob {
        val id = input.readLong()
        val size = input.readLong()
        val mime = readString(input)
        return when (input.readByte().toInt()) {
            TRANSPORT_INLINE -> Blob(id, size, mime, Blob.Transport.Inline(readBytes(input)))
            TRANSPORT_SHM -> Blob(id, size, mime, Blob.Transport.Shm(input.readLong(), input.readLong(), input.readLong()))
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
