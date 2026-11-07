/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.api.standard

import com.xfl.msgbot.plugin.api.schema.ModuleSpec
import com.xfl.msgbot.plugin.api.schema.Type
import com.xfl.msgbot.plugin.api.schema.moduleSpec
import com.xfl.msgbot.plugin.api.schema.nullable
import com.xfl.msgbot.plugin.api.schema.struct

/**
 * Built-in modules. [HOST] modules are implemented only by the host. [Bot] is a standard that
 * providers implement in parts, fitted with [ModuleSpec.fit].
 *
 * Additive changes (functions, events, optional parameters or fields) keep the version.
 */
object StandardApi {
    val Project: ModuleSpec =
        moduleSpec("project") {
            doc = "The running project itself."
            function("name", returns = Type.STRING)
            function("rootPath", returns = Type.STRING, doc = "Absolute path of the project folder.")
            function("power", returns = Type.BOOL, doc = "Whether the project is switched on.")
            function("setPower") { param("power", Type.BOOL) }
            function("compile", doc = "Reload the project, reading its script again.")
            function("unload")
            event("unloading", doc = "The engine is about to close. Save what must survive; asynchronous work will not finish.")
        }

    val Log: ModuleSpec =
        moduleSpec("log") {
            doc = "The project log, shown in the app and the debug room."
            function("write") {
                param("level", Type.STRING, doc = "debug, info, warn or error")
                param("message", Type.STRING)
                optional("tag", Type.STRING)
            }
        }

    val File: ModuleSpec =
        moduleSpec("file") {
            doc = "Text files inside the project folder. Paths outside it are refused."
            function("read", returns = Type.STRING.nullable()) { param("path", Type.STRING) }
            function("write", returns = Type.BOOL) {
                param("path", Type.STRING)
                param("data", Type.STRING)
                optional("append", Type.BOOL)
            }
            function("delete", returns = Type.BOOL) { param("path", Type.STRING) }
            function("exists", returns = Type.BOOL) { param("path", Type.STRING) }
        }

    /**
     * The file system calls an engine needs to offer its language's own file API. File contents do not
     * go through here: engines open them with [com.xfl.msgbot.plugin.api.engine.HostBridge.openFile].
     * A failing call answers with code `failed` and a message that starts with the POSIX error name,
     * for example `ENOENT: no such file or directory, stat 'a/b'`.
     */
    val Fs: ModuleSpec =
        moduleSpec("fs") {
            doc = "Files and folders inside the project folder. Paths outside it are refused with EACCES."
            val entry =
                Type.struct {
                    field("name", Type.STRING)
                    field("isDirectory", Type.BOOL)
                    field("isSymbolicLink", Type.BOOL)
                    field("size", Type.INT)
                    field("modifiedMs", Type.INT)
                    field("accessedMs", Type.INT)
                    field("createdMs", Type.INT)
                    field("mode", Type.INT, doc = "The permission bits, for example 0o644")
                    field("uid", Type.INT)
                    field("gid", Type.INT)
                }
            function("stat", returns = entry.nullable(), doc = "The entry at the path, or null when there is none.") {
                param("path", Type.STRING)
                optional("followLinks", Type.BOOL, doc = "True when left out. False describes a symbolic link itself.")
            }
            function("realpath", returns = Type.STRING, doc = "The absolute path with every link followed; ENOENT when nothing is there.") { param("path", Type.STRING) }
            function(
                "tmpdir",
                returns = Type.STRING,
                doc = "The absolute path of the temporary folder, which the host creates. Whether the script may use it is up to its file rules.",
            )
            function("list", returns = Type.list(entry), doc = "The entries of a folder, in no particular order.") { param("path", Type.STRING) }
            function(
                "names",
                returns = Type.BYTES,
                doc =
                    "The entries of a folder as names only, much cheaper than list. One byte string: for each entry a kind byte " +
                        "('d' folder, 'l' symbolic link, 'f' anything else, '-' when withTypes was not asked), the name in UTF-8 and a zero byte.",
            ) {
                param("path", Type.STRING)
                optional("withTypes", Type.BOOL, doc = "False when left out.")
            }
            function("access", returns = Type.BOOL, doc = "True when the path exists and may be used as mode asks; EACCES or ENOENT otherwise.") {
                param("path", Type.STRING)
                optional("mode", Type.INT, doc = "R_OK (4), W_OK (2) and X_OK (1) added together; 0 only asks whether it exists.")
            }
            function(
                "statfs",
                returns =
                    Type.struct {
                        field("type", Type.INT)
                        field("bsize", Type.INT)
                        field("blocks", Type.INT)
                        field("bfree", Type.INT)
                        field("bavail", Type.INT)
                        field("files", Type.INT)
                        field("ffree", Type.INT)
                    },
                doc = "The size of the storage the path is on, in blocks of bsize bytes.",
            ) { param("path", Type.STRING) }
            function("mkdir", returns = Type.BOOL, doc = "Creates a folder. False when it already exists as a folder.") {
                param("path", Type.STRING)
                optional("recursive", Type.BOOL, doc = "Creates missing parents too. Without it a missing parent is ENOENT.")
            }
            function("remove", returns = Type.BOOL, doc = "Deletes a file or folder. False when there was nothing to delete.") {
                param("path", Type.STRING)
                optional("recursive", Type.BOOL, doc = "Deletes a folder's content too. Without it a folder that is not empty is ENOTEMPTY.")
            }
            function("rename", returns = Type.BOOL) {
                param("from", Type.STRING)
                param("to", Type.STRING)
            }
            function("symlink", returns = Type.BOOL, doc = "Makes [path] a link to [target], which is stored as it is written.") {
                param("target", Type.STRING)
                param("path", Type.STRING)
            }
            function("link", returns = Type.BOOL, doc = "Makes [path] another name of the file [existing]. Hard links only exist where the storage has them.") {
                param("existing", Type.STRING)
                param("path", Type.STRING)
            }
            function("readlink", returns = Type.STRING, doc = "Where a symbolic link points; EINVAL when the path is not a link.") { param("path", Type.STRING) }
            function("truncate", returns = Type.BOOL) {
                param("path", Type.STRING)
                param("size", Type.INT)
            }
            function("chmod", returns = Type.BOOL) {
                param("path", Type.STRING)
                param("mode", Type.INT)
            }
            function("chown", returns = Type.BOOL, doc = "Needs more rights than the app has, such as Shizuku; EPERM without them.") {
                param("path", Type.STRING)
                param("uid", Type.INT)
                param("gid", Type.INT)
            }
            function("utimes", returns = Type.BOOL) {
                param("path", Type.STRING)
                param("accessedMs", Type.INT)
                param("modifiedMs", Type.INT)
            }
        }

    val Db: ModuleSpec =
        moduleSpec("db") {
            doc = "A per-project key-value store of strings."
            function("get", returns = Type.STRING.nullable()) { param("key", Type.STRING) }
            function("put", returns = Type.BOOL) {
                param("key", Type.STRING)
                param("value", Type.STRING)
            }
            function("delete", returns = Type.BOOL) { param("key", Type.STRING) }
        }

    val Http: ModuleSpec =
        moduleSpec("http") {
            doc = "Plain HTTP requests."
            function(
                "request",
                returns =
                    Type.struct {
                        field("status", Type.INT)
                        field("headers", Type.map(Type.STRING))
                        field("body", Type.STRING)
                    },
            ) {
                param("url", Type.STRING)
                optional("method", Type.STRING, doc = "GET when left out")
                optional("headers", Type.map(Type.STRING))
                optional("body", Type.STRING)
            }
        }

    val Device: ModuleSpec =
        moduleSpec("device") {
            doc = "The phone the bot runs on."
            function(
                "info",
                returns =
                    Type.struct {
                        field("sdkInt", Type.INT)
                        field("model", Type.STRING)
                        field("manufacturer", Type.STRING)
                        field("brand", Type.STRING)
                    },
            )
        }

    /**
     * Messenger-agnostic messaging, composed from several providers. Address fields are optional and
     * all passed through, so each provider uses the ones it understands and can answer messages
     * another provider received.
     */
    val Bot: ModuleSpec =
        moduleSpec("bot") {
            doc = "The messenger a project talks through, as far as its providers support it."
            function(
                "reply",
                returns = Type.BOOL,
                doc = "Answer a message. Pass its token and its address; the provider uses what it understands.",
            ) {
                param("text", Type.STRING)
                optional("token", Type.handle("reply"), doc = "The message's replyToken, meaningful to the provider that issued it")
                optional("room", Type.STRING)
                optional("channelId", Type.STRING)
                optional("packageName", Type.STRING)
                optional("extra", Type.map(Type.ANY), doc = "What only the answering provider understands, as it documents it")
            }
            function("send", returns = Type.BOOL, doc = "Send to a room, found by whichever address the provider understands.") {
                param("text", Type.STRING)
                optional("room", Type.STRING)
                optional("channelId", Type.STRING)
                optional("packageName", Type.STRING)
                optional("extra", Type.map(Type.ANY), doc = "What only the sending provider understands, as it documents it")
            }
            function("canReply", returns = Type.BOOL) {
                optional("room", Type.STRING)
                optional("channelId", Type.STRING)
                optional("packageName", Type.STRING)
            }
            function("markRead", returns = Type.BOOL, doc = "Mark a message, or its room up to now, as read.") {
                optional("token", Type.handle("read"), doc = "The message's readToken, meaningful to the provider that issued it")
                optional("room", Type.STRING)
                optional("channelId", Type.STRING)
                optional("packageName", Type.STRING)
            }
            function(
                "image",
                returns = Type.BYTES.nullable(),
                doc = "The bytes behind an image token, while the provider still has them.",
            ) {
                param("token", Type.handle("image"))
            }

            event("message") {
                field("room", Type.STRING)
                field("content", Type.STRING)
                field(
                    "author",
                    Type.struct {
                        field("name", Type.STRING)
                        optional("hash", Type.STRING, doc = "A stable ID of the author, where the messenger has one")
                        optional("avatar", Type.handle("image"), doc = "An image token")
                    },
                )
                optional("channelId", Type.STRING, doc = "IDs are strings so no language rounds them.")
                optional("logId", Type.STRING)
                optional("isGroupChat", Type.BOOL)
                optional("packageName", Type.STRING)
                optional("sourceProviderId", Type.STRING, doc = "Set by the host to identify the provider that delivered this message")
                optional("isMention", Type.BOOL)
                optional("isMultiChat", Type.BOOL)
                optional("isDebugRoom", Type.BOOL, doc = "Set by the host for the debug room; providers leave it out")
                optional("isInjected", Type.BOOL, doc = "Set by the host when a sandbox handed this message over instead of a messenger; providers leave it out")
                optional("injectedBy", Type.STRING, doc = "The name of the sandbox that handed this message over; set by the host together with isInjected")
                optional("image", Type.handle("image"), doc = "An image token")
                optional("replyToken", Type.handle("reply"), doc = "Absent when this message cannot be answered by token")
                optional("readToken", Type.handle("read"))
                optional("extra", Type.map(Type.ANY), doc = "What only this provider knows, such as attachments or the message type.")
            }
        }

    /** Lets profiles filter which events reach the script. */
    val Sys: ModuleSpec =
        moduleSpec("sys") {
            doc = "The script's own plumbing."
            function("listen", doc = "Deliver only these events from now on. Until first called, every event is delivered.") {
                param("events", Type.list(Type.STRING))
            }
        }

    val HOST: List<ModuleSpec> = listOf(Project, Log, File, Fs, Db, Http, Device, Sys)

    /** The host's sandbox module, which only the host side adds, so it is not in [HOST]. */
    const val SANDBOX_NAMESPACE = "sandbox"

    /** Namespaces providers may not publish. */
    val HOST_NAMESPACES: Set<String> = HOST.map { it.namespace }.toSet() + SANDBOX_NAMESPACE

    /** Standards a provider may implement in part. */
    val STANDARDS: Map<String, ModuleSpec> = listOf(Bot).associateBy { it.namespace }
}
