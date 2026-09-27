/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.ipc

import android.os.SharedMemory
import android.system.OsConstants
import com.xfl.msgbot.plugin.api.serialization.ValueCodec
import com.xfl.msgbot.plugin.api.value.Blob
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Moves large blob payloads out of Binder frames and into shared memory.
 *
 * A frame is a byte array, so an inlined image competes for the ~1MB Binder buffer shared by the
 * whole process and eventually throws TransactionTooLarge. Above [THRESHOLD_BYTES] the bytes are
 * published as a read-only region, handed over by [publish] ahead of the frame, and the frame
 * carries only a descriptor. Small payloads stay inline, where a round trip through mmap would
 * cost more than it saves.
 *
 * Both endpoints use this the same way; only the direction of [publish] differs.
 */
class SharedBlobs(private val publish: (id: Long, shm: SharedMemory) -> Unit) {
    private val received = ConcurrentHashMap<Long, SharedMemory>()
    private val transferIds = AtomicLong()

    /** Inline -> Shm for anything worth the mmap, publishing the region first. */
    fun outbound(): ValueCodec.BlobHook =
        ValueCodec.BlobHook { blob ->
            val t = blob.transport
            if (t !is Blob.Transport.Inline || t.bytes.size < THRESHOLD_BYTES) {
                blob
            } else {
                publishRegion(blob, t.bytes) ?: blob
            }
        }

    /** Shm -> Inline, consuming the region published for this id. */
    fun inbound(): ValueCodec.BlobHook =
        ValueCodec.BlobHook { blob ->
            val t = blob.transport
            if (t !is Blob.Transport.Shm) blob else readRegion(blob, t) ?: blob
        }

    /** Called by the transport when the peer hands a region over. */
    fun receive(id: Long, shm: SharedMemory) {
        received.put(id, shm)?.close()
    }

    fun clear() {
        received.values.forEach { runCatching { it.close() } }
        received.clear()
    }

    private fun publishRegion(blob: Blob, bytes: ByteArray): Blob? {
        var shm: SharedMemory? = null
        return try {
            val region = SharedMemory.create("blob-${blob.id}", bytes.size)
            shm = region
            val buffer = region.mapReadWrite()
            try {
                buffer.put(bytes)
            } finally {
                SharedMemory.unmap(buffer)
            }
            // Sealed before handing over: the peer only ever needs to read it.
            region.setProtect(OsConstants.PROT_READ)
            val transferId = transferIds.incrementAndGet()
            publish(transferId, region)
            Blob(blob.id, blob.size, blob.mime, Blob.Transport.Shm(transferId, 0L, bytes.size.toLong()))
        } catch (e: Exception) {
            // Falling back to inline is still correct, just size-limited.
            Log.w(TAG, "Shared memory unavailable for blob ${blob.id}, keeping it inline", e)
            null
        } finally {
            // Binder dup'd the fd during the call, so our copy is done either way.
            runCatching { shm?.close() }
        }
    }

    private fun readRegion(blob: Blob, t: Blob.Transport.Shm): Blob? {
        val shm = received.remove(t.id) ?: run {
            Log.w(TAG, "No shared region received for blob ${t.id}")
            return null
        }
        return try {
            val buffer = shm.mapReadOnly()
            try {
                val bytes = ByteArray(t.length.toInt())
                buffer.position(t.offset.toInt())
                buffer.get(bytes)
                Blob.ofInline(blob.id, bytes, blob.mime)
            } finally {
                SharedMemory.unmap(buffer)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read shared region for blob ${t.id}", e)
            null
        } finally {
            runCatching { shm.close() }
        }
    }

    private companion object {
        /** Below this, inlining is cheaper than creating and mapping a region. */
        const val THRESHOLD_BYTES = 64 * 1024
        const val TAG = "SharedBlobs"
    }
}
