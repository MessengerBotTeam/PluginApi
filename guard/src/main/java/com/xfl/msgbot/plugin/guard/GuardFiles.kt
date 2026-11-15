/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.guard

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset
import java.nio.file.CopyOption
import java.nio.file.DirectoryStream
import java.nio.file.FileStore
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserPrincipal
import java.util.function.BiPredicate
import java.util.stream.Stream

/**
 * `java.nio.file.Files` for scripts. Every call is the real one, which works on a [GuardPath] through the host; only
 * the temporary files and folders made without a folder go to the host's temporary folder instead of this app's.
 * A path that is not a [GuardPath] (a real path from another API) is taken as the same path of the project.
 */
class GuardFiles private constructor() {
    @Suppress("TooManyFunctions")
    companion object {
        private fun p(path: Path): Path = path as? GuardPath ?: GuardPath(GuardContext.host(), path.toString())

        private fun tmp(): Path = GuardPath(GuardContext.host(), GuardContext.host().tmpdir())

        @JvmStatic fun newInputStream(path: Path, vararg options: OpenOption): InputStream = Files.newInputStream(p(path), *options)

        @JvmStatic fun newOutputStream(path: Path, vararg options: OpenOption): OutputStream = Files.newOutputStream(p(path), *options)

        @JvmStatic fun newByteChannel(path: Path, options: Set<OpenOption>, vararg attrs: FileAttribute<*>): SeekableByteChannel = Files.newByteChannel(p(path), options, *attrs)

        @JvmStatic fun newByteChannel(path: Path, vararg options: OpenOption): SeekableByteChannel = Files.newByteChannel(p(path), *options)

        @JvmStatic fun newDirectoryStream(dir: Path): DirectoryStream<Path> = Files.newDirectoryStream(p(dir))

        @JvmStatic fun newDirectoryStream(dir: Path, glob: String): DirectoryStream<Path> = Files.newDirectoryStream(p(dir), glob)

        @JvmStatic fun newDirectoryStream(dir: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> = Files.newDirectoryStream(p(dir), filter)

        @JvmStatic fun createFile(path: Path, vararg attrs: FileAttribute<*>): Path = Files.createFile(p(path), *attrs)

        @JvmStatic fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>): Path = Files.createDirectory(p(dir), *attrs)

        @JvmStatic fun createDirectories(dir: Path, vararg attrs: FileAttribute<*>): Path = Files.createDirectories(p(dir).toAbsolutePath(), *attrs)

        @JvmStatic fun createTempFile(dir: Path, prefix: String?, suffix: String?, vararg attrs: FileAttribute<*>): Path = Files.createTempFile(p(dir), prefix, suffix, *attrs)

        @JvmStatic fun createTempFile(prefix: String?, suffix: String?, vararg attrs: FileAttribute<*>): Path = Files.createTempFile(tmp(), prefix, suffix, *attrs)

        @JvmStatic fun createTempDirectory(dir: Path, prefix: String?, vararg attrs: FileAttribute<*>): Path = Files.createTempDirectory(p(dir), prefix, *attrs)

        @JvmStatic fun createTempDirectory(prefix: String?, vararg attrs: FileAttribute<*>): Path = Files.createTempDirectory(tmp(), prefix, *attrs)

        @JvmStatic fun createSymbolicLink(link: Path, target: Path, vararg attrs: FileAttribute<*>): Path = Files.createSymbolicLink(p(link), p(target), *attrs)

        @JvmStatic fun createLink(link: Path, existing: Path): Path = Files.createLink(p(link), p(existing))

        @JvmStatic fun delete(path: Path) = Files.delete(p(path))

        @JvmStatic fun deleteIfExists(path: Path): Boolean = Files.deleteIfExists(p(path))

        @JvmStatic fun copy(source: Path, target: Path, vararg options: CopyOption): Path = Files.copy(p(source), p(target), *options)

        @JvmStatic fun copy(input: InputStream, target: Path, vararg options: CopyOption): Long = Files.copy(input, p(target), *options)

        @JvmStatic fun copy(source: Path, out: OutputStream): Long = Files.copy(p(source), out)

        @JvmStatic fun move(source: Path, target: Path, vararg options: CopyOption): Path = Files.move(p(source), p(target), *options)

        @JvmStatic fun readSymbolicLink(link: Path): Path = Files.readSymbolicLink(p(link))

        @JvmStatic fun getFileStore(path: Path): FileStore = Files.getFileStore(p(path))

        @JvmStatic fun isSameFile(path: Path, path2: Path): Boolean = Files.isSameFile(p(path), p(path2))

        @JvmStatic fun isHidden(path: Path): Boolean = Files.isHidden(p(path))

        @JvmStatic fun probeContentType(path: Path): String? = Files.probeContentType(p(path))

        @JvmStatic fun <V : FileAttributeView> getFileAttributeView(path: Path, type: Class<V>, vararg options: LinkOption): V? = Files.getFileAttributeView(p(path), type, *options)

        @JvmStatic fun <A : BasicFileAttributes> readAttributes(path: Path, type: Class<A>, vararg options: LinkOption): A = Files.readAttributes(p(path), type, *options)

        @JvmStatic fun setAttribute(path: Path, attribute: String, value: Any?, vararg options: LinkOption): Path = Files.setAttribute(p(path), attribute, value, *options)

        @JvmStatic fun getAttribute(path: Path, attribute: String, vararg options: LinkOption): Any? = Files.getAttribute(p(path), attribute, *options)

        @JvmStatic fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): Map<String, Any?> = Files.readAttributes(p(path), attributes, *options)

        @JvmStatic fun getPosixFilePermissions(path: Path, vararg options: LinkOption): Set<PosixFilePermission> = Files.getPosixFilePermissions(p(path), *options)

        @JvmStatic fun setPosixFilePermissions(path: Path, perms: Set<PosixFilePermission>): Path = Files.setPosixFilePermissions(p(path), perms)

        @JvmStatic fun getOwner(path: Path, vararg options: LinkOption): UserPrincipal = Files.getOwner(p(path), *options)

        @JvmStatic fun setOwner(path: Path, owner: UserPrincipal): Path = Files.setOwner(p(path), owner)

        @JvmStatic fun isSymbolicLink(path: Path): Boolean = Files.isSymbolicLink(p(path))

        @JvmStatic fun isDirectory(path: Path, vararg options: LinkOption): Boolean = Files.isDirectory(p(path), *options)

        @JvmStatic fun isRegularFile(path: Path, vararg options: LinkOption): Boolean = Files.isRegularFile(p(path), *options)

        @JvmStatic fun getLastModifiedTime(path: Path, vararg options: LinkOption): FileTime = Files.getLastModifiedTime(p(path), *options)

        @JvmStatic fun setLastModifiedTime(path: Path, time: FileTime): Path = Files.setLastModifiedTime(p(path), time)

        @JvmStatic fun size(path: Path): Long = Files.size(p(path))

        @JvmStatic fun exists(path: Path, vararg options: LinkOption): Boolean = Files.exists(p(path), *options)

        @JvmStatic fun notExists(path: Path, vararg options: LinkOption): Boolean = Files.notExists(p(path), *options)

        @JvmStatic fun isReadable(path: Path): Boolean = Files.isReadable(p(path))

        @JvmStatic fun isWritable(path: Path): Boolean = Files.isWritable(p(path))

        @JvmStatic fun isExecutable(path: Path): Boolean = Files.isExecutable(p(path))

        @JvmStatic fun walkFileTree(start: Path, options: Set<FileVisitOption>, maxDepth: Int, visitor: FileVisitor<in Path>): Path = Files.walkFileTree(p(start), options, maxDepth, visitor)

        @JvmStatic fun walkFileTree(start: Path, visitor: FileVisitor<in Path>): Path = Files.walkFileTree(p(start), visitor)

        @JvmStatic fun newBufferedReader(path: Path, cs: Charset): BufferedReader = Files.newBufferedReader(p(path), cs)

        @JvmStatic fun newBufferedReader(path: Path): BufferedReader = Files.newBufferedReader(p(path))

        @JvmStatic fun newBufferedWriter(path: Path, cs: Charset, vararg options: OpenOption): BufferedWriter = Files.newBufferedWriter(p(path), cs, *options)

        @JvmStatic fun newBufferedWriter(path: Path, vararg options: OpenOption): BufferedWriter = Files.newBufferedWriter(p(path), *options)

        @JvmStatic fun readAllBytes(path: Path): ByteArray = Files.readAllBytes(p(path))

        // Present at run time but missing from the SDK stubs, so written out instead of delegated.
        @JvmStatic fun readString(path: Path): String = readString(path, Charsets.UTF_8)

        @JvmStatic fun readString(path: Path, cs: Charset): String = cs.newDecoder().decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(p(path)))).toString()

        @JvmStatic fun writeString(path: Path, csq: CharSequence, vararg options: OpenOption): Path = writeString(path, csq, Charsets.UTF_8, *options)

        @JvmStatic fun writeString(path: Path, csq: CharSequence, cs: Charset, vararg options: OpenOption): Path {
            val bytes = cs.newEncoder().encode(java.nio.CharBuffer.wrap(csq))
            return Files.write(p(path), ByteArray(bytes.remaining()).also { bytes.get(it) }, *options)
        }

        @JvmStatic fun readAllLines(path: Path, cs: Charset): List<String> = Files.readAllLines(p(path), cs)

        @JvmStatic fun readAllLines(path: Path): List<String> = Files.readAllLines(p(path))

        @JvmStatic fun write(path: Path, bytes: ByteArray, vararg options: OpenOption): Path = Files.write(p(path), bytes, *options)

        @JvmStatic fun write(path: Path, lines: Iterable<CharSequence>, cs: Charset, vararg options: OpenOption): Path = Files.write(p(path), lines, cs, *options)

        @JvmStatic fun write(path: Path, lines: Iterable<CharSequence>, vararg options: OpenOption): Path = Files.write(p(path), lines, *options)

        @JvmStatic fun list(dir: Path): Stream<Path> = Files.list(p(dir))

        @JvmStatic fun walk(start: Path, maxDepth: Int, vararg options: FileVisitOption): Stream<Path> = Files.walk(p(start), maxDepth, *options)

        @JvmStatic fun walk(start: Path, vararg options: FileVisitOption): Stream<Path> = Files.walk(p(start), *options)

        @JvmStatic fun find(start: Path, maxDepth: Int, matcher: BiPredicate<Path, BasicFileAttributes>, vararg options: FileVisitOption): Stream<Path> = Files.find(p(start), maxDepth, matcher, *options)

        @JvmStatic fun lines(path: Path, cs: Charset): Stream<String> = Files.lines(p(path), cs)

        @JvmStatic fun lines(path: Path): Stream<String> = Files.lines(p(path))
    }
}
