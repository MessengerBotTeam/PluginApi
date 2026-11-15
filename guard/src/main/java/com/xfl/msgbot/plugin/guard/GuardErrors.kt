/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.guard

import android.system.Os
import android.system.OsConstants
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.NotLinkException

/** Turns host refusals into the exceptions the real classes throw on Android, with the same messages. */
internal object GuardErrors {
    private fun reason(errno: String): String =
        try {
            Os.strerror(OsConstants::class.java.getField(errno).getInt(null))
        } catch (_: ReflectiveOperationException) {
            errno
        }

    /** What `java.io` streams throw: `path: open failed: ENOENT (No such file or directory)`. */
    fun open(e: HostIOException): FileNotFoundException {
        report(e)
        return FileNotFoundException("${e.path}: open failed: ${e.errno} (${reason(e.errno)})").apply { initCause(e) }
    }

    /** What `java.io` throws elsewhere (`createNewFile`, `RandomAccessFile`): an [IOException] with the system's message. */
    fun io(e: HostIOException): IOException {
        report(e)
        return IOException(reason(e.errno), e)
    }

    /** What `java.nio.file` throws for the same failure. */
    fun nio(
        e: HostIOException,
        other: String? = null,
    ): FileSystemException {
        report(e)
        val path = e.path
        return when (e.errno) {
            "ENOENT" -> NoSuchFileException(path, other, null)
            "EACCES", "EPERM" -> AccessDeniedException(path, other, null)
            "EEXIST" -> FileAlreadyExistsException(path, other, null)
            "ENOTEMPTY" -> DirectoryNotEmptyException(path)
            "ENOTDIR" -> NotDirectoryException(path)
            "EINVAL" -> NotLinkException(path, other, null)
            else -> FileSystemException(path, other, reason(e.errno))
        }.apply { initCause(e) }
    }

    /**
     * What a `java.io.File` method that answers yes or no does when the rules forbid the path: the same
     * [SecurityException] a Java security manager throws there, instead of an answer that looks like "no".
     */
    fun denied(e: HostIOException): SecurityException {
        report(e)
        return SecurityException("${e.path}: not available to this project (${e.errno})", e)
    }

    private fun report(e: HostIOException) {
        if (e.isDenied) GuardContext.reportPath(e.path)
    }
}
