package com.xfl.msgbot.plugin.ipc

import android.os.Parcel
import android.os.SharedMemory
import com.xfl.msgbot.plugin.api.serialization.ValueCodec
import com.xfl.msgbot.plugin.api.value.Blob
import com.xfl.msgbot.plugin.api.value.Value
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SharedBlobsTest {
    @Test fun repeatedBlobHasSeparateTransfersButKeepsItsLogicalIdentity() {
        val transferIds = mutableListOf<Long>()
        val receiver = SharedBlobs { _, _ -> error("receiver cannot publish") }
        val sender = SharedBlobs { id, shm ->
            transferIds += id
            val parcel = Parcel.obtain()
            try {
                shm.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                receiver.receive(id, SharedMemory.CREATOR.createFromParcel(parcel))
            } finally { parcel.recycle() }
        }
        val bytes = ByteArray(70 * 1024) { (it % 251).toByte() }
        val blob = Value.VBlob(Blob.ofInline(42, bytes))
        try {
            val frame = ValueCodec.encode(Value.VArray(listOf(blob, Value.VObject(mapOf("again" to blob)))), sender.outbound())
            val decoded = ValueCodec.decode(frame, receiver.inbound()) as Value.VArray
            val copies = listOf(decoded.items[0], (decoded.items[1] as Value.VObject).entries.getValue("again"))
            assertEquals(2, transferIds.distinct().size)
            copies.forEach {
                val result = (it as Value.VBlob).blob
                assertEquals(42L, result.id)
                assertArrayEquals(bytes, (result.transport as Blob.Transport.Inline).bytes)
            }
        } finally {
            sender.clear()
            receiver.clear()
        }
    }
}
