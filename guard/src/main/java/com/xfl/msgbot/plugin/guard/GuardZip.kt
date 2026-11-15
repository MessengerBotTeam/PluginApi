/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.guard

import com.xfl.msgbot.plugin.api.engine.HostFileMode
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.util.Calendar
import java.util.Collections
import java.util.Enumeration
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.Manifest
import java.util.stream.Stream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException

/**
 * `java.util.zip.ZipFile` for scripts. The real class opens the path itself, so this one reads the archive through a
 * descriptor of the host: it reads the central directory like the real class does, so sizes, comments and random
 * access to entries are the same. Encrypted entries and compression methods other than stored and deflated are refused,
 * as by the real class.
 */
open class GuardZipFile(
    private val name: String,
    mode: Int,
    private val charset: Charset,
) : Closeable {
    // Like the real class on Android, a missing archive is a java.nio.file exception.
    private val channel: FileChannel =
        GuardRandomAccessFile(
            try {
                GuardContext.host().open(name, HostFileMode.READ)
            } catch (e: HostIOException) {
                throw GuardErrors.nio(e)
            },
        ).channel
    private val entries = LinkedHashMap<String, ZipEntry>()
    private val offsets = HashMap<String, Long>()
    private val encrypted = HashSet<String>()
    private var comment: String? = null

    @Volatile private var closed = false

    @Throws(IOException::class)
    constructor(name: String) : this(name, OPEN_READ, Charsets.UTF_8)

    @Throws(IOException::class)
    constructor(file: File) : this(file.path)

    @Throws(IOException::class)
    constructor(file: File, mode: Int) : this(file.path, mode, Charsets.UTF_8)

    @Throws(IOException::class)
    constructor(name: String, charset: Charset) : this(name, OPEN_READ, charset)

    @Throws(IOException::class)
    constructor(file: File, charset: Charset) : this(file.path, OPEN_READ, charset)

    @Throws(IOException::class)
    constructor(file: File, mode: Int, charset: Charset) : this(file.path, mode, charset)

    init {
        require((mode and OPEN_READ) != 0 && (mode and (OPEN_READ or OPEN_DELETE).inv()) == 0) { "Illegal mode: 0x${Integer.toHexString(mode)}" }
        try {
            readDirectory()
        } catch (e: Exception) {
            channel.close()
            throw if (e is ZipException) e else ZipException(e.message ?: "zip file is empty").apply { initCause(e) }
        }
        if ((mode and OPEN_DELETE) != 0) GuardContext.current().deleteOnExit(name)
    }

    private fun read(
        at: Long,
        length: Int,
    ): ByteBuffer {
        val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, at + buffer.position()) < 0) throw EOFException()
        }
        return buffer.flip() as ByteBuffer
    }

    private fun ByteBuffer.u16(at: Int) = getShort(at).toInt() and 0xffff

    private fun ByteBuffer.u32(at: Int) = getInt(at).toLong() and 0xffffffffL

    private fun readDirectory() {
        val size = channel.size()
        if (size == 0L) throw ZipException("zip file is empty")
        if (size < END_SIZE) throw ZipException("zip END header not found")
        val tailLength = minOf(size, (END_SIZE + 0xffff).toLong()).toInt()
        val tail = read(size - tailLength, tailLength)
        var end = -1
        for (i in tailLength - END_SIZE downTo 0) {
            if (tail.getInt(i) == END_SIGNATURE) {
                end = i
                break
            }
        }
        if (end < 0) throw ZipException("zip END header not found")
        var count = tail.u16(end + 10).toLong()
        var directoryOffset = tail.u32(end + 16)
        val commentLength = tail.u16(end + 20)
        if (commentLength > 0 && end + END_SIZE + commentLength <= tailLength) {
            comment = String(ByteArray(commentLength).also { tail.position(end + END_SIZE); tail.get(it) }, charset)
        }
        val endAt = size - tailLength + end
        if ((count == 0xffffL || directoryOffset == 0xffffffffL) && endAt >= 20) {
            val locator = read(endAt - 20, 20)
            if (locator.getInt(0) == ZIP64_LOCATOR_SIGNATURE) {
                val record = read(locator.getLong(8), 56)
                if (record.getInt(0) != ZIP64_END_SIGNATURE) throw ZipException("invalid zip64 END header")
                count = record.getLong(32)
                directoryOffset = record.getLong(48)
            }
        }
        var at = directoryOffset
        repeat(count.toInt()) {
            val header = read(at, CENTRAL_SIZE)
            if (header.getInt(0) != CENTRAL_SIGNATURE) throw ZipException("invalid CEN header (bad signature)")
            val flags = header.u16(8)
            val nameLength = header.u16(28)
            val extraLength = header.u16(30)
            val commentLength = header.u16(32)
            val variable = read(at + CENTRAL_SIZE, nameLength + extraLength + commentLength)
            val bytes = ByteArray(variable.limit()).also { variable.get(it) }
            val nameCharset = if ((flags and 0x800) != 0) Charsets.UTF_8 else charset
            val entry = ZipEntry(String(bytes, 0, nameLength, nameCharset))
            entry.method = header.u16(10)
            dosToMillis(header.u32(12)).takeIf { it != -1L }?.let { entry.time = it }
            entry.crc = header.u32(16)
            var compressed = header.u32(20)
            var plain = header.u32(24)
            var local = header.u32(42)
            val extra = bytes.copyOfRange(nameLength, nameLength + extraLength)
            if (extra.isNotEmpty()) {
                zip64(extra, plain == 0xffffffffL, compressed == 0xffffffffL, local == 0xffffffffL)?.let { (p, c, l) ->
                    plain = p ?: plain
                    compressed = c ?: compressed
                    local = l ?: local
                }
                entry.extra = extra
            }
            entry.size = plain
            entry.compressedSize = compressed
            if (commentLength > 0) entry.comment = String(bytes, nameLength + extraLength, commentLength, nameCharset)
            if ((flags and 1) != 0) encrypted += entry.name
            entries.putIfAbsent(entry.name, entry)
            offsets[entry.name] = local
            at += CENTRAL_SIZE + nameLength + extraLength + commentLength
        }
    }

    private fun zip64(
        extra: ByteArray,
        needPlain: Boolean,
        needCompressed: Boolean,
        needLocal: Boolean,
    ): Triple<Long?, Long?, Long?>? {
        val buffer = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.remaining() >= 4) {
            val id = buffer.short.toInt() and 0xffff
            val length = buffer.short.toInt() and 0xffff
            if (length > buffer.remaining()) return null
            if (id != 1) {
                buffer.position(buffer.position() + length)
                continue
            }
            val end = buffer.position() + length
            fun next(need: Boolean): Long? = if (need && buffer.position() + 8 <= end) buffer.long else null
            return Triple(next(needPlain), next(needCompressed), next(needLocal))
        }
        return null
    }

    private fun dosToMillis(dos: Long): Long {
        if (dos == 0L) return -1
        return Calendar.getInstance().run {
            clear()
            set(((dos shr 25) and 0x7f).toInt() + 1980, ((dos shr 21) and 0x0f).toInt() - 1, ((dos shr 16) and 0x1f).toInt(), ((dos shr 11) and 0x1f).toInt(), ((dos shr 5) and 0x3f).toInt(), ((dos shl 1) and 0x3e).toInt())
            timeInMillis
        }
    }

    private fun ensureOpen() {
        if (closed) throw IllegalStateException("zip file closed")
    }

    fun entries(): Enumeration<out ZipEntry> {
        ensureOpen()
        return Collections.enumeration(entries.values.toList())
    }

    fun stream(): Stream<out ZipEntry> {
        ensureOpen()
        return entries.values.toList().stream()
    }

    fun getEntry(name: String): ZipEntry? {
        ensureOpen()
        return entries[name] ?: entries["$name/"]
    }

    @Throws(IOException::class)
    fun getInputStream(entry: ZipEntry): InputStream? {
        ensureOpen()
        val found = entries[entry.name] ?: return null
        if (found.name in encrypted) throw ZipException("invalid CEN header (encrypted entry)")
        val local = read(offsets.getValue(found.name), LOCAL_SIZE)
        if (local.getInt(0) != LOCAL_SIGNATURE) throw ZipException("invalid LOC header (bad signature)")
        val data = offsets.getValue(found.name) + LOCAL_SIZE + local.u16(26) + local.u16(28)
        val raw = Range(data, found.compressedSize)
        return when (found.method) {
            ZipEntry.STORED -> raw
            ZipEntry.DEFLATED ->
                object : InflaterInputStream(raw, Inflater(true), 8192) {
                    private var eof = false

                    // A raw inflater may want one byte past the data; the real ZipFile gives it a zero too.
                    override fun fill() {
                        if (eof) throw EOFException("Unexpected end of ZLIB input stream")
                        len = `in`.read(buf, 0, buf.size)
                        if (len == -1) {
                            buf[0] = 0
                            len = 1
                            eof = true
                        }
                        inf.setInput(buf, 0, len)
                    }

                    override fun available(): Int = if (inf.finished()) 0 else maxOf(0L, found.size - inf.bytesWritten).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

                    override fun close() {
                        super.close()
                        inf.end()
                    }
                }
            else -> throw ZipException("invalid compression method")
        }
    }

    private inner class Range(
        private var at: Long,
        private var left: Long,
    ) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            ensureOpen()
            if (left <= 0) return -1
            if (length == 0) return 0
            val count = channel.read(ByteBuffer.wrap(buffer, offset, minOf(length.toLong(), left).toInt()), at)
            if (count <= 0) return -1
            at += count
            left -= count
            return count
        }

        override fun available() = left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        override fun skip(n: Long): Long = minOf(n, left).coerceAtLeast(0).also {
            at += it
            left -= it
        }
    }

    fun getName(): String = name

    fun size(): Int {
        ensureOpen()
        return entries.size
    }

    fun getComment(): String? {
        ensureOpen()
        return comment
    }

    override fun close() {
        if (!closed) {
            closed = true
            channel.close()
        }
    }

    override fun toString() = name

    companion object {
        const val OPEN_READ = 0x1
        const val OPEN_DELETE = 0x4

        // java.util.zip.ZipConstants, which ZipFile and JarFile expose.
        const val LOCSIG = 0x04034b50L
        const val EXTSIG = 0x08074b50L
        const val CENSIG = 0x02014b50L
        const val ENDSIG = 0x06054b50L
        const val LOCHDR = 30
        const val EXTHDR = 16
        const val CENHDR = 46
        const val ENDHDR = 22
        const val LOCVER = 4
        const val LOCFLG = 6
        const val LOCHOW = 8
        const val LOCTIM = 10
        const val LOCCRC = 14
        const val LOCSIZ = 18
        const val LOCLEN = 22
        const val LOCNAM = 26
        const val LOCEXT = 28
        const val EXTCRC = 4
        const val EXTSIZ = 8
        const val EXTLEN = 12
        const val CENVEM = 4
        const val CENVER = 6
        const val CENFLG = 8
        const val CENHOW = 10
        const val CENTIM = 12
        const val CENCRC = 16
        const val CENSIZ = 20
        const val CENLEN = 24
        const val CENNAM = 28
        const val CENEXT = 30
        const val CENCOM = 32
        const val CENDSK = 34
        const val CENATT = 36
        const val CENATX = 38
        const val CENOFF = 42
        const val ENDSUB = 8
        const val ENDTOT = 10
        const val ENDSIZ = 12
        const val ENDOFF = 16
        const val ENDCOM = 20

        private const val END_SIGNATURE = 0x06054b50
        private const val ZIP64_END_SIGNATURE = 0x06064b50
        private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50
        private const val CENTRAL_SIGNATURE = 0x02014b50
        private const val LOCAL_SIGNATURE = 0x04034b50
        private const val END_SIZE = 22
        private const val CENTRAL_SIZE = 46
        private const val LOCAL_SIZE = 30
    }
}

/** `java.util.jar.JarFile` on the same terms as [GuardZipFile]; signatures are not checked. */
class GuardJarFile(
    name: String,
    verify: Boolean,
    mode: Int,
) : GuardZipFile(name, mode, Charsets.UTF_8) {
    @Throws(IOException::class)
    constructor(name: String) : this(name, true, OPEN_READ)

    @Throws(IOException::class)
    constructor(name: String, verify: Boolean) : this(name, verify, OPEN_READ)

    @Throws(IOException::class)
    constructor(file: File) : this(file.path)

    @Throws(IOException::class)
    constructor(file: File, verify: Boolean) : this(file.path, verify)

    @Throws(IOException::class)
    constructor(file: File, verify: Boolean, mode: Int) : this(file.path, verify, mode)

    @Throws(IOException::class)
    fun getManifest(): Manifest? = getEntry(JarFile.MANIFEST_NAME)?.let { entry -> getInputStream(entry)?.use { Manifest(it) } }

    fun getJarEntry(name: String): JarEntry? = getEntry(name)?.let { JarEntry(it) }

    companion object {
        const val MANIFEST_NAME = JarFile.MANIFEST_NAME
    }
}
