/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.guard

import android.os.ParcelFileDescriptor
import java.io.IOException

/**
 * The files a script may reach, as the host decides. Every answer comes from the project's file rules, never from the
 * disk of this process, which the plugin cannot read anyway.
 *
 * Paths are absolute or relative to the project folder. A call the host refuses throws [HostIOException] with the POSIX
 * error name the host gave (`EACCES` for a place outside the rules).
 */
interface GuardHost {
    class Entry(
        val isDirectory: Boolean,
        val isSymbolicLink: Boolean,
        val size: Long,
        val modifiedMs: Long,
        val accessedMs: Long,
        val createdMs: Long,
        val mode: Int,
        val uid: Int,
        val gid: Int,
    ) {
        // The host reports permission bits only, so devices and pipes count as regular files.
        val isRegularFile: Boolean get() = !isDirectory && !isSymbolicLink
    }

    class Space(
        val blockSize: Long,
        val blocks: Long,
        val free: Long,
        val available: Long,
    )

    /** Absolute path of the project folder. */
    val root: String

    /** Null when nothing is there. */
    fun stat(
        path: String,
        followLinks: Boolean = true,
    ): Entry?

    fun list(path: String): List<String>

    /** Whether the path may be used as [mode] asks (`R_OK` 4, `W_OK` 2, `X_OK` 1); false instead of `EACCES`/`ENOENT`. */
    fun access(
        path: String,
        mode: Int,
    ): Boolean

    fun statfs(path: String): Space

    /** False when a folder is already there. */
    fun mkdir(
        path: String,
        recursive: Boolean,
    ): Boolean

    /** False when nothing was there. */
    fun remove(
        path: String,
        recursive: Boolean,
    ): Boolean

    fun rename(
        from: String,
        to: String,
    )

    fun symlink(
        target: String,
        path: String,
    )

    fun link(
        existing: String,
        path: String,
    )

    fun readlink(path: String): String

    fun truncate(
        path: String,
        size: Long,
    )

    fun chmod(
        path: String,
        mode: Int,
    )

    fun utimes(
        path: String,
        accessedMs: Long,
        modifiedMs: Long,
    )

    fun realpath(path: String): String

    /** The shared temporary folder; whether the script may use it is up to its file rules. */
    fun tmpdir(): String

    /** Opens [path] as a [com.xfl.msgbot.plugin.api.engine.HostFileMode]; the caller owns the descriptor. */
    fun open(
        path: String,
        mode: Int,
    ): ParcelFileDescriptor

    /** Writes a line to the project log. Never throws. */
    fun log(
        level: String,
        message: String,
    )
}

/** A refusal or failure from the host. [errno] is the POSIX name (`ENOENT`, `EACCES`, ...), or `EIO` when the host gave none. */
class HostIOException(
    val errno: String,
    val path: String,
    val detail: String,
) : IOException("$errno: $detail") {
    val isDenied: Boolean get() = errno == "EACCES" || errno == "EPERM"

    val isMissing: Boolean get() = errno == "ENOENT"

    companion object {
        private val NAME = Regex("^(E[A-Z0-9]+)\\b")

        fun of(
            path: String,
            message: String?,
        ): HostIOException {
            val text = message.orEmpty()
            val errno = NAME.find(text)?.groupValues?.get(1) ?: "EIO"
            return HostIOException(errno, path, text.removePrefix(errno).trimStart(':', ' ').ifEmpty { errno })
        }
    }
}
