/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.guard

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.xfl.msgbot.plugin.api.engine.HostFileMode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** A host that serves [root] and [tmp] the way the app's `ProjectFiles` does, and refuses every other place with EACCES. */
class FolderHost(
    rootFolder: File,
    private val tmpFolder: File,
) : GuardHost {
    override val root: String = rootFolder.canonicalPath
    private val tmp = tmpFolder.canonicalPath
    val logs = CopyOnWriteArrayList<String>()

    private fun resolve(path: String): String {
        val absolute = if (path.startsWith("/")) path else "$root/$path"
        val clean = GuardFile.normalize(absolute)
        val parent = File(clean).parentFile
        val real = if (parent != null && parent.exists()) File(parent.canonicalFile, File(clean).name).path else clean
        if (real != root && !real.startsWith("$root/") && real != tmp && !real.startsWith("$tmp/")) throw HostIOException("EACCES", path, "permission denied, '$path'")
        return clean
    }

    private fun <T> os(
        path: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: ErrnoException) {
            throw HostIOException(OsConstants.errnoName(e.errno) ?: "EIO", path, e.message ?: "")
        }

    private fun entry(stat: StructStat) =
        GuardHost.Entry(
            isDirectory = OsConstants.S_ISDIR(stat.st_mode),
            isSymbolicLink = OsConstants.S_ISLNK(stat.st_mode),
            size = stat.st_size,
            modifiedMs = stat.st_mtim.tv_sec * 1000 + stat.st_mtim.tv_nsec / 1_000_000,
            accessedMs = stat.st_atim.tv_sec * 1000 + stat.st_atim.tv_nsec / 1_000_000,
            createdMs = 0,
            mode = stat.st_mode and 0xfff,
            uid = stat.st_uid,
            gid = stat.st_gid,
        )

    override fun stat(
        path: String,
        followLinks: Boolean,
    ): GuardHost.Entry? {
        val real = resolve(path)
        return try {
            entry(if (followLinks) Os.stat(real) else Os.lstat(real))
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ENOENT || e.errno == OsConstants.ENOTDIR) null else throw HostIOException(OsConstants.errnoName(e.errno) ?: "EIO", path, e.message ?: "")
        }
    }

    override fun list(path: String): List<String> {
        val real = resolve(path)
        val file = File(real)
        if (!file.exists()) throw HostIOException("ENOENT", path, "no such file or directory, scandir '$path'")
        return file.list()?.toList() ?: throw HostIOException("ENOTDIR", path, "not a directory, scandir '$path'")
    }

    override fun access(
        path: String,
        mode: Int,
    ): Boolean =
        try {
            Os.access(resolve(path), mode)
        } catch (e: Exception) {
            false
        }

    override fun statfs(path: String): GuardHost.Space {
        val real = resolve(path)
        val s = os(path) { Os.statvfs(real) }
        return GuardHost.Space(s.f_bsize, s.f_blocks, s.f_bfree, s.f_bavail)
    }

    override fun mkdir(
        path: String,
        recursive: Boolean,
    ): Boolean {
        val file = File(resolve(path))
        if (file.isDirectory) return false
        if (file.exists()) throw HostIOException("EEXIST", path, "file already exists, mkdir '$path'")
        if (recursive) return file.mkdirs()
        os(path) { Os.mkdir(file.path, 0x1ff) }
        return true
    }

    override fun remove(
        path: String,
        recursive: Boolean,
    ): Boolean {
        val file = File(resolve(path))
        if (!file.exists() && !isLink(file)) return false
        if (recursive) return file.deleteRecursively()
        os(path) { Os.remove(file.path) }
        return true
    }

    private fun isLink(file: File) = runCatching { OsConstants.S_ISLNK(Os.lstat(file.path).st_mode) }.getOrDefault(false)

    override fun rename(
        from: String,
        to: String,
    ) {
        val a = resolve(from)
        val b = resolve(to)
        os(from) { Os.rename(a, b) }
    }

    override fun symlink(
        target: String,
        path: String,
    ) {
        val real = resolve(path)
        os(path) { Os.symlink(target, real) }
    }

    override fun link(
        existing: String,
        path: String,
    ) {
        val a = resolve(existing)
        val b = resolve(path)
        os(path) { Os.link(a, b) }
    }

    override fun readlink(path: String): String {
        val real = resolve(path)
        return os(path) { Os.readlink(real) }
    }

    override fun truncate(
        path: String,
        size: Long,
    ) {
        val real = resolve(path)
        os(path) {
            val fd = Os.open(real, OsConstants.O_WRONLY, 0)
            try {
                Os.ftruncate(fd, size)
            } finally {
                Os.close(fd)
            }
        }
    }

    override fun chmod(
        path: String,
        mode: Int,
    ) {
        val real = resolve(path)
        os(path) { Os.chmod(real, mode) }
    }

    override fun utimes(
        path: String,
        accessedMs: Long,
        modifiedMs: Long,
    ) {
        val real = resolve(path)
        if (!File(real).exists()) throw HostIOException("ENOENT", path, "no such file or directory")
        File(real).setLastModified(modifiedMs)
    }

    override fun realpath(path: String): String {
        val real = resolve(path)
        if (!File(real).exists()) throw HostIOException("ENOENT", path, "no such file or directory, realpath '$path'")
        return File(real).canonicalPath.also { resolve(it) }
    }

    override fun tmpdir(): String = tmp

    override fun open(
        path: String,
        mode: Int,
    ): ParcelFileDescriptor {
        val real = resolve(path)
        val readWrite = (mode and HostFileMode.READ_WRITE) != 0
        var flags =
            when {
                (mode and HostFileMode.APPEND) != 0 -> OsConstants.O_CREAT or OsConstants.O_APPEND or (if (readWrite) OsConstants.O_RDWR else OsConstants.O_WRONLY)
                (mode and HostFileMode.WRITE) != 0 -> OsConstants.O_CREAT or OsConstants.O_TRUNC or (if (readWrite) OsConstants.O_RDWR else OsConstants.O_WRONLY)
                readWrite -> OsConstants.O_RDWR
                else -> OsConstants.O_RDONLY
            }
        if ((mode and HostFileMode.EXCLUSIVE) != 0) flags = flags or OsConstants.O_EXCL
        val fd = os(path) { Os.open(real, flags or OsConstants.O_CLOEXEC, 0x1b6) }
        if (OsConstants.S_ISDIR(Os.fstat(fd).st_mode) && (flags and OsConstants.O_ACCMODE) != OsConstants.O_RDONLY) {
            Os.close(fd)
            throw HostIOException("EISDIR", path, "illegal operation on a directory")
        }
        return ParcelFileDescriptor.dup(fd).also { Os.close(fd) }
    }

    override fun log(
        level: String,
        message: String,
    ) {
        logs += "$level $message"
    }
}
