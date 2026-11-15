/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.tck

import com.xfl.msgbot.plugin.api.engine.ClassAccess
import com.xfl.msgbot.plugin.api.engine.ClassAccess.Preset
import com.xfl.msgbot.plugin.api.engine.EngineContext
import com.xfl.msgbot.plugin.api.engine.LoadRequest
import com.xfl.msgbot.plugin.api.engine.ProfileScript
import com.xfl.msgbot.plugin.api.engine.ScriptEngine
import com.xfl.msgbot.plugin.api.standard.StandardApi
import com.xfl.msgbot.plugin.api.value.Value
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Conformance tests for a JavaScript engine that lets scripts use Java (`<engine java="true">`): files only through the
 * host under the project's rules, the project's [ClassAccess], and ordinary Java working as before. It runs on a device,
 * because the guard opens the host's descriptors. Subclass it in an engine's `androidTest` sources.
 */
abstract class JavaGuardConformance {
    /** Called on the engine thread. */
    protected abstract fun createEngine(context: EngineContext): ScriptEngine

    /** A JavaScript expression for the Java class [name] in this engine, such as `Java.type('java.io.File')`. */
    protected abstract fun javaClass(name: String): String

    /** A folder of this test process the suite may fill and delete. */
    protected abstract fun scratchFolder(): File

    /** A descriptor of this process for [file], opened as the [com.xfl.msgbot.plugin.api.engine.HostFileMode] [mode] asks. */
    protected abstract fun openDescriptor(
        file: File,
        mode: Int,
    ): Int

    /**
     * Whether Java objects the script receives are replaced on the way, so `file.getClass()` describes the class and
     * cannot reach the real one. An engine that cannot rewrite results (GraalJS) makes those objects unusable instead.
     */
    protected open val replacesResults: Boolean = true

    protected lateinit var harness: EngineHarness
    protected lateinit var files: FolderFiles
    protected lateinit var outside: File
    private lateinit var top: File

    private val helpers =
        """
        function t(f) { try { var v = f(); return 'ok:' + v; } catch (e) { return 'threw:' + e; } }
        function has(f) { try { var c = f(); return c === undefined || c === null ? 'none' : 'some'; } catch (e) { return 'none'; } }
        """.trimIndent()

    @Before
    fun openHarness() {
        top = File(scratchFolder(), "java-guard-tck").apply { deleteRecursively() }
        val project = File(top, "project").apply { mkdirs() }
        val tmp = File(top, "tmp").apply { mkdirs() }
        outside = File(top, "outside.txt").apply { writeText("secret") }.canonicalFile
        harness = EngineHarness(::createEngine)
        files = FolderFiles(project, tmp, ::openDescriptor).also { it.install(harness) }
    }

    @After
    fun closeHarness() {
        harness.close()
        top.deleteRecursively()
    }

    protected fun load(
        access: ClassAccess = ClassAccess.DEFAULT,
        entry: String = "",
    ) = harness.load(
        LoadRequest("javascript", listOf(StandardApi.Log, StandardApi.Fs), ProfileScript("profile.js", helpers), "main.js", mapOf("main.js" to entry), classAccess = access),
    )

    private fun j(name: String) = javaClass(name)

    private val raw = ArrayList<String>()

    // A JavaScript error is compared as "threw"; its text goes into the failure message.
    private fun text(source: String): String {
        val answer = (harness.eval(source) as? Value.VString)?.value ?: error("Expected a string from: $source")
        raw += answer
        return if (answer.startsWith("threw:")) "threw" else answer
    }

    private fun assertEquals(
        expected: Any?,
        actual: Any?,
    ) = kotlin.test.assertEquals(expected, actual, raw.lastOrNull())

    private fun warnings(): List<String> = harness.calls.filter { it.function == "log.write" }.map { (it.args["message"] as Value.VString).value }

    @Test
    fun filesInTheProjectWork() {
        load()
        val file = j("java.io.File")
        assertEquals(
            "ok:true",
            text("t(function () { var d = new ($file)('data/sub'); return d.mkdirs(); })"),
        )
        text("t(function () { var w = new (${j("java.io.FileWriter")})('data/sub/a.txt'); w.write('hello'); w.close(); return 1; })")
        assertEquals("hello", File(files.root, "data/sub/a.txt").readText())
        assertEquals("ok:5", text("t(function () { return new ($file)('data/sub/a.txt').length(); })"))
        assertEquals("ok:a.txt", text("t(function () { return new ($file)('data/sub').list()[0]; })"))
        assertEquals(
            "ok:hello",
            text("t(function () { var r = new (${j("java.io.BufferedReader")})(new (${j("java.io.FileReader")})('data/sub/a.txt')); var s = r.readLine(); r.close(); return s; })"),
        )
        assertEquals("ok:${files.root.path}/x", text("t(function () { return new ($file)('x').getAbsolutePath(); })"))
    }

    @Test
    fun randomAccessAndChannelsWork() {
        load()
        assertEquals(
            "ok:8 4",
            text(
                """
                t(function () {
                    var f = new (${j("java.io.RandomAccessFile")})('r.bin', 'rw');
                    f.writeLong(42); f.setLength(8); f.seek(4);
                    var answer = f.getChannel().size() + ' ' + f.getFilePointer();
                    f.close();
                    return answer;
                })
                """.trimIndent(),
            ),
        )
        assertEquals(8L, File(files.root, "r.bin").length())
    }

    @Test
    fun nioWorks() {
        load()
        val paths = j("java.nio.file.Paths")
        val nioFiles = j("java.nio.file.Files")
        assertEquals(
            "ok:3 true",
            text(
                """
                t(function () {
                    var p = $paths.get('n/lines.txt');
                    $nioFiles.createDirectories(p.getParent());
                    $nioFiles.writeString(p, 'a\nb');
                    return $nioFiles.size(p) + ' ' + $nioFiles.exists(p);
                })
                """.trimIndent(),
            ),
        )
        assertEquals("a\nb", File(files.root, "n/lines.txt").readText())
    }

    @Test
    fun classesThatOpenAPathGoThroughTheHost() {
        load()
        text("t(function () { var f = new (${j("java.util.Formatter")})('fmt.txt'); f.out().append('hi'); f.close(); return 1; })")
        assertEquals("hi", File(files.root, "fmt.txt").readText())
        assertEquals("ok:true", text("t(function () { return ${j("android.graphics.BitmapFactory")}.decodeFile('${outside.path}') === null; })"))
        assertEquals(1, warnings().count { it.contains(outside.path) }, warnings().toString())
    }

    /** Runs where the engine app has no All files access, as on a fresh install. */
    @Test
    fun sqliteOnStorageNeedsAllFilesAccess() {
        load()
        val sqlite = j("android.database.sqlite.SQLiteDatabase")
        assertEquals("threw", text("t(function () { return $sqlite.openOrCreateDatabase('a.db', null).isOpen(); })"))
        assertFalse(File(files.root, "a.db").exists())
        assertEquals("ok:true", text("t(function () { var d = $sqlite.openOrCreateDatabase(':memory:', null); var open = d.isOpen(); d.close(); return open; })"))
        assertEquals(1, warnings().count { it.contains("SQLite") }, warnings().toString())
    }

    @Test
    fun placesOutsideTheRulesAreRefused() {
        load()
        val path = outside.path
        assertEquals("threw", text("t(function () { return new (${j("java.io.File")})('$path').exists(); })"))
        assertEquals("threw", text("t(function () { return new (${j("java.io.FileInputStream")})('$path').read(); })"))
        assertEquals("threw", text("t(function () { return ${j("java.nio.file.Files")}.readAllBytes(${j("java.nio.file.Paths")}.get('$path')).length; })"))
        assertEquals("threw", text("t(function () { return new (${j("java.io.RandomAccessFile")})('$path', 'r').length(); })"))
        assertEquals("threw", text("t(function () { return new (${j("java.io.File")})('../outside.txt').length(); })"))
        assertEquals("secret", outside.readText())
        assertEquals(1, warnings().count { it.contains(path) }, warnings().toString())
    }

    @Test
    fun temporaryFilesGoToTheHostsTemporaryFolder() {
        load()
        val made = text("t(function () { return ${j("java.io.File")}.createTempFile('abc', '.tmp').getParent(); })")
        assertEquals("ok:${files.tmp.path}", made)
    }

    @Test
    fun ordinaryJavaWorksAsBefore() {
        load()
        assertEquals("ok:2", text("t(function () { var n = new (${j("java.util.concurrent.atomic.AtomicInteger")})(1); n.incrementAndGet(); return n.get(); })"))
        assertEquals("ok:36", text("t(function () { return ${j("java.util.UUID")}.randomUUID().toString().length; })"))
        assertEquals("ok:ab", text("t(function () { var b = new (${j("java.lang.StringBuilder")})(); b.append('a'); b.append('b'); return b.toString(); })"))
        assertEquals("ok:3", text("t(function () { return ${j("java.lang.Math")}.max(2, 3); })"))
        assertEquals("ok:true", text("t(function () { return ${j("java.lang.System")}.currentTimeMillis() > 0; })"))
    }

    @Test
    fun theWaysAroundTheGuardAreClosed() {
        load()
        assertEquals("none", text("has(function () { return ${j("java.lang.reflect.Proxy")}; })"))
        assertEquals("none", text("has(function () { return ${j("dalvik.system.DexClassLoader")}; })"))
        assertEquals("none", text("has(function () { return ${j("com.xfl.msgbot.plugin.guard.GuardContext")}; })"))
        // The command as one string: engines differ in how they pass a JavaScript array as String[].
        assertEquals("threw", text("t(function () { return ${j("java.lang.Runtime")}.getRuntime().exec('sh -c id'); })"))
        assertEquals(
            "threw",
            text("t(function () { var r = ${j("java.lang.Class")}.forName('java.lang.Runtime').getMethod('getRuntime').invoke(null); return r.exec('sh -c id').waitFor(); })"),
        )
        assertTrue(warnings().any { it.contains("java.lang.reflect.Proxy") }, warnings().toString())
    }

    @Test
    fun aFileDescribesItselfAsAFile() {
        if (!replacesResults) return
        load()
        assertEquals("ok:java.io.File", text("t(function () { return new (${j("java.io.File")})('x').getClass().getName(); })"))
        assertEquals("threw", text("t(function () { return new (${j("java.io.File")})('x').getClass().getClassLoader().loadClass('java.lang.reflect.Proxy').getName(); })"))
    }

    @Test
    fun javaModulesGiveTheSameGuardedClasses() {
        load()
        text("t(function () { var File = require('java:java.io').File; return new File('from-package.txt').createNewFile(); })")
        text("t(function () { var File = require('java:java.io.File'); return new File('from-class.txt').createNewFile(); })")
        assertTrue(File(files.root, "from-package.txt").exists(), raw.toString())
        assertTrue(File(files.root, "from-class.txt").exists(), raw.toString())
        assertEquals("ok:ab", text("t(function () { var b = new (require('java:java.lang').StringBuilder)('a'); b.append('b'); return b.toString(); })"))
        assertEquals("threw", text("t(function () { return new (require('java:java.io.File'))('${outside.path}').exists(); })"))
        assertEquals("none", text("has(function () { return require('java:java.lang.reflect').Proxy; })"))
    }

    @Test
    fun theOffPresetHasNoJava() {
        load(ClassAccess(Preset.OFF))
        assertEquals("none", text("has(function () { return ${j("java.util.UUID")}; })"))
        assertEquals("none", text("has(function () { return ${j("java.io.File")}; })"))
        assertEquals("threw", text("t(function () { return require('java:java.util').UUID; })"))
        assertTrue(raw.last().contains("java:java.util"), raw.last())
    }

    @Test
    fun theProjectsListsAddAndTakeAway() {
        load(ClassAccess(Preset.CUSTOM, allow = listOf("java.lang.reflect.Proxy"), deny = listOf("java.util.UUID")))
        assertEquals("some", text("has(function () { return ${j("java.lang.reflect.Proxy")}; })"))
        assertEquals("none", text("has(function () { return ${j("java.util.UUID")}; })"))
    }

    @Test
    fun withoutRestrictionsClassesAreOpenButFilesStayGuarded() {
        load(ClassAccess(restrictions = false))
        assertEquals("some", text("has(function () { return ${j("java.lang.reflect.Proxy")}; })"))
        assertEquals("threw", text("t(function () { return new (${j("java.io.File")})('${outside.path}').exists(); })"))
        assertEquals("ok:true", text("t(function () { return new (${j("java.io.File")})('in.txt').createNewFile(); })"))
    }
}
