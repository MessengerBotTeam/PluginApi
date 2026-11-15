/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.tck

import com.xfl.msgbot.plugin.api.call.CallException
import com.xfl.msgbot.plugin.api.call.CallResult
import com.xfl.msgbot.plugin.api.value.Value
import com.xfl.msgbot.plugin.api.value.asBooleanOrNull
import com.xfl.msgbot.plugin.api.value.asLongOrNull
import com.xfl.msgbot.plugin.api.value.asStringOrNull
import com.xfl.msgbot.plugin.api.value.vObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission

/**
 * The host `fs` module and `openFile` for one project folder, as the host serves them: the project folder and the
 * temporary folder may be used, every other place answers `EACCES`. Installs itself on an [EngineHarness].
 *
 * @param open turns a file that passed the rules into a descriptor of this process, opened as the
 * [com.xfl.msgbot.plugin.api.engine.HostFileMode] asks; on Android `ParcelFileDescriptor.open(...).detachFd()`.
 */
class FolderFiles(
    root: File,
    tmp: File,
    private val open: (file: File, mode: Int) -> Int,
) {
    val root: File = root.canonicalFile
    val tmp: File = tmp.canonicalFile

    private fun fail(
        errno: String,
        path: String,
    ): Nothing = throw CallException.failed("$errno: $path")

    /** The file a path names, after the rules; relative paths are relative to [root]. */
    fun resolve(path: String): File {
        val file = (if (path.startsWith("/")) File(path) else File(root, path)).let { File(it.toURI().normalize().path.trimEnd('/').ifEmpty { "/" }) }
        val parent = file.parentFile
        val real = if (parent != null && parent.exists()) File(parent.canonicalFile, file.name) else file
        if (!inside(real, root) && !inside(real, tmp)) fail("EACCES", path)
        return file
    }

    private fun inside(
        file: File,
        folder: File,
    ) = file.path == folder.path || file.path.startsWith(folder.path + "/")

    private fun stat(
        file: File,
        follow: Boolean,
    ): Value {
        val options = if (follow) emptyArray() else arrayOf(LinkOption.NOFOLLOW_LINKS)
        val path = file.toPath()
        val attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, *options)
        val mode = runCatching { Files.getPosixFilePermissions(path, *options).fold(0) { mode, p -> mode or (0x100 shr PosixFilePermission.entries.indexOf(p)) } }.getOrDefault(0x1b6)
        return vObject(
            "name" to file.name,
            "isDirectory" to attributes.isDirectory,
            "isSymbolicLink" to attributes.isSymbolicLink,
            "size" to attributes.size(),
            "modifiedMs" to attributes.lastModifiedTime().toMillis(),
            "accessedMs" to attributes.lastAccessTime().toMillis(),
            "createdMs" to attributes.creationTime().toMillis(),
            "mode" to mode.toLong(),
            "uid" to 0L,
            "gid" to 0L,
        )
    }

    private fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun answer(block: () -> Any?): CallResult = CallResult.catching { Value.of(block()) }

    private fun Map<String, Value>.text(key: String) = this[key]?.asStringOrNull() ?: throw CallException.badArgs("'$key' is missing")

    private fun Map<String, Value>.flag(key: String) = this[key]?.asBooleanOrNull() == true

    fun install(harness: EngineHarness) {
        val f = harness.functions
        f["fs.stat"] = { a -> answer { resolve(a.text("path")).let { if (exists(it)) stat(it, a["followLinks"]?.asBooleanOrNull() != false) else null } } }
        f["fs.realpath"] = { a -> answer { resolve(a.text("path")).let { if (it.exists()) resolve(it.canonicalPath).path else fail("ENOENT", a.text("path")) } } }
        f["fs.tmpdir"] = { answer { tmp.path } }
        f["fs.list"] = { a ->
            answer {
                val dir = resolve(a.text("path"))
                if (!dir.exists()) fail("ENOENT", a.text("path"))
                (dir.listFiles() ?: fail("ENOTDIR", a.text("path"))).map { stat(it, false) }
            }
        }
        f["fs.access"] = { a ->
            answer {
                val file = resolve(a.text("path"))
                if (!file.exists()) fail("ENOENT", a.text("path"))
                val mode = a["mode"]?.asLongOrNull()?.toInt() ?: 0
                val ok = ((mode and 4) == 0 || file.canRead()) && ((mode and 2) == 0 || file.canWrite()) && ((mode and 1) == 0 || file.canExecute())
                if (!ok) fail("EACCES", a.text("path"))
                true
            }
        }
        f["fs.statfs"] = { a -> answer { resolve(a.text("path")).let { vObject("type" to 0L, "bsize" to 1L, "blocks" to it.totalSpace, "bfree" to it.freeSpace, "bavail" to it.usableSpace, "files" to 0L, "ffree" to 0L) } } }
        f["fs.mkdir"] = { a ->
            answer {
                val dir = resolve(a.text("path"))
                when {
                    dir.isDirectory -> false
                    dir.exists() -> fail("EEXIST", a.text("path"))
                    a.flag("recursive") -> dir.mkdirs()
                    dir.parentFile?.isDirectory != true -> fail("ENOENT", a.text("path"))
                    else -> dir.mkdir()
                }
            }
        }
        f["fs.remove"] = { a ->
            answer {
                val file = resolve(a.text("path"))
                when {
                    !exists(file) -> false
                    a.flag("recursive") -> file.deleteRecursively()
                    file.isDirectory && !Files.isSymbolicLink(file.toPath()) && !file.list().isNullOrEmpty() -> fail("ENOTEMPTY", a.text("path"))
                    else -> file.delete()
                }
            }
        }
        f["fs.rename"] = { a ->
            answer {
                val from = resolve(a.text("from"))
                val to = resolve(a.text("to"))
                if (!exists(from)) fail("ENOENT", a.text("from"))
                from.renameTo(to) || fail("EIO", a.text("from"))
            }
        }
        f["fs.symlink"] = { a -> answer { Files.createSymbolicLink(resolve(a.text("path")).toPath(), File(a.text("target")).toPath()); true } }
        f["fs.link"] = { a -> answer { Files.createLink(resolve(a.text("path")).toPath(), resolve(a.text("existing")).toPath()); true } }
        f["fs.readlink"] = { a -> answer { Files.readSymbolicLink(resolve(a.text("path")).toPath()).toString() } }
        f["fs.truncate"] = { a -> answer { java.io.RandomAccessFile(resolve(a.text("path")), "rw").use { it.setLength(a["size"]?.asLongOrNull() ?: 0) }; true } }
        f["fs.chmod"] = { a ->
            answer {
                val mode = a["mode"]?.asLongOrNull()?.toInt() ?: 0
                Files.setPosixFilePermissions(resolve(a.text("path")).toPath(), PosixFilePermission.entries.filterIndexed { i, _ -> (mode and (0x100 shr i)) != 0 }.toSet())
                true
            }
        }
        f["fs.utimes"] = { a ->
            answer {
                val file = resolve(a.text("path"))
                if (!file.exists()) fail("ENOENT", a.text("path"))
                Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(a["modifiedMs"]?.asLongOrNull() ?: 0))
                true
            }
        }
        f["log.write"] = { CallResult.ok() }
        harness.openFile = { path, mode ->
            val file = resolve(path)
            if (file.isDirectory && mode != 0) fail("EISDIR", path)
            if ((mode and 4) != 0 && file.exists()) fail("EEXIST", path)
            if ((mode == 0 || mode == 8) && !file.exists()) fail("ENOENT", path)
            if (mode != 0 && file.parentFile?.isDirectory != true) fail("ENOENT", path)
            open(file, mode)
        }
    }
}
