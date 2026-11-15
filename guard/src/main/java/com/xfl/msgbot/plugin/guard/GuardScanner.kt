/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.guard

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.channels.ReadableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.io.InputStreamReader
import java.nio.channels.Channels
import java.nio.charset.Charset
import java.util.Locale
import java.util.Scanner
import java.util.regex.MatchResult
import java.util.regex.Pattern
import java.util.stream.Stream

/** `java.util.Scanner` is final, so scripts get this one: the same calls on a real scanner that reads through the guard's streams. */
class GuardScanner private constructor(private val scanner: Scanner) : Closeable, Iterator<String> {
    constructor(source: String) : this(Scanner(source))

    constructor(source: InputStream) : this(Scanner(source))

    constructor(source: InputStream, charset: String) : this(Scanner(source, charset))

    constructor(source: File) : this(Scanner(GuardFileInputStream(source)))

    constructor(source: File, charset: String) : this(Scanner(GuardFileInputStream(source), charset))

    constructor(source: Path) : this(Scanner(Files.newInputStream(source)))

    constructor(source: Path, charset: String) : this(Scanner(Files.newInputStream(source), charset))

    constructor(source: Readable) : this(Scanner(source))

    constructor(source: ReadableByteChannel) : this(Scanner(source))

    constructor(source: ReadableByteChannel, charset: String) : this(Scanner(source, charset))

    // Scanner's own Charset constructors are newer than the oldest Android this runs on.
    constructor(source: InputStream, charset: Charset) : this(Scanner(InputStreamReader(source, charset)))

    constructor(source: File, charset: Charset) : this(GuardFileInputStream(source), charset)

    constructor(source: Path, charset: Charset) : this(Files.newInputStream(source), charset)

    constructor(source: ReadableByteChannel, charset: Charset) : this(Channels.newInputStream(source), charset)

    override fun hasNext() = scanner.hasNext()

    fun hasNext(pattern: String) = scanner.hasNext(pattern)

    override fun next(): String = scanner.next()

    fun next(pattern: String): String = scanner.next(pattern)

    fun hasNextLine() = scanner.hasNextLine()

    fun nextLine(): String = scanner.nextLine()

    fun hasNextInt() = scanner.hasNextInt()

    fun hasNextInt(radix: Int) = scanner.hasNextInt(radix)

    fun nextInt() = scanner.nextInt()

    fun nextInt(radix: Int) = scanner.nextInt(radix)

    fun hasNextLong() = scanner.hasNextLong()

    fun nextLong() = scanner.nextLong()

    fun hasNextShort() = scanner.hasNextShort()

    fun nextShort() = scanner.nextShort()

    fun hasNextByte() = scanner.hasNextByte()

    fun nextByte() = scanner.nextByte()

    fun hasNextFloat() = scanner.hasNextFloat()

    fun nextFloat() = scanner.nextFloat()

    fun hasNextDouble() = scanner.hasNextDouble()

    fun nextDouble() = scanner.nextDouble()

    fun hasNextBoolean() = scanner.hasNextBoolean()

    fun nextBoolean() = scanner.nextBoolean()

    fun hasNextBigInteger() = scanner.hasNextBigInteger()

    fun nextBigInteger(): BigInteger = scanner.nextBigInteger()

    fun hasNextBigDecimal() = scanner.hasNextBigDecimal()

    fun nextBigDecimal(): BigDecimal = scanner.nextBigDecimal()

    fun useDelimiter(pattern: String): GuardScanner = apply { scanner.useDelimiter(pattern) }

    fun delimiter(): Pattern = scanner.delimiter()

    fun skip(pattern: String): GuardScanner = apply { scanner.skip(pattern) }

    fun findInLine(pattern: String): String? = scanner.findInLine(pattern)

    fun findWithinHorizon(
        pattern: String,
        horizon: Int,
    ): String? = scanner.findWithinHorizon(pattern, horizon)

    fun useRadix(radix: Int): GuardScanner = apply { scanner.useRadix(radix) }

    fun radix() = scanner.radix()

    fun reset(): GuardScanner = apply { scanner.reset() }

    fun tokens(): Stream<String> = scanner.tokens()

    fun ioException(): IOException? = scanner.ioException()

    fun hasNext(pattern: Pattern) = scanner.hasNext(pattern)

    fun next(pattern: Pattern): String = scanner.next(pattern)

    fun hasNextLong(radix: Int) = scanner.hasNextLong(radix)

    fun nextLong(radix: Int) = scanner.nextLong(radix)

    fun hasNextShort(radix: Int) = scanner.hasNextShort(radix)

    fun nextShort(radix: Int) = scanner.nextShort(radix)

    fun hasNextByte(radix: Int) = scanner.hasNextByte(radix)

    fun nextByte(radix: Int) = scanner.nextByte(radix)

    fun hasNextBigInteger(radix: Int) = scanner.hasNextBigInteger(radix)

    fun nextBigInteger(radix: Int): BigInteger = scanner.nextBigInteger(radix)

    fun useDelimiter(pattern: Pattern): GuardScanner = apply { scanner.useDelimiter(pattern) }

    fun skip(pattern: Pattern): GuardScanner = apply { scanner.skip(pattern) }

    fun findInLine(pattern: Pattern): String? = scanner.findInLine(pattern)

    fun findWithinHorizon(
        pattern: Pattern,
        horizon: Int,
    ): String? = scanner.findWithinHorizon(pattern, horizon)

    fun findAll(pattern: String): Stream<MatchResult> = scanner.findAll(pattern)

    fun findAll(pattern: Pattern): Stream<MatchResult> = scanner.findAll(pattern)

    fun match(): MatchResult = scanner.match()

    fun locale(): Locale = scanner.locale()

    fun useLocale(locale: Locale): GuardScanner = apply { scanner.useLocale(locale) }

    override fun close() = scanner.close()

    override fun toString(): String = scanner.toString()
}
