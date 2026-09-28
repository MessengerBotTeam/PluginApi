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
 * The modules every project can count on, and the one every message source implements. Profiles
 * build their facades on these; each namespace here has exactly one owner.
 *
 * - [Project], [Log], [File], [Db], [Http], [Device]: answered by the host.
 * - [Bot]: answered by the project's message source. A source implements the parts it supports,
 *   with these exact signatures, and the host refuses one that does not.
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

    val Bot: ModuleSpec =
        moduleSpec("bot") {
            doc = "The messenger a project talks through, as far as its source supports it."
            function("reply", returns = Type.BOOL, doc = "Answer the message that carried this reply token.") {
                param("token", Type.STRING)
                param("text", Type.STRING)
            }
            function("markRead", returns = Type.BOOL, doc = "Mark the message that carried this read token as read.") {
                param("token", Type.STRING)
            }
            function("send", returns = Type.BOOL) {
                param("room", Type.STRING)
                param("text", Type.STRING)
                optional("packageName", Type.STRING)
            }
            function("canReply", returns = Type.BOOL) {
                param("room", Type.STRING)
                optional("packageName", Type.STRING)
            }
            function("markRoomRead", returns = Type.BOOL) {
                param("room", Type.STRING)
                optional("packageName", Type.STRING)
            }
            function("image", returns = Type.BYTES.nullable(), doc = "The bytes behind an image token, while the source still has them.") {
                param("token", Type.STRING)
            }

            event("message") {
                field("room", Type.STRING)
                field("content", Type.STRING)
                field("channelId", Type.STRING, doc = "IDs are strings so no language rounds them.")
                field("logId", Type.STRING)
                field("isGroupChat", Type.BOOL)
                field(
                    "author",
                    Type.struct {
                        field("name", Type.STRING)
                        field("hash", Type.STRING)
                        optional("avatar", Type.STRING, doc = "An image token")
                    },
                )
                optional("packageName", Type.STRING)
                optional("isMention", Type.BOOL)
                optional("isMultiChat", Type.BOOL)
                optional("isDebugRoom", Type.BOOL)
                optional("image", Type.STRING, doc = "An image token")
                optional("replyToken", Type.STRING, doc = "Absent when this message cannot be answered")
                optional("readToken", Type.STRING)
            }
            event("notificationPosted") {
                field("packageName", Type.STRING)
                field("content", Type.STRING)
                field("sender", Type.STRING)
                field("room", Type.STRING)
                field("logId", Type.STRING)
            }
            event("notificationRemoved") {
                field("packageName", Type.STRING)
                field("notificationId", Type.STRING)
            }
        }

    /** Answered by the host for every project. */
    val HOST: List<ModuleSpec> = listOf(Project, Log, File, Db, Http, Device)

    /** No extension may claim these. `sys` is kept for the host's own later use. */
    val RESERVED_NAMESPACES: Set<String> = HOST.map { it.namespace }.toSet() + Bot.namespace + "sys"
}
