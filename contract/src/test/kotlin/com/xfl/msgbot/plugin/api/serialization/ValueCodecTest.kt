package com.xfl.msgbot.plugin.api.serialization

import com.xfl.msgbot.plugin.api.value.Value
import com.xfl.msgbot.plugin.api.value.vArray
import com.xfl.msgbot.plugin.api.value.vObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ValueCodecTest {
    @Test
    fun `every kind of value survives a round trip`() {
        val value =
            vObject(
                "null" to null,
                "bool" to true,
                "int" to Long.MAX_VALUE,
                "double" to 1.5,
                "string" to "한글 ✓",
                "bytes" to byteArrayOf(1, 2, 3),
                "list" to vArray(1, "two", vArray()),
                "map" to vObject("nested" to vObject()),
            )
        assertEquals(value, ValueCodec.decode(ValueCodec.encode(value)))
    }

    @Test
    fun `large bytes can travel out of band`() {
        val stash = mutableMapOf<Long, ByteArray>()
        val channel =
            object : BytesChannel {
                override fun offload(bytes: ByteArray): Long? = if (bytes.size < 4) null else (stash.size + 1L).also { stash[it] = bytes }

                override fun resolve(
                    transferId: Long,
                    length: Int,
                ): ByteArray = stash.remove(transferId)!!.also { assertEquals(length, it.size) }
            }
        val value = vArray(ByteArray(10) { it.toByte() }, byteArrayOf(1))
        val frame = ValueCodec.encode(value, channel)
        assertEquals(1, stash.size)
        assertEquals(value, ValueCodec.decode(frame, channel))
    }

    @Test
    fun `a length longer than the frame is refused before anything is allocated`() {
        val frame = bytes { writeByte(5); writeInt(Int.MAX_VALUE) }
        assertFailsWith<MalformedFrameException> { ValueCodec.decode(frame) }
        val list = bytes { writeByte(7); writeInt(1_000_000_000) }
        assertFailsWith<MalformedFrameException> { ValueCodec.decode(list) }
    }

    @Test
    fun `nesting past the limit is refused instead of overflowing the stack`() {
        val depth = ValueCodec.MAX_DEPTH + 5
        val frame = bytes { repeat(depth) { writeByte(7); writeInt(1) }; writeByte(0) }
        assertFailsWith<MalformedFrameException> { ValueCodec.decode(frame) }
    }

    @Test
    fun `truncated and padded frames are refused`() {
        val good = ValueCodec.encode(Value.VString("hello"))
        assertFailsWith<MalformedFrameException> { ValueCodec.decode(good.copyOf(good.size - 1)) }
        assertFailsWith<MalformedFrameException> { ValueCodec.decode(good + 0) }
        assertFailsWith<MalformedFrameException> { ValueCodec.decode(byteArrayOf(42)) }
    }

    private fun bytes(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).use(block) }.toByteArray()

    @Test
    fun `a frame of many tiny values is refused before it is allocated`() {
        val count = ValueCodec.MAX_VALUES + 1
        val frame =
            java.nio.ByteBuffer
                .allocate(5 + count)
                .put(7)
                .putInt(count)
                .array()
        val e = assertFailsWith<MalformedFrameException> { ValueCodec.decode(frame) }
        assertTrue(e.message!!.contains("values"), e.message)
        val fits = vArray(*Array(1000) { null })
        assertEquals(fits, ValueCodec.decode(ValueCodec.encode(fits)))
    }

    @Test
    fun `out-of-band payloads share one budget per frame`() {
        val channel =
            object : BytesChannel {
                override fun offload(bytes: ByteArray): Long? = null

                override fun resolve(
                    transferId: Long,
                    length: Int,
                ): ByteArray = ByteArray(0)
            }
        val half = ValueCodec.MAX_SHARED_BYTES / 2 + 1
        val frame =
            java.nio.ByteBuffer
                .allocate(5 + 2 * 13)
                .put(7)
                .putInt(2)
                .apply { repeat(2) { put(9).putLong(it.toLong()).putInt(half) } }
                .array()
        assertFailsWith<MalformedFrameException> { ValueCodec.decode(frame, channel) }
    }

    @Test
    fun `nested collections cannot each claim the whole value budget up front`() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            repeat(70) {
                data.writeByte(7)
                data.writeInt(ValueCodec.MAX_VALUES - 100)
            }
            data.write(ByteArray(ValueCodec.MAX_VALUES))
        }
        val before = allocatedBytes()
        assertFailsWith<MalformedFrameException> { ValueCodec.decode(out.toByteArray()) }
        assertTrue(allocatedBytes() - before < 64L * 1024 * 1024, "allocated ${(allocatedBytes() - before) shr 20}MB")
    }

    @Test
    fun `the encoder refuses what the decoder would, before anything is sent`() {
        assertFailsWith<IllegalArgumentException> { ValueCodec.encode(Value.VArray(List(ValueCodec.MAX_VALUES) { Value.VNull })) }
        val offloaded = mutableListOf<Int>()
        val channel =
            object : BytesChannel {
                override fun offload(bytes: ByteArray): Long = offloaded.size.toLong().also { offloaded += bytes.size }

                override fun resolve(
                    transferId: Long,
                    length: Int,
                ): ByteArray = error("not read")
            }
        val big = ByteArray(40 shl 20)
        // The first goes out of band; the others no longer fit the frame's shared budget, and inline they make the frame too big.
        assertFailsWith<IllegalArgumentException> { ValueCodec.encode(vArray(big, big, big), channel) }
        assertEquals(1, offloaded.size)
    }

    @Test
    fun `a frame sends at most a few payloads out of band and keeps the rest inline`() {
        val store = mutableMapOf<Long, ByteArray>()
        val channel =
            object : BytesChannel {
                override fun offload(bytes: ByteArray): Long = (store.size + 1L).also { store[it] = bytes }

                override fun resolve(
                    transferId: Long,
                    length: Int,
                ): ByteArray = store.remove(transferId)!!
            }
        val value = Value.VArray(List(ValueCodec.MAX_OFFLOADS + 50) { Value.VBytes(ByteArray(20 * 1024) { i -> i.toByte() }) })
        val frame = ValueCodec.encode(value, channel)
        assertEquals(ValueCodec.MAX_OFFLOADS, store.size)
        assertEquals(value, ValueCodec.decode(frame, channel))
    }

    @Test
    fun `a lone surrogate arrives as the replacement character`() {
        val decoded = ValueCodec.decode(ValueCodec.encode(vObject("a\uD83D" to "b\uDE00c 😀")))
        assertEquals(vObject("a\uFFFD" to "b\uFFFDc 😀"), decoded)
    }

    private fun allocatedBytes(): Long =
        (java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean).currentThreadAllocatedBytes
}
