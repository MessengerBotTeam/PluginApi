/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.protocol

/**
 * Host -> script event type constants and field keys. Events are [Value.VObject] with a [TYPE]
 * key; the shim's `__dispatch` branches on it and builds the idiomatic event object.
 */
object Events {
    const val TYPE = "type"

    const val MESSAGE = "message"
    const val COMMAND = "command"
    const val NOTIFICATION_POSTED = "notificationPosted"
    const val NOTIFICATION_REMOVED = "notificationRemoved"
    const val START_COMPILE = "startCompile"
    const val TICK = "tick"
    const val TIMER = "timer"

    /** TIMER event payload key. */
    const val TIMER_ID = "timerId"

    object Message {
        const val ROOM = "room"
        const val CONTENT = "content"
        const val CHANNEL_ID = "channelId"
        const val LOG_ID = "logId"
        const val PACKAGE_NAME = "packageName"
        const val IS_GROUP_CHAT = "isGroupChat"
        const val IS_MENTION = "isMention"
        const val IS_MULTI_CHAT = "isMultiChat"
        const val IS_DEBUG_ROOM = "isDebugRoom"
        const val AUTHOR = "author"
        const val IMAGE = "image"

        /** Opaque tokens issued by the host, returned on capability calls. */
        const val REPLY_TOKEN = "replyToken"
        const val READ_TOKEN = "readToken"
    }

    object Author {
        const val NAME = "name"
        const val HASH = "hash"
        const val AVATAR = "avatar"
    }

    object Notification {
        const val PACKAGE_NAME = "packageName"
        const val CONTENT = "content"
        const val SENDER = "sender"
        const val ROOM = "room"
        const val LOG_ID = "logId"
        const val NOTIFICATION_ID = "notificationId"
    }
}
