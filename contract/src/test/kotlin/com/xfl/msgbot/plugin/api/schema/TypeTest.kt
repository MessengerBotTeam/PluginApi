package com.xfl.msgbot.plugin.api.schema

import com.xfl.msgbot.plugin.api.standard.StandardApi
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

class TypeConstraintTest {
    @Test
    fun `enums, limits and handles read back to the same text`() {
        listOf(
            "enum<ko|en|ja>",
            "enum<a>?",
            "string[..100]",
            "string[1..100]?",
            "int[0..]",
            "int[-5..5]",
            "list<string>[..10]",
            "map<int>[1..]",
            "bytes[..1024]",
            "handle<reply>",
            "{kind: enum<a|b>, name: string[..8], tokens: list<handle<image>>[..3]}",
        ).forEach { text -> assertEquals(text, Type.parse(text).toString()) }
    }

    @Test
    fun `malformed constraints are refused`() {
        listOf(
            "enum<>", "enum<a|a>", "enum<a b>", "enum<a", "bool[..1]", "{a: int}[..1]", "string[..]", "string[5..1]", "string[1.2]",
            "handle<>", "handle<a b>", "any[..1]", "string[..99999999999999999999]",
        ).forEach { text -> assertFailsWith<IllegalArgumentException>(text) { Type.parse(text) } }
    }

    @Test
    fun `an enum takes only its own values`() {
        val type = Type.parse("enum<ko|en>")
        assertNull(type.check(Value.VString("ko")))
        assertTrue(type.check(Value.VString("fr"))!!.contains("expected one of"))
        assertTrue(type.check(Value.VInt(1))!!.contains("expected enum<ko|en>"))
    }

    @Test
    fun `a limit checks length, size or value`() {
        assertNull(Type.parse("string[..3]").check(Value.VString("abc")))
        assertEquals("value: length 4 is above the maximum 3", Type.parse("string[..3]").check(Value.VString("abcd")))
        assertEquals("value: length 0 is below the minimum 1", Type.parse("list<int>[1..]").check(vArray()))
        assertEquals("value: size 2 is above the maximum 1", Type.parse("map<int>[..1]").check(vObject("a" to 1, "b" to 2)))
        assertEquals("value: size 3 is above the maximum 2", Type.parse("bytes[..2]").check(Value.VBytes(byteArrayOf(1, 2, 3))))
        assertNull(Type.parse("int[0..100]").check(Value.VInt(100)))
        assertEquals("value: value 101 is above the maximum 100", Type.parse("int[0..100]").check(Value.VInt(101)))
        assertTrue(Type.parse("double[0..1]").check(Value.VDouble(1.5))!!.contains("above the maximum"))
        assertTrue(Type.parse("string[..3]").check(Value.VInt(1))!!.contains("expected string"))
        assertNull(Type.parse("string[..3]?").check(Value.VNull))
    }

    @Test
    fun `a limit applies to the items of a list too`() {
        val type = Type.parse("list<string[..2]>[..2]")
        assertNull(type.check(vArray("a", "bc")))
        assertEquals("value[1]: length 3 is above the maximum 2", type.check(vArray("a", "bcd")))
        assertTrue(type.check(vArray("a", "b", "c"))!!.contains("above the maximum 2"))
    }

    @Test
    fun `a handle is a string that names something the host keeps`() {
        val type = Type.parse("handle<reply>")
        assertNull(type.check(Value.VString("provider-1")))
        assertTrue(type.check(Value.VInt(1))!!.contains("expected handle<reply>"))
    }

    @Test
    fun `conform still drops unknown struct fields under a limit`() {
        val type = Type.parse("list<{a: int}>[..5]")
        assertEquals(vArray(vObject("a" to 1)), type.conform(vArray(vObject("a" to 1, "b" to 2))))
    }

    @Test
    fun `a plain string fits a handle in the standard, other types do not`() {
        assertTrue(Type.STRING.fits(Type.handle("reply")))
        assertTrue(Type.STRING.nullable().fits(Type.handle("reply").nullable()))
        assertTrue(Type.handle("reply").fits(Type.handle("reply")))
        assertTrue(!Type.INT.fits(Type.handle("reply")))
        assertTrue(!Type.handle("read").fits(Type.handle("reply")))
    }

    @Test
    fun `the standard bot module marks its tokens as handles`() {
        val message = StandardApi.Bot.event("message")!!.fields.associateBy { it.name }
        assertEquals(Type.handle("reply").nullable(), message.getValue("replyToken").type)
        assertEquals(Type.handle("read").nullable(), message.getValue("readToken").type)
        assertEquals(Type.handle("image").nullable(), message.getValue("image").type)
        assertEquals(Type.handle("reply").nullable(), StandardApi.Bot.function("reply")!!.params.first { it.name == "token" }.type)
    }
}
