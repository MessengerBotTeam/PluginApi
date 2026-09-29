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
 * Moves byte payloads of [THRESHOLD_BYTES] or more into read-only shared memory, sent via [publish]
 * ahead of the frame. Binder transactions share a ~1MB per-process buffer, so large inline payloads
 * fail with TransactionTooLargeException.
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
            // Read-only before handing it over.
            region.setProtect(OsConstants.PROT_READ)
            transferIds.incrementAndGet().also { publish(it, region) }
        } catch (e: Exception) {
            Log.w(TAG, "Shared memory unavailable; keeping ${bytes.size} bytes inline", e)
            null
        } finally {
            // Binder dups the descriptor during publish, so close ours either way.
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

    fun receive(
        transferId: Long,
        region: SharedMemory,
    ) {
        // Regions whose frame was dropped are never claimed. IDs increase, so evict the oldest.
        while (received.size >= MAX_OUTSTANDING) {
            val oldest = received.keys.minOrNull() ?: break
            Log.w(TAG, "Discarding shared region $oldest: $MAX_OUTSTANDING wait for their frames")
            received.remove(oldest)?.let { runCatching { it.close() } }
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
