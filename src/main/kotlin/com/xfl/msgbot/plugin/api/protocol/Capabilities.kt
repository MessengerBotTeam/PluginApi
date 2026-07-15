/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.protocol

/** Capability names invokable via [com.xfl.msgbot.plugin.api.bridge.HostBridge.call]. */
object Capabilities {
    // Messaging
    const val REPLY = "reply" // (replyToken, text) -> VBool
    const val MARK_AS_READ = "markAsRead" // (readToken) -> VBool
    const val SEND = "send" // (room, msg, packageName?) -> VBool
    const val CAN_REPLY = "canReply" // (room, packageName?) -> VBool

    // Logging
    const val LOG = "log" // (level, tag, message) -> VNull

    // Large payload / image (Blob path)
    const val IMAGE_BYTES = "imageBytes" // (VHandle) -> VBlob

    // Bot meta / control
    const val BOT_META = "botMeta" // (field) -> Value
    const val BOT_CONTROL = "botControl" // (action, arg?) -> Value

    // FileStream
    const val FILE_READ = "fileRead" // (path) -> VString
    const val FILE_WRITE = "fileWrite" // (path, data, append?) -> VBool
    const val FILE_DELETE = "fileDelete" // (path) -> VBool
    const val FILE_EXISTS = "fileExists" // (path) -> VBool

    // Key-value database
    const val DB_GET = "dbGet" // (name) -> VString
    const val DB_PUT = "dbPut" // (name, data) -> VBool
    const val DB_DELETE = "dbDelete" // (name) -> VBool

    // HTTP
    const val HTTP_REQUEST = "httpRequest" // (VObject{method,url,body,headers}) -> VObject{status,body}

    // Device
    const val DEVICE_INFO = "deviceInfo" // (field) -> Value

    // Timers (host-scheduled; fire back a TIMER event)
    const val TIMER_SET = "timerSet" // (timerId, delayMs, repeat) -> VNull
    const val TIMER_CLEAR = "timerClear" // (timerId) -> VNull
}
