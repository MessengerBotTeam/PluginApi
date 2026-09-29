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
                optional("token", Type.STRING, doc = "The message's replyToken, meaningful to the provider that issued it")
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
                optional("token", Type.STRING, doc = "The message's readToken, meaningful to the provider that issued it")
                optional("room", Type.STRING)
                optional("channelId", Type.STRING)
                optional("packageName", Type.STRING)
            }
            function(
                "image",
                returns = Type.BYTES.nullable(),
                doc = "The bytes behind an image token, while the provider still has them.",
            ) {
                param("token", Type.STRING)
            }

            event("message") {
                field("room", Type.STRING)
                field("content", Type.STRING)
                field(
                    "author",
                    Type.struct {
                        field("name", Type.STRING)
                        optional("hash", Type.STRING, doc = "A stable ID of the author, where the messenger has one")
                        optional("avatar", Type.STRING, doc = "An image token")
                    },
                )
                optional("channelId", Type.STRING, doc = "IDs are strings so no language rounds them.")
                optional("logId", Type.STRING)
                optional("isGroupChat", Type.BOOL)
                optional("packageName", Type.STRING)
                optional("isMention", Type.BOOL)
                optional("isMultiChat", Type.BOOL)
                optional("isDebugRoom", Type.BOOL, doc = "Set by the host for the debug room; providers leave it out")
                optional("image", Type.STRING, doc = "An image token")
                optional("replyToken", Type.STRING, doc = "Absent when this message cannot be answered by token")
                optional("readToken", Type.STRING)
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

    val HOST: List<ModuleSpec> = listOf(Project, Log, File, Db, Http, Device, Sys)

    /** Namespaces providers may not publish. */
    val HOST_NAMESPACES: Set<String> = HOST.map { it.namespace }.toSet()

    /** Standards a provider may implement in part. */
    val STANDARDS: Map<String, ModuleSpec> = listOf(Bot).associateBy { it.namespace }
}
