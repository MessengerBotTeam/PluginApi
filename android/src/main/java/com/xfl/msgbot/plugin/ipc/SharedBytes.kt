/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.ipc

import android.os.SharedMemory
import android.system.OsConstants
import android.util.Log
import com.xfl.msgbot.plugin.api.serialization.BytesChannel
import com.xfl.msgbot.plugin.api.serialization.MalformedFrameException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Moves large byte payloads out of Binder frames into sealed shared memory.
 *
 * Every Binder transaction of a process shares a ~1MB buffer, so an inlined image eventually
 * throws TransactionTooLarge. From [THRESHOLD_BYTES] up, bytes go out through [publish] ahead of
 * the frame, which keeps only an ID. Smaller payloads stay inline, where mapping would cost more
 * than it saves. Both ends use this the same way; only where [publish] sends differs.
 */
class SharedBytes(
    private val publish: (transferId: Long, region: SharedMemory) -> Unit,
) : BytesChannel {
    private val received = ConcurrentHashMap<Long, SharedMemory>()
    private val transferIds = AtomicLong()

    override fun offload(bytes: ByteArray): Long? {
        if (bytes.size < THRESHOLD_BYTES) return null
        var region: SharedMemory? = null
        return try {
            region = SharedMemory.create("msgbot-bytes", bytes.size)
            val buffer = region.mapReadWrite()
            try {
                buffer.put(bytes)
            } finally {
                SharedMemory.unmap(buffer)
            }
            // Sealed before it leaves: the other side only ever reads it.
            region.setProtect(OsConstants.PROT_READ)
            transferIds.incrementAndGet().also { publish(it, region) }
        } catch (e: Exception) {
            Log.w(TAG, "Shared memory unavailable; keeping ${bytes.size} bytes inline", e)
            null
        } finally {
            // Binder duplicated the descriptor during publish, so ours is done either way.
            runCatching { region?.close() }
        }
    }

    override fun resolve(
        transferId: Long,
        length: Int,
    ): ByteArray {
        val region = received.remove(transferId) ?: throw MalformedFrameException("No shared region $transferId arrived")
        return try {
            if (length > region.size) throw MalformedFrameException("Shared region $transferId holds ${region.size} bytes, not $length")
            val buffer = region.mapReadOnly()
            try {
                ByteArray(length).also { buffer.get(it) }
            } finally {
                SharedMemory.unmap(buffer)
            }
        } finally {
            runCatching { region.close() }
        }
    }

    /** Called by the transport when the other side hands a region over. */
    fun receive(
        transferId: Long,
        region: SharedMemory,
    ) {
        if (received.size >= MAX_OUTSTANDING) {
            Log.w(TAG, "Refusing shared region $transferId: $MAX_OUTSTANDING already wait for their frames")
            region.close()
            return
        }
        received.put(transferId, region)?.close()
    }

    fun clear() {
        received.values.forEach { runCatching { it.close() } }
        received.clear()
    }

    private companion object {
        const val THRESHOLD_BYTES = 64 * 1024
        const val MAX_OUTSTANDING = 64
        const val TAG = "SharedBytes"
    }
}
