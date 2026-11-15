/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.guard

import android.os.ParcelFileDescriptor
import com.xfl.msgbot.plugin.api.call.CallException
import com.xfl.msgbot.plugin.api.call.CallResult
import com.xfl.msgbot.plugin.api.engine.HostBridge
import com.xfl.msgbot.plugin.api.value.Value
import com.xfl.msgbot.plugin.api.value.asArrayOrNull
import com.xfl.msgbot.plugin.api.value.asBooleanOrNull
import com.xfl.msgbot.plugin.api.value.asLongOrNull
import com.xfl.msgbot.plugin.api.value.asObjectOrNull
import com.xfl.msgbot.plugin.api.value.asStringOrNull

/** [GuardHost] over the host's `fs` and `log` modules and [HostBridge.openFile], for an engine's own [HostBridge]. */
class BridgeGuardHost(private val host: HostBridge) : GuardHost {
    // Asked on first use: an engine may not call the host while it loads.
    override val root: String by lazy { realpath(".") }

    override fun stat(
        path: String,
        followLinks: Boolean,
    ): GuardHost.Entry? {
        val answer =
            try {
                fs("stat", path, "path" to Value.VString(path), "followLinks" to Value.VBool(followLinks))
            } catch (e: HostIOException) {
                if (e.isMissing || e.errno == "ENOTDIR") return null
                throw e
            }
        return answer.asObjectOrNull()?.let(::entry)
    }

    override fun list(path: String): List<String> =
        fs("list", path, "path" to Value.VString(path)).asArrayOrNull().orEmpty().mapNotNull { it.asObjectOrNull()?.get("name")?.asStringOrNull() }

    override fun access(
        path: String,
        mode: Int,
    ): Boolean =
        try {
            fs("access", path, "path" to Value.VString(path), "mode" to Value.of(mode)).asBooleanOrNull() == true
        } catch (e: HostIOException) {
            false
        }

    override fun statfs(path: String): GuardHost.Space {
        val map = fs("statfs", path, "path" to Value.VString(path)).asObjectOrNull().orEmpty()
        fun long(key: String) = map[key]?.asLongOrNull() ?: 0L
        return GuardHost.Space(long("bsize"), long("blocks"), long("bfree"), long("bavail"))
    }

    override fun mkdir(
        path: String,
        recursive: Boolean,
    ) = fs("mkdir", path, "path" to Value.VString(path), "recursive" to Value.VBool(recursive)).asBooleanOrNull() == true

    override fun remove(
        path: String,
        recursive: Boolean,
    ) = fs("remove", path, "path" to Value.VString(path), "recursive" to Value.VBool(recursive)).asBooleanOrNull() == true

    override fun rename(
        from: String,
        to: String,
    ) {
        fs("rename", from, "from" to Value.VString(from), "to" to Value.VString(to))
    }

    override fun symlink(
        target: String,
        path: String,
    ) {
        fs("symlink", path, "target" to Value.VString(target), "path" to Value.VString(path))
    }

    override fun link(
        existing: String,
        path: String,
    ) {
        fs("link", path, "existing" to Value.VString(existing), "path" to Value.VString(path))
    }

    override fun readlink(path: String): String = fs("readlink", path, "path" to Value.VString(path)).asStringOrNull().orEmpty()

    override fun truncate(
        path: String,
        size: Long,
    ) {
        fs("truncate", path, "path" to Value.VString(path), "size" to Value.of(size))
    }

    override fun chmod(
        path: String,
        mode: Int,
    ) {
        fs("chmod", path, "path" to Value.VString(path), "mode" to Value.of(mode))
    }

    override fun utimes(
        path: String,
        accessedMs: Long,
        modifiedMs: Long,
    ) {
        fs("utimes", path, "path" to Value.VString(path), "accessedMs" to Value.of(accessedMs), "modifiedMs" to Value.of(modifiedMs))
    }

    override fun realpath(path: String): String = fs("realpath", path, "path" to Value.VString(path)).asStringOrNull() ?: throw HostIOException("ENOENT", path, path)

    override fun tmpdir(): String = fs("tmpdir", "").asStringOrNull() ?: throw HostIOException("ENOENT", "", "no temporary folder")

    override fun open(
        path: String,
        mode: Int,
    ): ParcelFileDescriptor =
        try {
            ParcelFileDescriptor.adoptFd(host.openFile(path, mode))
        } catch (e: CallException) {
            throw HostIOException.of(path, e.message)
        }

    override fun log(
        level: String,
        message: String,
    ) {
        try {
            host.call("log.write", mapOf("level" to Value.VString(level), "message" to Value.VString(message), "tag" to Value.VString(TAG)))
        } catch (_: Exception) {
        }
    }

    private fun fs(
        name: String,
        path: String,
        vararg args: Pair<String, Value>,
    ): Value =
        when (val answer = host.call("fs.$name", mapOf(*args))) {
            is CallResult.Ok -> answer.value
            is CallResult.Err -> throw HostIOException.of(path, answer.message)
        }

    private fun entry(map: Map<String, Value>): GuardHost.Entry {
        fun long(key: String) = map[key]?.asLongOrNull() ?: 0L
        return GuardHost.Entry(
            isDirectory = map["isDirectory"]?.asBooleanOrNull() == true,
            isSymbolicLink = map["isSymbolicLink"]?.asBooleanOrNull() == true,
            size = long("size"),
            modifiedMs = long("modifiedMs"),
            accessedMs = long("accessedMs"),
            createdMs = long("createdMs"),
            mode = long("mode").toInt(),
            uid = long("uid").toInt(),
            gid = long("gid").toInt(),
        )
    }

    companion object {
        const val TAG = "java"
    }
}
