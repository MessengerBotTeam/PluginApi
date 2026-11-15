/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.guard

import android.os.ParcelFileDescriptor
import android.system.Os
import com.xfl.msgbot.plugin.api.engine.HostFileMode
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.lang.reflect.Method

/**
 * Puts a host descriptor under a descriptor number that a real JDK object already owns, so the object (a
 * `RandomAccessFile` and the `FileChannel` it hands out) works on the host's file with all of its own code.
 *
 * The real class can only be made by opening a path, so it first opens a placeholder this process may open, and the
 * host's descriptor is then copied over the placeholder's number with `dup2`.
 */
internal object Descriptors {
    // Unsupported but allowed hidden API; when a later Android refuses it, a pipe marks the placeholder instead.
    private val getInt: Method? = runCatching { FileDescriptor::class.java.getMethod("getInt" + '$') }.getOrNull()

    /** False makes the pipe marker run even where the hidden API works; only tests set it. */
    @Volatile internal var useHiddenApi = true

    private val marker = ThreadLocal<Array<ParcelFileDescriptor>?>()

    fun placeholder(): String {
        if (getInt != null && useHiddenApi) return "/dev/null"
        val pipe = ParcelFileDescriptor.createPipe()
        marker.set(pipe)
        return "/proc/self/fd/${pipe[0].fd}"
    }

    /** Makes [target] refer to [source]'s file and closes [source]. */
    fun retarget(
        target: FileDescriptor,
        source: ParcelFileDescriptor,
    ) {
        source.use { Os.dup2(it.fileDescriptor, number(target)) }
    }

    fun abandon() {
        marker.get()?.forEach { it.close() }
        marker.remove()
    }

    private fun number(target: FileDescriptor): Int {
        if (useHiddenApi) getInt?.let { method -> runCatching { return method.invoke(target) as Int } }
        val pipe = marker.get() ?: throw IllegalStateException("No placeholder is waiting on this thread")
        try {
            val link = Os.readlink("/proc/self/fd/${pipe[0].fd}")
            val own = setOf(pipe[0].fd, pipe[1].fd)
            return File("/proc/self/fd").list().orEmpty().mapNotNull { it.toIntOrNull() }.filter { it !in own }.single { number ->
                runCatching { Os.readlink("/proc/self/fd/$number") }.getOrNull() == link
            }
        } finally {
            abandon()
        }
    }
}

/**
 * `java.io.RandomAccessFile` for scripts: the real class, working on a descriptor the host opened under the
 * project's file rules. [getChannel] is a real `FileChannel`, so mapping and locking work too.
 *
 * Modes `rws` and `rwd` do not make each write synchronous; call `getFD().sync()` where that matters.
 */
class GuardRandomAccessFile private constructor(
    source: ParcelFileDescriptor,
    mode: String,
) : RandomAccessFile(Descriptors.placeholder(), mode) {
    init {
        try {
            Descriptors.retarget(fd, source)
        } catch (e: Throwable) {
            Descriptors.abandon()
            source.close()
            close()
            throw e
        }
    }

    @Throws(FileNotFoundException::class)
    constructor(path: String, mode: String) : this(openFor(path, checkMode(mode)), mode)

    @Throws(FileNotFoundException::class)
    constructor(file: File, mode: String) : this(file.path, mode)

    internal constructor(descriptor: ParcelFileDescriptor) : this(descriptor, "rw")

    companion object {
        private fun checkMode(mode: String): String {
            require(mode == "r" || mode == "rw" || mode == "rws" || mode == "rwd") {
                "Illegal mode \"$mode\" must be one of \"r\", \"rw\", \"rws\", or \"rwd\""
            }
            return mode
        }

        private fun openFor(
            path: String,
            mode: String,
        ): ParcelFileDescriptor {
            val host = GuardContext.host()
            try {
                if (mode == "r") return host.open(path, HostFileMode.READ)
                return try {
                    host.open(path, HostFileMode.READ_WRITE)
                } catch (e: HostIOException) {
                    if (!e.isMissing) throw e
                    try {
                        host.open(path, HostFileMode.WRITE or HostFileMode.READ_WRITE or HostFileMode.EXCLUSIVE)
                    } catch (raced: HostIOException) {
                        if (raced.errno != "EEXIST") throw raced
                        host.open(path, HostFileMode.READ_WRITE)
                    }
                }
            } catch (e: HostIOException) {
                throw GuardErrors.open(e)
            }
        }
    }
}
