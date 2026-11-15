/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.guard

import com.xfl.msgbot.plugin.api.engine.HostFileMode
import java.io.File
import java.io.FileFilter
import java.io.FilenameFilter
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import java.security.SecureRandom

/**
 * `java.io.File` for scripts. It is a real `File`, so it can be passed wherever a `File` is taken and compares equal to
 * one with the same path, but every question about the disk goes to the host and is answered under the project's file
 * rules. Relative paths are relative to the project folder.
 *
 * A path the rules forbid makes the methods that answer yes or no throw [SecurityException], as under a Java security
 * manager; `canRead`, `canWrite` and `canExecute` answer false instead.
 */
class GuardFile : GuardFileBase {
    @Transient private val session: GuardSession = GuardContext.current()

    private val host: GuardHost get() = session.host

    constructor(path: String) : super(path)

    constructor(parent: String?, child: String) : super(parent, child)

    constructor(parent: File?, child: String) : super(parent, child)

    constructor(uri: URI) : super(uri)

    private inline fun <T> ask(
        orElse: T,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: HostIOException) {
            if (e.isDenied) throw GuardErrors.denied(e)
            orElse
        }

    private fun stat(): GuardHost.Entry? = ask(null) { host.stat(path) }

    private fun permissions(): Int = stat()?.mode?.and(0x1ff) ?: throw GuardErrors.denied(HostIOException("ENOENT", path, path))

    private fun chmod(bits: Int): Boolean = ask(false) {
        host.chmod(path, bits)
        true
    }

    override fun exists() = stat() != null

    override fun isFile() = stat()?.isRegularFile == true

    override fun isDirectory() = stat()?.isDirectory == true

    override fun isHidden() = name.startsWith(".")

    override fun canRead() = host.access(path, 4)

    override fun canWrite() = host.access(path, 2)

    override fun canExecute() = host.access(path, 1)

    override fun length() = stat()?.size ?: 0L

    override fun lastModified() = stat()?.modifiedMs ?: 0L

    override fun list(): Array<String>? = ask(null) { host.list(path).toTypedArray() }

    override fun list(filter: FilenameFilter?): Array<String>? = list()?.filter { filter == null || filter.accept(this, it) }?.toTypedArray()

    override fun listFiles(): Array<File>? = list()?.map<String, File> { GuardFile(this, it) }?.toTypedArray()

    override fun listFiles(filter: FilenameFilter?): Array<File>? = list(filter)?.map<String, File> { GuardFile(this, it) }?.toTypedArray()

    override fun listFiles(filter: FileFilter?): Array<File>? = listFiles()?.filter { filter == null || filter.accept(it) }?.toTypedArray()

    override fun mkdir() = ask(false) { host.mkdir(path, false) }

    override fun mkdirs() = ask(false) { host.mkdir(path, true) }

    override fun createNewFile(): Boolean {
        try {
            host.open(path, HostFileMode.WRITE or HostFileMode.EXCLUSIVE).close()
            return true
        } catch (e: HostIOException) {
            if (e.errno == "EEXIST") return false
            throw GuardErrors.io(e)
        }
    }

    override fun delete() = ask(false) { host.remove(path, false) }

    override fun deleteOnExit() = session.deleteOnExit(absolutePath)

    override fun renameTo(dest: File) = ask(false) {
        host.rename(path, dest.path)
        true
    }

    override fun getParentFile(): File? = parent?.let { GuardFile(it) }

    override fun getAbsolutePath(): String = if (isAbsolute) path else host.root.trimEnd('/') + "/" + path

    override fun getAbsoluteFile(): File = GuardFile(absolutePath)

    override fun getCanonicalPath(): String = canonical(absolutePath)

    override fun getCanonicalFile(): File = GuardFile(canonicalPath)

    // Like java.io: links are followed as far as something exists, the rest of the path is only cleaned up.
    private fun canonical(absolute: String): String {
        val clean = normalize(absolute)
        if (clean == "/") return clean
        return try {
            host.realpath(clean)
        } catch (e: HostIOException) {
            if (e.isDenied) throw GuardErrors.denied(e)
            val cut = clean.lastIndexOf('/')
            canonical(clean.substring(0, cut).ifEmpty { "/" }).trimEnd('/') + clean.substring(cut)
        }
    }

    override fun toPath(): Path = GuardPath(host, path)

    override fun setLastModified(time: Long): Boolean {
        require(time >= 0) { "Negative time" }
        val entry = stat() ?: return false
        return ask(false) {
            host.utimes(path, entry.accessedMs, time)
            true
        }
    }

    override fun setReadOnly() = exists() && chmod(permissions() and 0x92.inv())

    override fun setWritable(
        writable: Boolean,
        ownerOnly: Boolean,
    ) = exists() && chmod(bits(permissions(), if (ownerOnly) 0x80 else 0x92, writable))

    override fun setReadable(
        readable: Boolean,
        ownerOnly: Boolean,
    ) = exists() && chmod(bits(permissions(), if (ownerOnly) 0x100 else 0x124, readable))

    override fun setExecutable(
        executable: Boolean,
        ownerOnly: Boolean,
    ) = exists() && chmod(bits(permissions(), if (ownerOnly) 0x40 else 0x49, executable))

    private fun bits(
        mode: Int,
        mask: Int,
        on: Boolean,
    ) = if (on) mode or mask else mode and mask.inv()

    private fun space(pick: (GuardHost.Space) -> Long): Long = ask(0L) { host.statfs(path).let { pick(it) * it.blockSize } }

    override fun getTotalSpace() = space { it.blocks }

    override fun getFreeSpace() = space { it.free }

    override fun getUsableSpace() = space { it.available }

    companion object {
        internal fun normalize(path: String): String {
            val kept = ArrayList<String>()
            for (name in path.split('/')) {
                when (name) {
                    "", "." -> Unit
                    ".." -> if (kept.isNotEmpty()) kept.removeAt(kept.size - 1)
                    else -> kept += name
                }
            }
            return "/" + kept.joinToString("/")
        }
    }
}

internal object TempFiles {
    private val random = SecureRandom()

    fun create(
        prefix: String,
        suffix: String?,
        directory: File?,
    ): File {
        require(prefix.length >= 3) { "Prefix string \"$prefix\" too short: length must be at least 3" }
        val folder = directory?.path ?: GuardContext.host().tmpdir()
        while (true) {
            val file = GuardFile(folder, prefix + java.lang.Long.toUnsignedString(random.nextLong()) + (suffix ?: ".tmp"))
            if (file.createNewFile()) return file
        }
    }
}
