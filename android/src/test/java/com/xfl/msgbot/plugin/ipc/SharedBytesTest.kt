package com.xfl.msgbot.plugin.ipc

import android.os.Parcel
import android.os.SharedMemory
import com.xfl.msgbot.plugin.api.serialization.ValueCodec
import com.xfl.msgbot.plugin.api.value.Value
import com.xfl.msgbot.plugin.api.value.vArray
import com.xfl.msgbot.plugin.api.value.vObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SharedBytesTest {
    @Test
    fun largeBytesTravelThroughSharedMemoryAndSmallOnesStayInline() {
        val transfers = mutableListOf<Long>()
        val receiver = SharedBytes { _, _ -> error("the receiver does not publish") }
        val sender =
            SharedBytes { id, region ->
                transfers += id
                val parcel = Parcel.obtain()
                try {
                    region.writeToParcel(parcel, 0)
                    parcel.setDataPosition(0)
                    receiver.receive(id, SharedMemory.CREATOR.createFromParcel(parcel))
                } finally {
                    parcel.recycle()
                }
            }
        val large = ByteArray(70 * 1024) { (it % 251).toByte() }
        val value = vArray(large, vObject("again" to large, "small" to byteArrayOf(1, 2)))
        try {
            val decoded = ValueCodec.decode(ValueCodec.encode(value, sender), receiver)
            assertEquals(value, decoded)
            assertEquals(2, transfers.distinct().size)
        } finally {
            sender.clear()
            receiver.clear()
        }
    }
}
