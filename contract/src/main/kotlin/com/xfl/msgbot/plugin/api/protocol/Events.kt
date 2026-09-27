/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.protocol

/**
 * Host -> script event type constants and field keys. Events are `Value.VObject` with a [TYPE]
 * key; the shim's `__dispatch` branches on it and builds the idiomatic event object.
 *
 * A type is listed only once the host can deliver it; the ones it will fire are advertised via
 * [Capabilities.event].
 */
object Events {
    const val TYPE = "type"

    /**
     * Which source produced this event. The host stamps it on arrival and delivers the event only
     * to projects using that source, so two bots on different messengers never see each other's
     * traffic. Scripts do not read it: a script is written against a source, not aware of it.
     */
    const val SOURCE_ID = "sourceId"
    const val PROVIDER_ID = "providerId"

    const val MESSAGE = "message"
    const val COMMAND = "command"
    const val NOTIFICATION_POSTED = "notificationPosted"
    const val NOTIFICATION_REMOVED = "notificationRemoved"
    const val START_COMPILE = "startCompile"

    /**
     * A deferred capability completed. The shim holds the callback and passes only [CALLBACK_ID];
     * a function cannot cross the boundary.
     */
    const val CALLBACK = "callback"

    /** CALLBACK payload keys. [CALLBACK_ARGS] is a VArray passed through to the callback. */
    const val CALLBACK_ID = "callbackId"
    const val CALLBACK_ARGS = "args"

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
