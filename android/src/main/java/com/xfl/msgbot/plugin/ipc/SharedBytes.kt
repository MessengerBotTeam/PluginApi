/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.ipc

import android.os.SharedMemory
import android.system.OsConstants
import android.util.Log
import com.xfl.msgbot.plugin.api.rpc.TransportClosedException
import com.xfl.msgbot.plugin.api.serialization.BytesChannel
import com.xfl.msgbot.plugin.api.serialization.MalformedFrameException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Moves byte payloads of [THRESHOLD_BYTES] or more into read-only shared memory, sent via [publish]
 * ahead of the frame. Binder transactions share a ~1MB per-process buffer (half of it for one-way
 * calls), so large inline payloads fail with TransactionTooLargeException.
 */
class SharedBytes(
    private val publish: (transferId: Long, region: SharedMemory) -> Unit,
) : BytesChannel {
    private val received = ConcurrentHashMap<Long, SharedMemory>()
    private val transferIds = AtomicLong()

    @Volatile private var closed = false

    override fun offload(bytes: ByteArray): Long? = if (bytes.size < THRESHOLD_BYTES) null else share(bytes)

    override fun offloadFrame(frame: ByteArray): Long? = share(frame)

    private fun share(bytes: ByteArray): Long? {
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
        } catch (e: TransportClosedException) {
            throw e
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
            // Only what the frame claims: the other side chose the region's size.
            val buffer = region.map(OsConstants.PROT_READ, 0, length)
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
        if (closed) {
            runCatching { region.close() }
            return
        }
        // Regions whose frame was dropped are never claimed. IDs increase, so evict the oldest.
        while (received.size >= MAX_OUTSTANDING) {
            val oldest = received.keys.minOrNull() ?: break
            Log.w(TAG, "Discarding shared region $oldest: $MAX_OUTSTANDING wait for their frames")
            received.remove(oldest)?.let { runCatching { it.close() } }
        }
        received.put(transferId, region)?.let { runCatching { it.close() } }
        // clear() may have run in between and missed it.
        if (closed) received.remove(transferId)?.let { runCatching { it.close() } }
    }

    /** Closes what waits and every region that arrives later. */
    fun clear() {
        closed = true
        received.values.forEach { runCatching { it.close() } }
        received.clear()
    }

    private companion object {
        const val THRESHOLD_BYTES = 16 * 1024

        /** Several frames' worth of [com.xfl.msgbot.plugin.api.serialization.ValueCodec.MAX_OFFLOADS]. */
        const val MAX_OUTSTANDING = 256
        const val TAG = "SharedBytes"
    }
}
