/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.value

/**
 * Descriptor for a large/binary payload: it describes how to reach the bytes, not the bytes
 * themselves. Control messages carry only the descriptor; bytes travel out-of-band per
 * [transport]. Shims hide the transport choice behind blob.bytes()/blob.stream().
 *
 * @property id ownership/lifetime id. Default policy: a blob lives until the response of the
 *   call that passed it; longer retention requires an explicit retain.
 * @property size byte length, or [UNKNOWN_SIZE] when streaming.
 */
class Blob(
    val id: Long,
    val size: Long,
    val mime: String,
    val transport: Transport,
) {
    /**
     * Where the bytes actually travel. Phase 1 uses [Inline] only; the others are defined now
     * so Phase 2 (separate process) needs no signature change. FDs are abstracted as Long so
     * this module stays Android-free.
     */
    sealed interface Transport {
        class Inline(val bytes: ByteArray) : Transport
        class Shm(val fd: Long, val offset: Long, val length: Long) : Transport
        class Pipe(val fd: Long) : Transport
        class FileRef(val handle: Long) : Transport
    }

    companion object {
        const val UNKNOWN_SIZE: Long = -1L
        const val DEFAULT_MIME: String = "application/octet-stream"

        fun ofInline(id: Long, bytes: ByteArray, mime: String = DEFAULT_MIME): Blob =
            Blob(id = id, size = bytes.size.toLong(), mime = mime, transport = Transport.Inline(bytes))
    }
}
