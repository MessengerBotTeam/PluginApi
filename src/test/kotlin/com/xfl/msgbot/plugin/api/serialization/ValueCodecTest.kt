/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.serialization

import com.xfl.msgbot.plugin.api.value.Blob
import com.xfl.msgbot.plugin.api.value.Value
import com.xfl.msgbot.plugin.api.value.vArray
import com.xfl.msgbot.plugin.api.value.vObject
import com.xfl.msgbot.plugin.api.value.valueOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ValueCodecTest {
    private fun roundTrip(value: Value): Value = ValueCodec.decode(ValueCodec.encode(value))

    @Test
    fun primitivesRoundTrip() {
        assertEquals(Value.VNull, roundTrip(Value.VNull))
        assertEquals(true, (roundTrip(valueOf(true)) as Value.VBool).value)
        assertEquals(42L, (roundTrip(valueOf(42L)) as Value.VInt).value)
        assertEquals(3.14, (roundTrip(valueOf(3.14)) as Value.VDouble).value, 0.0)
        assertEquals("héllo", (roundTrip(valueOf("héllo")) as Value.VString).value)
        assertEquals(7L, (roundTrip(Value.VHandle(7L)) as Value.VHandle).id)
    }

    @Test
    fun nestedStructureRoundTrips() {
        val value =
            vObject(
                "name" to valueOf("bot"),
                "count" to valueOf(3),
                "tags" to vArray(valueOf("a"), valueOf("b")),
                "nested" to vObject("x" to valueOf(true)),
            )
        val decoded = roundTrip(value)
        assertIs<Value.VObject>(decoded)
        assertEquals("bot", (decoded.entries.getValue("name") as Value.VString).value)
        assertEquals(3L, (decoded.entries.getValue("count") as Value.VInt).value)
        assertEquals(2, (decoded.entries.getValue("tags") as Value.VArray).items.size)
        assertTrue(((decoded.entries.getValue("nested") as Value.VObject).entries.getValue("x") as Value.VBool).value)
    }

    @Test
    fun bytesAndInlineBlobRoundTrip() {
        val raw = byteArrayOf(1, 2, 3, 0, -7, 127)
        assertContentEquals(raw, (roundTrip(valueOf(raw)) as Value.VBytes).value)

        val blob = Blob.ofInline(9L, "image-bytes".toByteArray(), "image/png")
        val decoded = roundTrip(Value.VBlob(blob)).let { assertIs<Value.VBlob>(it); it.blob }
        assertEquals(9L, decoded.id)
        assertEquals("image/png", decoded.mime)
        val transport = decoded.transport
        assertIs<Blob.Transport.Inline>(transport)
        assertContentEquals("image-bytes".toByteArray(), transport.bytes)
    }

    @Test
    fun nonInlineBlobDegradesToRef() {
        val blob = Blob(5L, 100L, "application/octet-stream", Blob.Transport.Shm(3L, 0L, 100L))
        val decoded = (roundTrip(Value.VBlob(blob)) as Value.VBlob).blob
        assertEquals(5L, decoded.id)
        assertIs<Blob.Transport.FileRef>(decoded.transport)
    }
}
