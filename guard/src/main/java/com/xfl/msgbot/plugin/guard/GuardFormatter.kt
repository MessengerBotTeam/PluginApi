/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.guard

import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.Flushable
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import java.io.UnsupportedEncodingException
import java.nio.charset.Charset
import java.util.Formatter
import java.util.Locale

/**
 * `java.util.Formatter`, which is final: the constructors that take a file name or a `File` write through the host,
 * and everything else is the real formatter underneath.
 */
class GuardFormatter private constructor(
    private val real: Formatter,
) : Closeable,
    Flushable {
    constructor() : this(Formatter())

    constructor(a: Appendable?) : this(Formatter(a))

    constructor(l: Locale?) : this(Formatter(l))

    constructor(a: Appendable?, l: Locale?) : this(Formatter(a, l))

    @Throws(FileNotFoundException::class)
    constructor(fileName: String) : this(toFile(fileName, Charset.defaultCharset(), formatLocale()))

    @Throws(FileNotFoundException::class, UnsupportedEncodingException::class)
    constructor(fileName: String, csn: String) : this(toFile(fileName, charsetNamed(csn), formatLocale()))

    @Throws(FileNotFoundException::class, UnsupportedEncodingException::class)
    constructor(fileName: String, csn: String, l: Locale?) : this(toFile(fileName, charsetNamed(csn), l))

    @Throws(IOException::class)
    constructor(fileName: String, charset: Charset, l: Locale?) : this(toFile(fileName, charset, l))

    @Throws(FileNotFoundException::class)
    constructor(file: File) : this(file.path)

    @Throws(FileNotFoundException::class, UnsupportedEncodingException::class)
    constructor(file: File, csn: String) : this(file.path, csn)

    @Throws(FileNotFoundException::class, UnsupportedEncodingException::class)
    constructor(file: File, csn: String, l: Locale?) : this(file.path, csn, l)

    @Throws(IOException::class)
    constructor(file: File, charset: Charset, l: Locale?) : this(file.path, charset, l)

    constructor(ps: PrintStream) : this(Formatter(ps))

    constructor(os: OutputStream) : this(Formatter(os))

    @Throws(UnsupportedEncodingException::class)
    constructor(os: OutputStream, csn: String) : this(Formatter(os, csn))

    @Throws(UnsupportedEncodingException::class)
    constructor(os: OutputStream, csn: String, l: Locale?) : this(Formatter(os, csn, l))

    constructor(os: OutputStream, charset: Charset, l: Locale?) : this(Formatter(os, charset.name(), l))

    fun locale(): Locale? = real.locale()

    fun out(): Appendable = real.out()

    override fun toString(): String = real.toString()

    override fun flush() = real.flush()

    override fun close() = real.close()

    fun ioException(): IOException? = real.ioException()

    fun format(
        format: String?,
        vararg args: Any?,
    ): GuardFormatter {
        real.format(format, *args)
        return this
    }

    fun format(
        l: Locale?,
        format: String?,
        vararg args: Any?,
    ): GuardFormatter {
        real.format(l, format, *args)
        return this
    }

    private companion object {
        fun formatLocale(): Locale = Locale.getDefault(Locale.Category.FORMAT)

        // The real class checks the charset before it opens the file.
        fun toFile(
            fileName: String,
            charset: Charset,
            l: Locale?,
        ): Formatter = Formatter(GuardFileOutputStream(fileName), charset.name(), l)
    }
}
