/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.guard

import com.xfl.msgbot.plugin.api.engine.HostFileMode
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessDeniedException
import java.nio.file.AccessMode
import java.nio.file.CopyOption
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.DirectoryStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileStore
import java.nio.file.FileSystem
import java.nio.file.FileSystemNotFoundException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.ProviderMismatchException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import java.nio.file.attribute.FileOwnerAttributeView
import java.nio.file.attribute.FileStoreAttributeView
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.GroupPrincipal
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserPrincipal
import java.nio.file.attribute.UserPrincipalLookupService
import java.nio.file.spi.FileSystemProvider
import java.util.regex.Pattern

/**
 * `java.nio.file.Path` for scripts. The real `java.nio.file.Files` works with it unchanged: it asks the path's
 * provider, which is [GuardProvider], so every operation goes to the host under the project's file rules.
 * Relative paths are relative to the project folder.
 */
class GuardPath internal constructor(
    internal val host: GuardHost,
    text: String,
) : Path {
    private val text: String = clean(text)
    private val names: List<String> = if (this.text.isEmpty()) listOf("") else this.text.split('/').filter { it.isNotEmpty() }

    override fun getFileSystem(): FileSystem = GuardFileSystem

    override fun isAbsolute() = text.startsWith("/")

    override fun getRoot(): Path? = if (isAbsolute) GuardPath(host, "/") else null

    override fun getFileName(): Path? = if (text == "/") null else GuardPath(host, names.last())

    override fun getParent(): Path? {
        val cut = text.lastIndexOf('/')
        return when {
            text == "/" || cut < 0 -> null
            cut == 0 -> GuardPath(host, "/")
            else -> GuardPath(host, text.substring(0, cut))
        }
    }

    override fun getNameCount() = if (text == "/") 0 else names.size

    override fun getName(index: Int): Path = GuardPath(host, names.getOrNull(index) ?: throw IllegalArgumentException())

    override fun subpath(
        beginIndex: Int,
        endIndex: Int,
    ): Path {
        if (beginIndex < 0 || beginIndex >= nameCount || endIndex > nameCount || beginIndex >= endIndex) throw IllegalArgumentException()
        return GuardPath(host, names.subList(beginIndex, endIndex).joinToString("/"))
    }

    private fun other(path: Path): GuardPath = path as? GuardPath ?: throw ProviderMismatchException()

    override fun startsWith(other: Path): Boolean {
        val o = other as? GuardPath ?: return false
        if (o.isAbsolute != isAbsolute) return false
        if (o.text == "/") return isAbsolute
        if (o.text.isEmpty()) return text.isEmpty()
        return o.names.size <= names.size && names.subList(0, o.names.size) == o.names
    }

    override fun endsWith(other: Path): Boolean {
        val o = other as? GuardPath ?: return false
        if (o.isAbsolute) return o.text == text
        if (o.text.isEmpty()) return text.isEmpty()
        return o.names.size <= names.size && names.subList(names.size - o.names.size, names.size) == o.names
    }

    override fun normalize(): Path {
        val kept = ArrayList<String>()
        for (name in names) {
            when {
                name == "." || name.isEmpty() -> Unit
                name == ".." && kept.isNotEmpty() && kept.last() != ".." -> kept.removeAt(kept.size - 1)
                name == ".." && isAbsolute -> Unit
                else -> kept.add(name)
            }
        }
        return GuardPath(host, (if (isAbsolute) "/" else "") + kept.joinToString("/"))
    }

    override fun resolve(other: Path): Path {
        val o = other(other)
        return when {
            o.isAbsolute -> o
            o.text.isEmpty() -> this
            text.isEmpty() -> o
            else -> GuardPath(host, "$text/${o.text}")
        }
    }

    override fun relativize(other: Path): Path {
        val o = other(other)
        if (o.isAbsolute != isAbsolute) throw IllegalArgumentException("'other' is different type of Path")
        if (o.text == text) return GuardPath(host, "")
        var common = 0
        val mine = if (text.isEmpty()) emptyList() else names
        val theirs = if (o.text.isEmpty()) emptyList() else o.names
        while (common < mine.size && common < theirs.size && mine[common] == theirs[common]) common++
        val up = List(mine.size - common) { ".." }
        return GuardPath(host, (up + theirs.drop(common)).joinToString("/"))
    }

    override fun toUri(): URI {
        val absolute = toAbsolutePath().toString()
        val directory = !absolute.endsWith("/") && runCatching { host.stat(absolute)?.isDirectory == true }.getOrDefault(false)
        return URI("file", "", if (directory) "$absolute/" else absolute, null)
    }

    override fun toAbsolutePath(): Path = if (isAbsolute) this else GuardPath(host, host.root.trimEnd('/') + "/" + text)

    override fun toRealPath(vararg options: LinkOption): Path {
        val absolute = toAbsolutePath().toString()
        try {
            if (LinkOption.NOFOLLOW_LINKS in options) {
                host.stat(absolute, false) ?: throw NoSuchFileException(text)
                return toAbsolutePath().normalize()
            }
            return GuardPath(host, host.realpath(absolute))
        } catch (e: HostIOException) {
            throw GuardErrors.nio(HostIOException(e.errno, text, e.detail))
        }
    }

    override fun toFile(): File = GuardFile(text)

    override fun register(
        watcher: WatchService,
        events: Array<out WatchEvent.Kind<*>>,
        vararg modifiers: WatchEvent.Modifier,
    ): WatchKey = throw UnsupportedOperationException("Watching folders is not available to scripts")

    override fun register(
        watcher: WatchService,
        vararg events: WatchEvent.Kind<*>,
    ): WatchKey = throw UnsupportedOperationException("Watching folders is not available to scripts")

    override fun startsWith(other: String) = startsWith(GuardPath(host, other))

    override fun endsWith(other: String) = endsWith(GuardPath(host, other))

    override fun resolve(other: String): Path = resolve(GuardPath(host, other))

    override fun resolveSibling(other: Path): Path = parent?.resolve(other) ?: other

    override fun resolveSibling(other: String): Path = resolveSibling(GuardPath(host, other))

    override fun iterator(): MutableIterator<Path> = List(nameCount) { getName(it) }.toMutableList().iterator()

    override fun compareTo(other: Path): Int = text.compareTo((other as GuardPath).text)

    override fun equals(other: Any?) = other is GuardPath && other.text == text

    override fun hashCode() = text.hashCode()

    override fun toString() = text

    companion object {
        private fun clean(raw: String): String {
            if (raw.indexOf('\u0000') >= 0) throw java.nio.file.InvalidPathException(raw, "Nul character not allowed")
            val joined = raw.replace(Regex("/+"), "/")
            return if (joined.length > 1) joined.trimEnd('/') else joined
        }

        /** `Path.of(first, more...)`. */
        @JvmStatic
        fun of(
            first: String,
            vararg more: String,
        ): Path = GuardPath(GuardContext.host(), (listOf(first) + more).filter { it.isNotEmpty() }.joinToString("/"))

        /** `Path.of(uri)`; only `file:` URIs exist here. */
        @JvmStatic
        fun of(uri: URI): Path {
            if (!uri.scheme.equals("file", ignoreCase = true)) throw FileSystemNotFoundException("Provider \"${uri.scheme}\" not installed")
            return GuardPath(GuardContext.host(), uri.path)
        }
    }
}

/** `java.nio.file.Paths`, which is final. */
class GuardPaths private constructor() {
    companion object {
        @JvmStatic
        fun get(
            first: String,
            vararg more: String,
        ): Path = GuardPath.of(first, *more)

        @JvmStatic
        fun get(uri: URI): Path = GuardPath.of(uri)
    }
}

/** `java.nio.file.FileSystems`, which is final. Only the default file system exists. */
class GuardFileSystems private constructor() {
    companion object {
        @JvmStatic
        fun getDefault(): FileSystem = GuardFileSystem

        @JvmStatic
        fun getFileSystem(uri: URI): FileSystem = GuardProvider.getFileSystem(uri)

        // Android ships no other file system provider (no zipfs), so these fail as the real ones do.
        private fun none(): Nothing = throw java.nio.file.ProviderNotFoundException("Provider not found")

        @JvmStatic
        fun newFileSystem(
            uri: URI,
            env: Map<String, *>,
        ): FileSystem = none()

        @JvmStatic
        fun newFileSystem(
            uri: URI,
            env: Map<String, *>,
            loader: ClassLoader?,
        ): FileSystem = none()

        @JvmStatic
        fun newFileSystem(
            path: Path,
        ): FileSystem = none()

        @JvmStatic
        fun newFileSystem(
            path: Path,
            loader: ClassLoader?,
        ): FileSystem = none()

        @JvmStatic
        fun newFileSystem(
            path: Path,
            env: Map<String, *>,
        ): FileSystem = none()

        @JvmStatic
        fun newFileSystem(
            path: Path,
            env: Map<String, *>,
            loader: ClassLoader?,
        ): FileSystem = none()
    }
}

internal object GuardFileSystem : FileSystem() {
    override fun provider(): FileSystemProvider = GuardProvider

    override fun close() = throw UnsupportedOperationException()

    override fun isOpen() = true

    override fun isReadOnly() = false

    override fun getSeparator() = "/"

    override fun getRootDirectories(): Iterable<Path> = listOf(GuardPath(GuardContext.host(), "/"))

    override fun getFileStores(): Iterable<FileStore> = listOf(GuardFileStore(GuardPath(GuardContext.host(), GuardContext.host().root)))

    override fun supportedFileAttributeViews(): Set<String> = setOf("basic", "posix", "unix", "owner")

    override fun getPath(
        first: String,
        vararg more: String,
    ): Path = GuardPath.of(first, *more)

    override fun getPathMatcher(syntaxAndPattern: String): PathMatcher {
        val separator = syntaxAndPattern.indexOf(':')
        require(separator > 0) { "Syntax and pattern are required" }
        val pattern = syntaxAndPattern.substring(separator + 1)
        val regex =
            when (syntaxAndPattern.substring(0, separator).lowercase()) {
                "regex" -> pattern
                "glob" -> Globs.toRegex(pattern)
                else -> throw UnsupportedOperationException("Syntax '${syntaxAndPattern.substring(0, separator)}' not recognized")
            }
        val compiled = Pattern.compile(regex)
        return PathMatcher { compiled.matcher(it.toString()).matches() }
    }

    override fun getUserPrincipalLookupService(): UserPrincipalLookupService =
        object : UserPrincipalLookupService() {
            override fun lookupPrincipalByName(name: String): UserPrincipal = Principal(name)

            override fun lookupPrincipalByGroupName(group: String): GroupPrincipal = Principal(group)
        }

    override fun newWatchService(): WatchService = throw UnsupportedOperationException("Watching folders is not available to scripts")
}

/** Glob syntax of `FileSystem.getPathMatcher`: `*`, `**`, `?`, `[...]`, `{a,b}`. */
internal object Globs {
    fun toRegex(glob: String): String {
        val out = StringBuilder("^")
        var i = 0
        var inGroup = false
        while (i < glob.length) {
            when (val c = glob[i]) {
                '*' ->
                    if (i + 1 < glob.length && glob[i + 1] == '*') {
                        out.append(".*")
                        i++
                    } else {
                        out.append("[^/]*")
                    }
                '?' -> out.append("[^/]")
                '[' -> {
                    val end = glob.indexOf(']', i + 1)
                    if (end < 0) throw java.util.regex.PatternSyntaxException("Missing ']'", glob, i)
                    var body = glob.substring(i + 1, end)
                    if (body.startsWith("!")) body = "^" + body.substring(1)
                    out.append('[').append(body.replace("\\", "\\\\")).append(']')
                    i = end
                }
                '{' -> {
                    inGroup = true
                    out.append("(?:")
                }
                '}' -> {
                    inGroup = false
                    out.append(')')
                }
                ',' -> out.append(if (inGroup) "|" else ",")
                '\\' -> {
                    if (i + 1 < glob.length) out.append(Pattern.quote(glob[++i].toString()))
                }
                else -> out.append(if (c.isLetterOrDigit() || c == '/') c.toString() else Pattern.quote(c.toString()))
            }
            i++
        }
        return out.append('$').toString()
    }
}

private class Principal(private val name: String) : UserPrincipal, GroupPrincipal {
    override fun getName() = name

    override fun equals(other: Any?) = other is Principal && other.name == name

    override fun hashCode() = name.hashCode()

    override fun toString() = name
}

private class GuardAttributes(private val entry: GuardHost.Entry) : PosixFileAttributes {
    override fun lastModifiedTime(): FileTime = FileTime.fromMillis(entry.modifiedMs)

    override fun lastAccessTime(): FileTime = FileTime.fromMillis(entry.accessedMs)

    override fun creationTime(): FileTime = FileTime.fromMillis(entry.createdMs.takeIf { it > 0 } ?: entry.modifiedMs)

    override fun isRegularFile() = entry.isRegularFile

    override fun isDirectory() = entry.isDirectory

    override fun isSymbolicLink() = entry.isSymbolicLink

    override fun isOther() = false

    override fun size() = entry.size

    override fun fileKey(): Any? = null

    override fun owner(): UserPrincipal = Principal(entry.uid.toString())

    override fun group(): GroupPrincipal = Principal(entry.gid.toString())

    override fun permissions(): MutableSet<PosixFilePermission> = Permissions.of(entry.mode)
}

private object Permissions {
    private val order =
        listOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE,
        )

    fun of(mode: Int): MutableSet<PosixFilePermission> = order.filterIndexed { index, _ -> (mode and (0x100 shr index)) != 0 }.toMutableSet()

    fun mode(set: Set<PosixFilePermission>): Int = order.foldIndexed(0) { index, mode, permission -> if (permission in set) mode or (0x100 shr index) else mode }
}

internal class GuardFileStore(private val path: GuardPath) : FileStore() {
    private fun space(): GuardHost.Space =
        try {
            path.host.statfs(path.toString())
        } catch (e: HostIOException) {
            throw GuardErrors.nio(e)
        }

    override fun name() = path.host.root

    override fun type() = "host"

    override fun isReadOnly() = false

    override fun getTotalSpace() = space().let { it.blocks * it.blockSize }

    override fun getUsableSpace() = space().let { it.available * it.blockSize }

    override fun getUnallocatedSpace() = space().let { it.free * it.blockSize }

    override fun supportsFileAttributeView(type: Class<out FileAttributeView>) =
        type == BasicFileAttributeView::class.java || type == PosixFileAttributeView::class.java || type == FileOwnerAttributeView::class.java

    override fun supportsFileAttributeView(name: String) = name in GuardFileSystem.supportedFileAttributeViews()

    override fun <V : FileStoreAttributeView?> getFileStoreAttributeView(type: Class<V>): V? = null

    override fun getAttribute(attribute: String): Any =
        when (attribute) {
            "totalSpace" -> totalSpace
            "usableSpace" -> usableSpace
            "unallocatedSpace" -> unallocatedSpace
            else -> throw UnsupportedOperationException("'$attribute' not recognized")
        }
}

private fun guard(path: Path): GuardPath = path as? GuardPath ?: throw ProviderMismatchException()

private inline fun <T> nio(
    path: GuardPath,
    other: Path? = null,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: HostIOException) {
        throw GuardErrors.nio(HostIOException(e.errno, path.toString(), e.detail), other?.toString())
    }

private fun entry(
    path: GuardPath,
    options: Array<out LinkOption>,
): GuardHost.Entry = nio(path) { path.host.stat(path.toString(), LinkOption.NOFOLLOW_LINKS !in options) } ?: throw NoSuchFileException(path.toString())

internal object GuardProvider : FileSystemProvider() {
    override fun getScheme() = "file"

    override fun newFileSystem(
        uri: URI,
        env: MutableMap<String, *>,
    ): FileSystem = throw UnsupportedOperationException("Other file systems are not available to scripts")

    override fun getFileSystem(uri: URI): FileSystem {
        if (!uri.scheme.equals("file", ignoreCase = true)) throw FileSystemNotFoundException("Provider \"${uri.scheme}\" not installed")
        return GuardFileSystem
    }

    override fun getPath(uri: URI): Path = GuardPath.of(uri)

    override fun newByteChannel(
        path: Path,
        options: MutableSet<out OpenOption>,
        vararg attrs: FileAttribute<*>,
    ): SeekableByteChannel = newFileChannel(path, options, *attrs)

    override fun newFileChannel(
        path: Path,
        options: MutableSet<out OpenOption>,
        vararg attrs: FileAttribute<*>,
    ): FileChannel {
        val target = guard(path)
        val name = target.toString()
        val append = StandardOpenOption.APPEND in options
        val write = StandardOpenOption.WRITE in options || append
        val read = StandardOpenOption.READ in options || !write
        if (append && (read && StandardOpenOption.READ in options || StandardOpenOption.TRUNCATE_EXISTING in options)) {
            throw IllegalArgumentException("READ + APPEND or APPEND + TRUNCATE_EXISTING not allowed")
        }
        val host = target.host
        if (!write) return GuardRandomAccessFile(nio(target) { host.open(name, HostFileMode.READ) }).channel
        val createNew = StandardOpenOption.CREATE_NEW in options
        val create = createNew || StandardOpenOption.CREATE in options
        val exists = nio(target) { host.stat(name) }
        if (exists?.isDirectory == true) throw java.nio.file.FileSystemException(name, null, "Is a directory")
        if (createNew && exists != null) throw FileAlreadyExistsException(name)
        if (exists == null && !create) throw NoSuchFileException(name)
        val truncate = StandardOpenOption.TRUNCATE_EXISTING in options
        val mode =
            when {
                append -> HostFileMode.APPEND or HostFileMode.READ_WRITE
                exists == null || truncate -> HostFileMode.WRITE or HostFileMode.READ_WRITE
                else -> HostFileMode.READ_WRITE
            } or (if (createNew) HostFileMode.EXCLUSIVE else 0)
        val descriptor = nio(target) { host.open(name, mode) }
        val channel = GuardRandomAccessFile(descriptor).channel
        if (append) channel.position(channel.size())
        if (StandardOpenOption.DELETE_ON_CLOSE in options) GuardContext.current().deleteOnExit(target.toAbsolutePath().toString())
        return channel
    }

    override fun newDirectoryStream(
        dir: Path,
        filter: DirectoryStream.Filter<in Path>?,
    ): DirectoryStream<Path> {
        val target = guard(dir)
        val entry = entry(target, emptyArray())
        if (!entry.isDirectory) throw java.nio.file.NotDirectoryException(target.toString())
        val names = nio(target) { target.host.list(target.toString()) }
        val entries = names.map { target.resolve(it) }.filter { filter == null || filter.accept(it) }
        return object : DirectoryStream<Path> {
            private var used = false

            override fun iterator(): MutableIterator<Path> {
                check(!used) { "Iterator already obtained" }
                used = true
                return entries.toMutableList().iterator()
            }

            override fun close() = Unit
        }
    }

    override fun createDirectory(
        dir: Path,
        vararg attrs: FileAttribute<*>,
    ) {
        val target = guard(dir)
        if (nio(target) { target.host.stat(target.toString(), false) } != null) throw FileAlreadyExistsException(target.toString())
        nio(target) { target.host.mkdir(target.toString(), false) }
        applyAttributes(target, attrs)
    }

    private fun applyAttributes(
        target: GuardPath,
        attrs: Array<out FileAttribute<*>>,
    ) {
        for (attr in attrs) {
            if (attr.name() == "posix:permissions") {
                @Suppress("UNCHECKED_CAST")
                nio(target) { target.host.chmod(target.toString(), Permissions.mode(attr.value() as Set<PosixFilePermission>)) }
            } else {
                throw UnsupportedOperationException("'${attr.name()}' not supported as initial attribute")
            }
        }
    }

    override fun createSymbolicLink(
        link: Path,
        target: Path,
        vararg attrs: FileAttribute<*>,
    ) {
        val path = guard(link)
        nio(path) { path.host.symlink(target.toString(), path.toString()) }
    }

    override fun createLink(
        link: Path,
        existing: Path,
    ) {
        val path = guard(link)
        nio(path, existing) { path.host.link(guard(existing).toString(), path.toString()) }
    }

    override fun readSymbolicLink(link: Path): Path {
        val path = guard(link)
        return GuardPath(path.host, nio(path) { path.host.readlink(path.toString()) })
    }

    override fun delete(path: Path) {
        val target = guard(path)
        val entry = entry(target, arrayOf(LinkOption.NOFOLLOW_LINKS))
        if (entry.isDirectory && nio(target) { target.host.list(target.toString()) }.isNotEmpty()) throw DirectoryNotEmptyException(target.toString())
        nio(target) { target.host.remove(target.toString(), false) }
    }

    override fun copy(
        source: Path,
        target: Path,
        vararg options: CopyOption,
    ) {
        val from = guard(source)
        val to = guard(target)
        val follow = LinkOption.NOFOLLOW_LINKS !in options
        val entry = entry(from, if (follow) emptyArray() else arrayOf(LinkOption.NOFOLLOW_LINKS))
        if (nio(to) { to.host.stat(to.toString(), false) } != null) {
            if (StandardCopyOption.REPLACE_EXISTING !in options) throw FileAlreadyExistsException(to.toString())
            delete(to)
        }
        when {
            entry.isSymbolicLink -> nio(to) { to.host.symlink(from.host.readlink(from.toString()), to.toString()) }
            entry.isDirectory -> createDirectory(to)
            else ->
                newFileChannel(from, mutableSetOf(StandardOpenOption.READ)).use { input ->
                    newFileChannel(to, mutableSetOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)).use { output ->
                        var at = 0L
                        val size = input.size()
                        while (at < size) at += input.transferTo(at, size - at, output)
                    }
                }
        }
        if (StandardCopyOption.COPY_ATTRIBUTES in options) {
            nio(to) { to.host.utimes(to.toString(), entry.accessedMs, entry.modifiedMs) }
            if (!entry.isSymbolicLink) nio(to) { to.host.chmod(to.toString(), entry.mode and 0xfff) }
        }
    }

    override fun move(
        source: Path,
        target: Path,
        vararg options: CopyOption,
    ) {
        val from = guard(source)
        val to = guard(target)
        entry(from, arrayOf(LinkOption.NOFOLLOW_LINKS))
        val existing = nio(to) { to.host.stat(to.toString(), false) }
        if (existing != null) {
            if (StandardCopyOption.REPLACE_EXISTING !in options) throw FileAlreadyExistsException(to.toString())
            if (from == to) return
            delete(to)
        }
        nio(from, to) { from.host.rename(from.toString(), to.toString()) }
    }

    override fun isSameFile(
        path: Path,
        path2: Path,
    ): Boolean {
        val a = guard(path)
        val b = path2 as? GuardPath ?: return false
        if (a == b) return true
        return nio(a) { a.host.realpath(a.toAbsolutePath().toString()) } == nio(b) { b.host.realpath(b.toAbsolutePath().toString()) }
    }

    override fun isHidden(path: Path) = path.fileName?.toString()?.startsWith(".") == true

    override fun getFileStore(path: Path): FileStore {
        val target = guard(path)
        entry(target, emptyArray())
        return GuardFileStore(target)
    }

    override fun checkAccess(
        path: Path,
        vararg modes: AccessMode,
    ) {
        val target = guard(path)
        entry(target, emptyArray())
        val mask = modes.fold(0) { mask, mode -> mask or when (mode) { AccessMode.READ -> 4; AccessMode.WRITE -> 2; AccessMode.EXECUTE -> 1 } }
        if (mask != 0 && !target.host.access(target.toString(), mask)) throw AccessDeniedException(target.toString())
    }

    private open class Basic(
        protected val path: GuardPath,
        protected val options: Array<out LinkOption>,
    ) : BasicFileAttributeView {
        override fun name() = "basic"

        override fun readAttributes(): BasicFileAttributes = GuardAttributes(entry(path, options))

        override fun setTimes(
            lastModifiedTime: FileTime?,
            lastAccessTime: FileTime?,
            createTime: FileTime?,
        ) {
            if (lastModifiedTime == null && lastAccessTime == null) return
            val entry = entry(path, options)
            nio(path) { path.host.utimes(path.toString(), lastAccessTime?.toMillis() ?: entry.accessedMs, lastModifiedTime?.toMillis() ?: entry.modifiedMs) }
        }
    }

    private class Posix(
        path: GuardPath,
        options: Array<out LinkOption>,
    ) : Basic(path, options), PosixFileAttributeView {
        override fun name() = "posix"

        override fun readAttributes(): PosixFileAttributes = GuardAttributes(entry(path, options))

        override fun setPermissions(perms: MutableSet<PosixFilePermission>) {
            nio(path) { path.host.chmod(path.toString(), Permissions.mode(perms)) }
        }

        override fun getOwner(): UserPrincipal = readAttributes().owner()

        override fun setOwner(owner: UserPrincipal) = throw java.nio.file.FileSystemException(path.toString(), null, "Operation not permitted")

        override fun setGroup(group: GroupPrincipal) = throw java.nio.file.FileSystemException(path.toString(), null, "Operation not permitted")
    }

    @Suppress("UNCHECKED_CAST")
    override fun <V : FileAttributeView?> getFileAttributeView(
        path: Path,
        type: Class<V>,
        vararg options: LinkOption,
    ): V? {
        val target = guard(path)
        return when (type) {
            BasicFileAttributeView::class.java -> Basic(target, options) as V
            PosixFileAttributeView::class.java, FileOwnerAttributeView::class.java -> Posix(target, options) as V
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <A : BasicFileAttributes> readAttributes(
        path: Path,
        type: Class<A>,
        vararg options: LinkOption,
    ): A {
        if (type != BasicFileAttributes::class.java && type != PosixFileAttributes::class.java) throw UnsupportedOperationException()
        return GuardAttributes(entry(guard(path), options)) as A
    }

    override fun readAttributes(
        path: Path,
        attributes: String,
        vararg options: LinkOption,
    ): MutableMap<String, Any?> {
        val view = if (':' in attributes) attributes.substringBefore(':') else "basic"
        if (view !in GuardFileSystem.supportedFileAttributeViews()) throw UnsupportedOperationException("View '$view' not available")
        val a = GuardAttributes(entry(guard(path), options))
        val all =
            linkedMapOf<String, Any?>(
                "lastModifiedTime" to a.lastModifiedTime(),
                "lastAccessTime" to a.lastAccessTime(),
                "creationTime" to a.creationTime(),
                "size" to a.size(),
                "isRegularFile" to a.isRegularFile,
                "isDirectory" to a.isDirectory,
                "isSymbolicLink" to a.isSymbolicLink,
                "isOther" to a.isOther,
                "fileKey" to a.fileKey(),
            )
        if (view != "basic") {
            all["permissions"] = a.permissions()
            all["owner"] = a.owner()
            all["group"] = a.group()
        }
        val wanted = attributes.substringAfter(':').split(',')
        if ("*" in wanted) return all
        return wanted.associateWith { name -> if (name in all) all[name] else throw IllegalArgumentException("'$name' not recognized") }.toMutableMap()
    }

    override fun setAttribute(
        path: Path,
        attribute: String,
        value: Any?,
        vararg options: LinkOption,
    ) {
        val target = guard(path)
        val name = attribute.substringAfter(':')
        val posix = Posix(target, options)
        when (name) {
            "lastModifiedTime" -> posix.setTimes(value as FileTime, null, null)
            "lastAccessTime" -> posix.setTimes(null, value as FileTime, null)
            "creationTime" -> Unit
            "permissions" -> @Suppress("UNCHECKED_CAST") posix.setPermissions(value as MutableSet<PosixFilePermission>)
            else -> throw IllegalArgumentException("'$attribute' not recognized")
        }
    }
}
