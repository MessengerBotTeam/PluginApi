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
 * The modules every project can count on, and the standards providers implement. Profiles build
 * their facades on these.
 *
 * - [Project], [Log], [File], [Db], [Http], [Device], [Sys]: answered by the host, and only by it.
 * - [Bot]: a standard. Providers implement compatible parts of it ([ModuleSpec.accepts]); a
 *   project may combine several, one per member.
 *
 * Standards evolve by addition: a new function, event, optional parameter or optional field keeps
 * the version, so providers built against an older one stay accepted. Only an incompatible change
 * raises it.
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
     * Messaging, messenger-agnostic. Any number of providers may implement parts of it (a
     * notification reader, a database reader, an Intent sender); a project composes them.
     * Addresses are optional so each provider can take the one it understands: a reply token it
     * issued, a room name, or a channel ID.
     */
    val Bot: ModuleSpec =
        moduleSpec("bot") {
            doc = "The messenger a project talks through, as far as its providers support it."
            function("reply", returns = Type.BOOL, doc = "Answer the message that carried this reply token.") {
                param("token", Type.STRING)
                param("text", Type.STRING)
            }
            function("markRead", returns = Type.BOOL, doc = "Mark the message that carried this read token as read.") {
                param("token", Type.STRING)
            }
            function("send", returns = Type.BOOL, doc = "Send to a room, found by whichever address the provider understands.") {
                param("text", Type.STRING)
                optional("room", Type.STRING)
                optional("channelId", Type.STRING)
                optional("packageName", Type.STRING)
            }
            function("canReply", returns = Type.BOOL) {
                optional("room", Type.STRING)
                optional("channelId", Type.STRING)
                optional("packageName", Type.STRING)
            }
            function("markRoomRead", returns = Type.BOOL) {
                optional("room", Type.STRING)
                optional("channelId", Type.STRING)
                optional("packageName", Type.STRING)
            }
            function("image", returns = Type.BYTES.nullable(), doc = "The bytes behind an image token, while the provider still has them.") {
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
                optional("replyToken", Type.STRING, doc = "Absent when this message cannot be answered by token")
                optional("readToken", Type.STRING)
                optional("extra", Type.map(Type.ANY), doc = "What only this provider knows (attachments, message type...), as it documents it.")
            }
        }

    /** Host services for profiles: which events the script listens to, so no other crosses to it. */
    val Sys: ModuleSpec =
        moduleSpec("sys") {
            doc = "The script's own plumbing."
            function("listen", doc = "Deliver only these events from now on. Until first called, every event is delivered.") {
                param("events", Type.list(Type.STRING))
            }
        }

    /** Answered by the host for every project. */
    val HOST: List<ModuleSpec> = listOf(Project, Log, File, Db, Http, Device, Sys)

    /** Namespaces no provider may publish: the host answers them. */
    val HOST_NAMESPACES: Set<String> = HOST.map { it.namespace }.toSet()

    /** Namespaces with a published standard: a provider may implement any compatible part of one. */
    val STANDARDS: Map<String, ModuleSpec> = listOf(Bot).associateBy { it.namespace }
}
