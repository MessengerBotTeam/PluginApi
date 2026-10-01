package com.xfl.msgbot.plugin.api.schema

import com.xfl.msgbot.plugin.api.value.Value
import com.xfl.msgbot.plugin.api.value.vArray
import com.xfl.msgbot.plugin.api.value.vObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TypeTest {
    @Test
    fun `the text form reads back to the same type`() {
        listOf(
            "string",
            "int?",
            "list<string>",
            "map<list<int?>>",
            "{status: int, headers: map<string>, body: string?}",
            "{author: {name: string, avatar: string?}}?",
            "{}",
            "bytes?",
        ).forEach { text -> assertEquals(text, Type.parse(text).toString()) }
    }

    @Test
    fun `malformed type text names where it went wrong`() {
        listOf("strin", "list<string", "{a int}", "void?", "string??", "map<>").forEach { text ->
            assertFailsWith<IllegalArgumentException>(text) { Type.parse(text) }
        }
    }

    @Test
    fun `an absent value is only fine for an optional type`() {
        assertNull(Type.STRING.nullable().check(Value.VNull))
        assertTrue(Type.STRING.check(Value.VNull)!!.contains("missing"))
    }

    @Test
    fun `a whole number passes as a double but not the other way round`() {
        assertNull(Type.DOUBLE.check(Value.VInt(3)))
        assertNotNull(Type.INT.check(Value.VDouble(3.5)))
    }

    @Test
    fun `a struct names the field that is wrong, missing or unknown`() {
        val type = Type.parse("{name: string, author: {hash: string}, tags: list<string>?}")
        assertNull(type.check(vObject("name" to "a", "author" to vObject("hash" to "h"))))
        assertEquals(
            "value.author.hash: expected string, got int",
            type.check(vObject("name" to "a", "author" to vObject("hash" to 1))),
        )
        assertEquals("value.name: required (string), but missing", type.check(vObject("author" to vObject("hash" to "h"))))
        assertTrue(type.check(vObject("name" to "a", "author" to vObject("hash" to "h"), "nmae" to "x"))!!.startsWith("value.nmae: unknown field"))
        assertEquals("value.tags[1]: expected string, got int", type.check(vObject("name" to "a", "author" to vObject("hash" to "h"), "tags" to vArray("x", 2))))
    }

    @Test
    fun `a type from another app is bounded in length and nesting`() {
        assertEquals(Type.list(Type.list(Type.INT)), Type.parse("list<list<int>>"))
        assertFailsWith<IllegalArgumentException> { Type.parse("list<".repeat(50_000) + "int" + ">".repeat(50_000)) }
        assertFailsWith<IllegalArgumentException> { Type.parse("list<".repeat(40) + "int" + ">".repeat(40)) }
    }

    @Test
    fun `checking a large value does not build a path for every item`() {
        val key = "k".repeat(1 shl 20)
        val value = vObject(key to Value.VArray(List(200_000) { Value.VNull }))
        val started = System.nanoTime()
        assertNull(Type.map(Type.list(Type.ANY)).check(value))
        assertTrue(System.nanoTime() - started < 2_000_000_000L, "took ${(System.nanoTime() - started) / 1_000_000}ms")
        // Errors still name where, with a long key shortened.
        val error = Type.map(Type.list(Type.INT)).check(vObject(key to vArray(1, "two")))!!
        assertTrue(error.startsWith("value.${"k".repeat(64)}…[1]: expected int"), error)
    }
}
