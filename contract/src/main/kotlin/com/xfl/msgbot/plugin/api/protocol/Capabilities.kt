/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.protocol

/**
 * Capability names invokable via [com.xfl.msgbot.plugin.api.bridge.HostBridge.call], named
 * `namespace.method`, one per operation: a name is advertised only when it actually works.
 */
object Capabilities {
    fun qualify(
        namespace: String,
        method: String,
    ): String = "$namespace.$method"

    /** `event.message` advertised means [Events.MESSAGE] is actually delivered. */
    fun event(type: String): String = qualify(EVENT_NAMESPACE, type)

    const val EVENT_NAMESPACE: String = "event"

    /**
     * The bot itself: messaging plus the script's own lifecycle. Messaging methods are the ones a
     * [com.xfl.msgbot.plugin.api.provider.CapabilityProvider] declares support for.
     */
    object Bot {
        const val NAMESPACE = "bot"

        const val REPLY = "reply" // (replyToken, text) -> VBool
        const val MARK_AS_READ = "markAsRead" // (room, packageName?) -> VBool
        const val MARK_AS_READ_TOKEN = "markAsReadToken" // (readToken) -> VBool
        const val SEND = "send" // (room, msg, packageName?) -> VBool
        const val CAN_REPLY = "canReply" // (room, packageName?) -> VBool

        const val GET_NAME = "getName" // () -> VString
        const val GET_ROOT_PATH = "getRootPath" // () -> VString
        const val GET_POWER = "getPower" // () -> VBool
        const val SET_POWER = "setPower" // (power) -> VNull
        const val COMPILE = "compile" // () -> VNull
        const val UNLOAD = "unload" // () -> VNull
    }

    object Log {
        const val NAMESPACE = "log"

        const val WRITE = "write" // (level, tag, message) -> VNull
    }

    object Image {
        const val NAMESPACE = "image"

        const val BYTES = "bytes" // (VHandle) -> VBlob
    }

    object File {
        const val NAMESPACE = "file"

        const val READ = "read" // (path) -> VString
        const val WRITE = "write" // (path, data, append?) -> VBool
        const val DELETE = "delete" // (path) -> VBool
        const val EXISTS = "exists" // (path) -> VBool
    }

    object Db {
        const val NAMESPACE = "db"

        const val GET = "get" // (name) -> VString
        const val PUT = "put" // (name, data) -> VBool
        const val DELETE = "delete" // (name) -> VBool
    }

    object Http {
        const val NAMESPACE = "http"

        const val REQUEST = "request" // (VObject{method,url,body,headers}) -> VObject{status,body}
    }

    object Device {
        const val NAMESPACE = "device"

        const val INFO = "info" // (field) -> Value
    }

    /**
     * Deferred completion: [SET] takes a callback id minted by the shim, answered later by an
     * [Events.CALLBACK] event carrying it back.
     */
    object Timer {
        const val NAMESPACE = "timer"

        const val SET = "set" // (callbackId, delayMs, repeat) -> VNull
        const val CLEAR = "clear" // (callbackId) -> VNull
    }
}
