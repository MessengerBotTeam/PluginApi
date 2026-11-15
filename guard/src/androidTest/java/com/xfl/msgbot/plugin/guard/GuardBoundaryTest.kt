/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.guard

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xfl.msgbot.plugin.api.engine.ClassAccess
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException
import java.lang.reflect.Modifier
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class GuardBoundaryTest {
    private lateinit var top: File
    private lateinit var project: File
    private lateinit var outside: File
    private lateinit var tmp: File
    private lateinit var host: FolderHost
    private lateinit var session: GuardSession
    private var previous: GuardSession? = null

    @Before
    fun setUp() {
        top = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "guard-boundary").apply { deleteRecursively() }
        project = File(top, "project").apply { mkdirs() }.canonicalFile
        tmp = File(top, "tmp").apply { mkdirs() }.canonicalFile
        outside = File(top, "secret.txt").apply { writeText("secret") }.canonicalFile
        host = FolderHost(project, tmp)
        use(ClassAccess.DEFAULT)
    }

    private fun use(access: ClassAccess) {
        session = GuardSession(host, ClassRules(access, listOf("com.example.engine.")))
        val old = GuardContext.swap(session)
        if (!::top.isInitialized || previous == null) previous = old
    }

    @After
    fun tearDown() {
        Descriptors.useHiddenApi = true
        GuardContext.swap(previous)
        top.deleteRecursively()
    }

    @Test
    fun relativePathsAreInTheProjectFolder() {
        GuardFile("note.txt").also { it.createNewFile() }
        assertTrue(File(project, "note.txt").exists())
        assertEquals("${project.path}/note.txt", GuardFile("note.txt").absolutePath)
        assertEquals("${project.path}/note.txt", GuardPaths.get("note.txt").toAbsolutePath().toString())
        GuardFileOutputStream("rel.txt").use { it.write(1) }
        assertEquals(1, File(project, "rel.txt").length())
    }

    @Test
    fun outsideTheRulesIsRefusedAndReportedOnce() {
        val secret = GuardFile(outside.path)
        assertFailsWith<SecurityException> { secret.exists() }
        assertFailsWith<SecurityException> { secret.length() }
        assertFalse(secret.canRead())
        val io = assertFailsWith<FileNotFoundException> { GuardFileInputStream(outside.path) }
        assertTrue(io.message!!.contains("EACCES"), io.message)
        assertFailsWith<AccessDeniedException> { Files.readAllBytes(GuardPaths.get(outside.path)) }
        assertFailsWith<FileNotFoundException> { GuardRandomAccessFile(outside.path, "r") }
        assertFailsWith<AccessDeniedException> { GuardZipFile(outside.path) }
        assertFailsWith<SecurityException> { GuardFile(project, "../secret.txt").exists() }
        assertEquals(1, host.logs.count { it.contains(outside.path) }, host.logs.toString())
    }

    @Test
    fun realFilesAndPathsBecomeGuarded() {
        val file = GuardPolicy.replace(File(outside.path))
        assertIs<GuardFile>(file)
        assertFailsWith<SecurityException> { file.exists() }
        val path = GuardPolicy.replace(Paths.get(outside.path))
        assertIs<GuardPath>(path)
        assertFailsWith<AccessDeniedException> { Files.size(path as Path) }
    }

    @Test
    fun temporaryFilesGoToTheHostsFolder() {
        val a = GuardFileBase.createTempFile("abc", ".t")
        assertEquals(GuardFileBase::class.java, GuardFile::class.java.getMethod("createTempFile", String::class.java, String::class.java).declaringClass)
        assertEquals(tmp.path, a.parent)
        val b = GuardFiles.createTempFile("abc", ".t")
        assertEquals(tmp.path, b.parent.toString())
        val c = GuardFiles.createTempDirectory("dir")
        assertTrue(File(c.toString()).isDirectory)
        assertTrue(File(tmp, a.name).exists())
    }

    @Test
    fun deleteOnExitRunsWhenTheSessionCloses() {
        val file = GuardFile("bye.txt").apply { createNewFile() }
        file.deleteOnExit()
        assertTrue(File(project, "bye.txt").exists())
        session.close()
        assertFalse(File(project, "bye.txt").exists())
    }

    @Test
    fun withoutASessionNothingOpens() {
        val saved = GuardContext.swap(null)
        try {
            assertFailsWith<SecurityException> { GuardFile("x") }
            assertFailsWith<SecurityException> { GuardPaths.get("x") }
        } finally {
            GuardContext.swap(saved)
        }
    }

    @Test
    fun theFallbackWithoutTheHiddenApiFindsTheSameDescriptor() {
        Descriptors.useHiddenApi = false
        GuardFileOutputStream("p.bin").use { it.write(byteArrayOf(1, 2, 3)) }
        GuardRandomAccessFile("p.bin", "rw").use {
            it.seek(3)
            it.write(4)
            assertEquals(4, it.length())
        }
        GuardRandomAccessFile("p.bin", "r").use { assertEquals(4, it.channel.size()) }
        assertEquals(listOf<Byte>(1, 2, 3, 4), File(project, "p.bin").readBytes().toList())
    }

    @Test
    fun theEnvironmentNamesTheSameFoldersAsGuardFiles() {
        @Suppress("DEPRECATION")
        val real = android.os.Environment.getExternalStorageDirectory()
        val guarded = GuardEnvironment.getExternalStorageDirectory()
        assertIs<GuardFile>(guarded)
        assertEquals(real.path, guarded.path)
        assertEquals(android.os.Environment.DIRECTORY_DOWNLOADS, GuardEnvironment.DIRECTORY_DOWNLOADS)
    }

    @Test
    fun guardClassesDescribeTheClassAskedFor() {
        val file = GuardClass.forName("java.io.File")
        assertEquals("java.io.File", file.getName())
        assertTrue(file.isInstance(File("x")))
        assertEquals("java.io.File", GuardClass.of(GuardFile::class.java).getName())
        val made = file.getConstructor(GuardClass.of(String::class.java)).newInstance("made.txt")
        assertIs<GuardFile>(made)
        val temp = file.getMethod("createTempFile", GuardClass.of(String::class.java), GuardClass.of(String::class.java)).invoke(null, "abc", null)
        assertEquals(tmp.path, (temp as File).parent)
        assertEquals("java.nio.file.Paths", GuardClass.forName("java.nio.file.Paths").getName())
        assertFailsWith<ClassNotFoundException> { GuardClass.forName("java.lang.reflect.Proxy") }
        assertFailsWith<ClassNotFoundException> { GuardClass.forName("com.example.engine.Runtime") }
        assertFailsWith<ClassNotFoundException> { GuardClass.forName("com.xfl.msgbot.plugin.guard.GuardContext") }
        assertTrue(host.logs.any { it.contains("java.lang.reflect.Proxy") })
    }

    @Test
    fun reflectionOnOrdinaryClassesWorks() {
        val list = GuardClass.forName("java.util.ArrayList").getConstructor().newInstance() as MutableList<Any?>
        GuardClass.forName("java.util.ArrayList").getMethod("add", GuardClass.of(Any::class.java)).invoke(list, "x")
        assertEquals(listOf<Any?>("x"), list)
        val max = GuardClass.forName("java.lang.Integer").getField("MAX_VALUE").get(null)
        assertEquals(Int.MAX_VALUE, max)
        assertTrue(Modifier.isPublic(GuardClass.forName("java.lang.String").getModifiers()))
        assertIs<GuardClass>(GuardPolicy.replace(String::class.java))
    }

    @Test
    fun reflectiveMembersOfRefusedClassesAreRefusedWhenUsed() {
        val forName = GuardClass.forName("java.lang.Thread").getMethod("currentThread")
        assertIs<Thread>(forName.invoke(null))
        val proxy = java.lang.reflect.Proxy::class.java.getMethod("isProxyClass", Class::class.java)
        val standIn = GuardPolicy.replace(proxy) as GuardMethod
        assertFailsWith<SecurityException> { standIn.invoke(null, String::class.java) }
    }

    @Test
    fun widePresetLetsRealReflectionThrough() {
        use(ClassAccess(ClassAccess.Preset.WIDE))
        val method = String::class.java.getMethod("length")
        assertEquals(method, GuardPolicy.replace(method))
        use(ClassAccess.DEFAULT)
        assertIs<GuardMethod>(GuardPolicy.replace(method))
    }

    @Test
    fun withoutRestrictionsClassesAreRealButFilesStayGuarded() {
        use(ClassAccess(restrictions = false))
        assertEquals(String::class.java, GuardPolicy.replace(String::class.java))
        assertNull(GuardPolicy.substituteOf("java.lang.Class"))
        assertEquals(GuardFile::class.java, GuardPolicy.substituteOf("java.io.File"))
        assertIs<GuardFile>(GuardPolicy.replace(File("x")))
        assertEquals(GuardPolicy.Lookup.Ordinary, GuardPolicy.lookup("java.lang.reflect.Proxy"))
        assertEquals(GuardPolicy.Lookup.Denied, GuardPolicy.lookup("com.example.engine.Thing"))
    }

    @Test
    fun customListsAddAndTakeAway() {
        use(ClassAccess(ClassAccess.Preset.CUSTOM, allow = listOf("java.lang.reflect.Proxy"), deny = listOf("java.net.*")))
        assertEquals(GuardPolicy.Lookup.Ordinary, GuardPolicy.lookup("java.lang.reflect.Proxy"))
        assertEquals(GuardPolicy.Lookup.Denied, GuardPolicy.lookup("java.net.Socket"))
        assertEquals(GuardPolicy.Lookup.Denied, GuardPolicy.lookup("java.lang.invoke.MethodHandles"))
    }

    @Test
    fun processesNamingPathsOutsideAreRefused() {
        assertFailsWith<SecurityException> { GuardRuntime.getRuntime().exec(arrayOf("ls", outside.path)) }
        assertFailsWith<SecurityException> { GuardRuntime.getRuntime().exec("sh -c id") }
        GuardRuntime.getRuntime().exec(arrayOf("ls", project.path)).waitFor()
    }

    @Test
    fun stockZipFileAndGuardAgreeOnAnArchiveInTheProject() {
        val zip = File(project, "a.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use {
            it.putNextEntry(java.util.zip.ZipEntry("x"))
            it.write("y".toByteArray())
        }
        val names = ZipFile(zip).use { z -> z.entries().toList().map { it.name } }
        assertEquals(names, GuardZipFile("a.zip").use { z -> z.entries().toList().map { it.name } })
    }
}
