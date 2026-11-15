/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.guard

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.xfl.msgbot.plugin.api.engine.HostFileMode
import java.io.BufferedWriter
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.FileReader
import java.io.FileWriter
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintStream
import java.io.PrintWriter
import java.io.UnsupportedEncodingException
import java.io.Writer
import java.nio.charset.Charset
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.UnsupportedCharsetException

internal fun openHost(
    path: String,
    mode: Int,
): ParcelFileDescriptor {
    val descriptor =
        try {
            GuardContext.host().open(path, mode)
        } catch (e: HostIOException) {
            throw GuardErrors.open(e)
        }
    // java.io refuses a folder even for reading (IoBridge.open); a host may hand one out.
    if (OsConstants.S_ISDIR(Os.fstat(descriptor.fileDescriptor).st_mode)) {
        descriptor.close()
        throw GuardErrors.open(HostIOException("EISDIR", path, "is a directory"))
    }
    return descriptor
}

internal fun charsetNamed(name: String): Charset =
    try {
        Charset.forName(name)
    } catch (e: IllegalCharsetNameException) {
        throw UnsupportedEncodingException(name)
    } catch (e: UnsupportedCharsetException) {
        throw UnsupportedEncodingException(name)
    }

/** `java.io.FileInputStream` over a descriptor the host opened; [getChannel] is a real read-only `FileChannel`. */
class GuardFileInputStream private constructor(pfd: ParcelFileDescriptor) : ParcelFileDescriptor.AutoCloseInputStream(pfd) {
    @Throws(FileNotFoundException::class)
    constructor(path: String) : this(openHost(path, HostFileMode.READ))

    @Throws(FileNotFoundException::class)
    constructor(file: File) : this(file.path)

    constructor(descriptor: FileDescriptor) : this(ParcelFileDescriptor.dup(descriptor))
}

/** `java.io.FileOutputStream` over a descriptor the host opened; [getChannel] is a real write-only `FileChannel`. */
class GuardFileOutputStream private constructor(pfd: ParcelFileDescriptor) : ParcelFileDescriptor.AutoCloseOutputStream(pfd) {
    @Throws(FileNotFoundException::class)
    constructor(path: String, append: Boolean) : this(openHost(path, if (append) HostFileMode.APPEND else HostFileMode.WRITE))

    @Throws(FileNotFoundException::class)
    constructor(path: String) : this(path, false)

    @Throws(FileNotFoundException::class)
    constructor(file: File, append: Boolean) : this(file.path, append)

    @Throws(FileNotFoundException::class)
    constructor(file: File) : this(file.path, false)

    constructor(descriptor: FileDescriptor) : this(ParcelFileDescriptor.dup(descriptor))
}

/**
 * `java.io.FileReader` for scripts. It is a real `FileReader`; reading goes to a reader over the host's descriptor,
 * which also lets it take any charset.
 */
class GuardFileReader private constructor(
    pfd: ParcelFileDescriptor,
    charset: Charset,
) : FileReader(pfd.fileDescriptor) {
    private val reader = InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(pfd), charset)

    @Throws(FileNotFoundException::class)
    constructor(path: String) : this(openHost(path, HostFileMode.READ), Charset.defaultCharset())

    @Throws(FileNotFoundException::class)
    constructor(file: File) : this(file.path)

    @Throws(FileNotFoundException::class)
    constructor(path: String, charset: Charset) : this(openHost(path, HostFileMode.READ), charset)

    @Throws(FileNotFoundException::class)
    constructor(file: File, charset: Charset) : this(file.path, charset)

    constructor(descriptor: FileDescriptor) : this(ParcelFileDescriptor.dup(descriptor), Charset.defaultCharset())

    override fun read() = reader.read()

    override fun read(
        buffer: CharArray,
        offset: Int,
        length: Int,
    ) = reader.read(buffer, offset, length)

    override fun ready() = reader.ready()

    override fun getEncoding(): String? = reader.encoding

    override fun close() = reader.close()
}

/** `java.io.FileWriter` for scripts, on the same terms as [GuardFileReader]. */
class GuardFileWriter private constructor(
    pfd: ParcelFileDescriptor,
    charset: Charset,
) : FileWriter(pfd.fileDescriptor) {
    private val writer = OutputStreamWriter(ParcelFileDescriptor.AutoCloseOutputStream(pfd), charset)

    @Throws(java.io.IOException::class)
    constructor(path: String, append: Boolean) : this(open(path, append), Charset.defaultCharset())

    @Throws(java.io.IOException::class)
    constructor(path: String) : this(path, false)

    @Throws(java.io.IOException::class)
    constructor(file: File, append: Boolean) : this(file.path, append)

    @Throws(java.io.IOException::class)
    constructor(file: File) : this(file.path, false)

    @Throws(java.io.IOException::class)
    constructor(path: String, charset: Charset) : this(open(path, false), charset)

    @Throws(java.io.IOException::class)
    constructor(path: String, charset: Charset, append: Boolean) : this(open(path, append), charset)

    @Throws(java.io.IOException::class)
    constructor(file: File, charset: Charset) : this(file.path, charset)

    @Throws(java.io.IOException::class)
    constructor(file: File, charset: Charset, append: Boolean) : this(file.path, charset, append)

    constructor(descriptor: FileDescriptor) : this(ParcelFileDescriptor.dup(descriptor), Charset.defaultCharset())

    override fun write(c: Int) = writer.write(c)

    override fun write(
        buffer: CharArray,
        offset: Int,
        length: Int,
    ) = writer.write(buffer, offset, length)

    override fun write(
        text: String,
        offset: Int,
        length: Int,
    ) = writer.write(text, offset, length)

    override fun flush() = writer.flush()

    override fun getEncoding(): String? = writer.encoding

    override fun close() = writer.close()

    private companion object {
        fun open(
            path: String,
            append: Boolean,
        ) = openHost(path, if (append) HostFileMode.APPEND else HostFileMode.WRITE)
    }
}

private fun fileWriter(
    path: String,
    charset: Charset,
): Writer = BufferedWriter(OutputStreamWriter(GuardFileOutputStream(path), charset))

/** `java.io.PrintWriter`; the constructors that take a file name or a `File` write through the host. */
class GuardPrintWriter : PrintWriter {
    constructor(out: Writer) : super(out)

    constructor(out: Writer, autoFlush: Boolean) : super(out, autoFlush)

    constructor(out: OutputStream) : super(out)

    constructor(out: OutputStream, autoFlush: Boolean) : super(out, autoFlush)

    constructor(out: OutputStream, autoFlush: Boolean, charset: Charset) : super(BufferedWriter(OutputStreamWriter(out, charset)), autoFlush)

    @Throws(FileNotFoundException::class)
    constructor(fileName: String) : super(fileWriter(fileName, Charset.defaultCharset()), false)

    @Throws(FileNotFoundException::class, UnsupportedEncodingException::class)
    constructor(fileName: String, csn: String) : super(fileWriter(fileName, charsetNamed(csn)), false)

    @Throws(java.io.IOException::class)
    constructor(fileName: String, charset: Charset) : super(fileWriter(fileName, charset), false)

    @Throws(FileNotFoundException::class)
    constructor(file: File) : this(file.path)

    @Throws(FileNotFoundException::class, UnsupportedEncodingException::class)
    constructor(file: File, csn: String) : this(file.path, csn)

    @Throws(java.io.IOException::class)
    constructor(file: File, charset: Charset) : this(file.path, charset)
}

/** `java.io.PrintStream`; the constructors that take a file name or a `File` write through the host. */
class GuardPrintStream : PrintStream {
    constructor(out: OutputStream) : super(out)

    constructor(out: OutputStream, autoFlush: Boolean) : super(out, autoFlush)

    @Throws(UnsupportedEncodingException::class)
    constructor(out: OutputStream, autoFlush: Boolean, encoding: String) : super(out, autoFlush, encoding)

    constructor(out: OutputStream, autoFlush: Boolean, charset: Charset) : super(out, autoFlush, charset.name())

    @Throws(FileNotFoundException::class)
    constructor(fileName: String) : super(GuardFileOutputStream(fileName), false)

    @Throws(FileNotFoundException::class, UnsupportedEncodingException::class)
    constructor(fileName: String, csn: String) : super(GuardFileOutputStream(fileName), false, charsetNamed(csn).name())

    @Throws(java.io.IOException::class)
    constructor(fileName: String, charset: Charset) : super(GuardFileOutputStream(fileName), false, charset.name())

    @Throws(FileNotFoundException::class)
    constructor(file: File) : this(file.path)

    @Throws(FileNotFoundException::class, UnsupportedEncodingException::class)
    constructor(file: File, csn: String) : this(file.path, csn)

    @Throws(java.io.IOException::class)
    constructor(file: File, charset: Charset) : this(file.path, charset)
}
