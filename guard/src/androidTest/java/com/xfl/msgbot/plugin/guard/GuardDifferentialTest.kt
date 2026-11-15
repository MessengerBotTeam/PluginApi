/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.guard

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileReader
import java.io.FileWriter
import java.io.PrintStream
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.Scanner
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals

/**
 * Runs each scenario twice, once on the real classes in a folder of their own and once on the guard's classes in the
 * project folder the host serves, and expects the same transcript: results, exception types and messages.
 */
@RunWith(AndroidJUnit4::class)
class GuardDifferentialTest {
    private interface ZipView {
        val comment: String?
        val names: List<String>

        fun describe(name: String): String?

        fun read(name: String): String?

        fun manifest(): String?

        fun close()
    }

    private interface Io {
        val base: String

        fun file(path: String): File

        fun fis(path: String): FileInputStream

        fun fos(
            path: String,
            append: Boolean = false,
        ): FileOutputStream

        fun reader(
            path: String,
            charset: Charset,
        ): FileReader

        fun writer(
            path: String,
            charset: Charset,
            append: Boolean,
        ): FileWriter

        fun printWriter(file: File): PrintWriter

        fun printStream(path: String): PrintStream

        fun raf(
            path: String,
            mode: String,
        ): RandomAccessFile

        fun path(path: String): Path

        fun scan(file: File): List<String>

        fun zip(path: String): ZipView

        fun jar(path: String): ZipView
    }

    private lateinit var top: File
    private lateinit var real: Io
    private lateinit var guarded: Io
    private lateinit var host: FolderHost
    private var previous: GuardSession? = null

    private fun view(zip: ZipFile) =
        object : ZipView {
            override val comment = zip.comment
            override val names = zip.entries().toList().map { it.name }

            override fun describe(name: String) = zip.getEntry(name)?.let { "${it.name} ${it.size} ${it.compressedSize} ${it.crc} ${it.method} ${it.comment} ${it.isDirectory}" }

            override fun read(name: String) = zip.getEntry(name)?.let { zip.getInputStream(it).readBytes().decodeToString() }

            override fun manifest() = (zip as? JarFile)?.manifest?.mainAttributes?.getValue("Created-By")

            override fun close() = zip.close()
        }

    private fun view(zip: GuardZipFile) =
        object : ZipView {
            override val comment = zip.getComment()
            override val names = zip.entries().toList().map { it.name }

            override fun describe(name: String) = zip.getEntry(name)?.let { "${it.name} ${it.size} ${it.compressedSize} ${it.crc} ${it.method} ${it.comment} ${it.isDirectory}" }

            override fun read(name: String) = zip.getEntry(name)?.let { zip.getInputStream(it)!!.readBytes().decodeToString() }

            override fun manifest() = (zip as? GuardJarFile)?.getManifest()?.mainAttributes?.getValue("Created-By")

            override fun close() = zip.close()
        }

    @Before
    fun setUp() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        top = File(cache, "guard-diff").apply { deleteRecursively() }
        val mirror = File(top, "real").apply { mkdirs() }.canonicalPath
        val project = File(top, "project").apply { mkdirs() }
        val tmp = File(top, "tmp").apply { mkdirs() }
        host = FolderHost(project, tmp)
        previous = GuardContext.swap(GuardSession(host))
        real =
            object : Io {
                override val base = mirror

                override fun file(path: String) = File("$base/$path")

                override fun fis(path: String) = FileInputStream("$base/$path")

                override fun fos(
                    path: String,
                    append: Boolean,
                ) = FileOutputStream("$base/$path", append)

                override fun reader(
                    path: String,
                    charset: Charset,
                ) = FileReader("$base/$path", charset)

                override fun writer(
                    path: String,
                    charset: Charset,
                    append: Boolean,
                ) = FileWriter("$base/$path", charset, append)

                override fun printWriter(file: File) = PrintWriter(file)

                override fun printStream(path: String) = PrintStream("$base/$path")

                override fun raf(
                    path: String,
                    mode: String,
                ) = RandomAccessFile("$base/$path", mode)

                override fun path(path: String): Path = Paths.get(if (path.startsWith("/") || path.isEmpty()) path else "$base/$path")

                override fun scan(file: File) = Scanner(file).use { s -> buildList { while (s.hasNext()) add(if (s.hasNextInt()) "int ${s.nextInt()}" else s.next()) } }

                override fun zip(path: String) = view(ZipFile("$base/$path"))

                override fun jar(path: String) = view(JarFile("$base/$path"))
            }
        guarded =
            object : Io {
                override val base = host.root

                override fun file(path: String): File = GuardFile("$base/$path")

                override fun fis(path: String): FileInputStream = GuardFileInputStream("$base/$path")

                override fun fos(
                    path: String,
                    append: Boolean,
                ): FileOutputStream = GuardFileOutputStream("$base/$path", append)

                override fun reader(
                    path: String,
                    charset: Charset,
                ): FileReader = GuardFileReader("$base/$path", charset)

                override fun writer(
                    path: String,
                    charset: Charset,
                    append: Boolean,
                ): FileWriter = GuardFileWriter("$base/$path", charset, append)

                override fun printWriter(file: File): PrintWriter = GuardPrintWriter(file)

                override fun printStream(path: String): PrintStream = GuardPrintStream("$base/$path")

                override fun raf(
                    path: String,
                    mode: String,
                ): RandomAccessFile = GuardRandomAccessFile("$base/$path", mode)

                override fun path(path: String): Path = GuardPaths.get(if (path.startsWith("/") || path.isEmpty()) path else "$base/$path")

                override fun scan(file: File) = GuardScanner(file).use { s -> buildList { while (s.hasNext()) add(if (s.hasNextInt()) "int ${s.nextInt()}" else s.next()) } }

                override fun zip(path: String) = view(GuardZipFile("$base/$path"))

                override fun jar(path: String) = view(GuardJarFile("$base/$path"))
            }
    }

    @After
    fun tearDown() {
        GuardContext.swap(previous)
        top.deleteRecursively()
    }

    private fun same(scenario: Io.(MutableList<String>) -> Unit) {
        fun run(io: Io): List<String> {
            val out = ArrayList<String>()
            try {
                io.scenario(out)
            } catch (e: Throwable) {
                out += "escaped ${e.javaClass.name}: ${e.message}"
            }
            return out.map { it.replace(io.base, "<base>") }
        }
        assertEquals(run(real), run(guarded))
    }

    private inline fun MutableList<String>.step(
        name: String,
        block: () -> Any?,
    ) {
        this +=
            try {
                "$name = ${show(block())}"
            } catch (e: Throwable) {
                "$name ! ${e.javaClass.name}: ${e.message}"
            }
    }

    private fun show(value: Any?): String =
        when (value) {
            is Array<*> -> value.map { show(it) }.sorted().toString()
            is Collection<*> -> value.map { show(it) }.toString()
            is ByteArray -> value.decodeToString()
            is File -> "File(${value.path})"
            is Path -> "Path($value)"
            else -> value.toString()
        }

    @Test
    fun filesAndFolders() =
        same { out ->
            out.step("mkdirs") { file("a/b/c").mkdirs() }
            out.step("mkdirs again") { file("a/b/c").mkdirs() }
            out.step("isDirectory") { file("a/b").isDirectory }
            out.step("isFile dir") { file("a/b").isFile }
            out.step("create") { file("a/x.txt").createNewFile() }
            out.step("create again") { file("a/x.txt").createNewFile() }
            out.step("create in missing") { file("nope/x.txt").createNewFile() }
            out.step("write") { fos("a/x.txt").use { it.write("hello".toByteArray()) } }
            out.step("length") { file("a/x.txt").length() }
            out.step("length missing") { file("a/none").length() }
            out.step("exists missing") { file("a/none").exists() }
            out.step("list") { file("a").list() }
            out.step("list missing") { file("a/none").list() }
            out.step("list file") { file("a/x.txt").list() }
            out.step("listFiles") { file("a").listFiles() }
            out.step("listFiles filter") { file("a").listFiles { f -> f.isFile } }
            out.step("rename") { file("a/x.txt").renameTo(file("a/y.txt")) }
            out.step("old exists") { file("a/x.txt").exists() }
            out.step("new exists") { file("a/y.txt").exists() }
            out.step("delete full folder") { file("a").delete() }
            out.step("delete file") { file("a/y.txt").delete() }
            out.step("delete missing") { file("a/y.txt").delete() }
            out.step("mkdir no parent") { file("z/q").mkdir() }
            out.step("mkdir") { file("z").mkdir() }
            out.step("canonical") { file("a/b/../b/c/./").canonicalPath }
            out.step("canonical missing") { file("a/b/none/../more").canonicalPath }
            out.step("parent") { file("a/b/c").parentFile }
            out.step("uri dir") { file("a/b").toURI() }
            out.step("uri file") { file("a/q.txt").toURI() }
            out.step("hidden") { file(".hidden").isHidden }
            out.step("equals") { file("a/b") == File(file("a/b").path) }
            out.step("compare") { file("a/b").compareTo(File(file("a/c").path)) }
            out.step("hash") { file("a/b").hashCode() == File(file("a/b").path).hashCode() }
            out.step("touch") { file("t").createNewFile() }
            out.step("setLastModified") { file("t").setLastModified(1_600_000_000_000) }
            out.step("lastModified") { file("t").lastModified() }
            out.step("setReadOnly") { file("t").setReadOnly() }
            out.step("canWrite read-only") { file("t").canWrite() }
            out.step("setWritable") { file("t").setWritable(true) }
            out.step("canWrite") { file("t").canWrite() }
            out.step("canRead") { file("t").canRead() }
            out.step("canExecute") { file("t").canExecute() }
            out.step("setExecutable") { file("t").setExecutable(true, true) }
            out.step("canExecute after") { file("t").canExecute() }
            out.step("space") { file("t").totalSpace > 0 && file("t").usableSpace > 0 }
            out.step("toPath") { file("a/b").toPath().fileName }
        }

    @Test
    fun streams() =
        same { out ->
            out.step("open missing") { fis("none.txt") }
            file("d").mkdir()
            out.step("open folder") { fis("d") }
            out.step("write folder") { fos("d") }
            out.step("write in missing") { fos("none/x.txt") }
            out.step("write") { fos("s.txt").use { it.write("one".toByteArray()) } }
            out.step("append") { fos("s.txt", true).use { it.write("two".toByteArray()) } }
            out.step("read") { fis("s.txt").use { it.readBytes() } }
            out.step("channel") { fis("s.txt").use { it.channel.size().toString() + " " + it.channel.position() } }
            out.step("skip+available") { fis("s.txt").use { "${it.skip(2)} ${it.available()} ${it.read().toChar()}" } }
            out.step("writer utf16") { writer("w.txt", Charsets.UTF_16LE, false).use { it.write("한글 ok") } }
            out.step("writer append") { writer("w.txt", Charsets.UTF_16LE, true).use { it.append('!').let { } } }
            out.step("reader utf16") { reader("w.txt", Charsets.UTF_16LE).use { it.readText() } }
            out.step("raw bytes") { fis("w.txt").use { it.readBytes().size } }
            out.step("print writer") { printWriter(file("p.txt")).use { it.println("line"); it.printf("%d", 42).let { } } }
            out.step("print writer read") { fis("p.txt").use { it.readBytes() } }
            out.step("print stream") { printStream("q.txt").use { it.print(true); it.print(1.5) } }
            out.step("print stream read") { fis("q.txt").use { it.readBytes() } }
            out.step("print writer missing folder") { printWriter(file("none/p.txt")) }
            out.step("scanner") { fos("n.txt").use { it.write("a 1 b 22".toByteArray()) }; scan(file("n.txt")) }
            out.step("scanner missing") { scan(file("none.txt")) }
        }

    @Test
    fun randomAccess() =
        same { out ->
            out.step("bad mode") { raf("r.bin", "x") }
            out.step("read missing") { raf("r.bin", "r") }
            out.step("rw creates") {
                raf("r.bin", "rw").use {
                    it.writeInt(7)
                    it.writeUTF("é")
                    it.writeLong(-1)
                    "${it.filePointer} ${it.length()}"
                }
            }
            out.step("read back") {
                raf("r.bin", "r").use {
                    val a = it.readInt()
                    val b = it.readUTF()
                    it.seek(0)
                    "$a $b ${it.readByte()} ${it.length()}"
                }
            }
            out.step("write read-only") { raf("r.bin", "r").use { it.write(1) } }
            out.step("setLength") { raf("r.bin", "rw").use { it.setLength(3); it.length() } }
            out.step("map") {
                raf("m.bin", "rw").use {
                    it.setLength(8)
                    val map = it.channel.map(FileChannel.MapMode.READ_WRITE, 0, 8)
                    map.putInt(0, 0x01020304)
                    map.force()
                    it.seek(0)
                    it.readInt().toString(16)
                }
            }
            out.step("lock") { raf("m.bin", "rw").use { it.channel.lock().use { lock -> "${lock.isValid} ${lock.isShared}" } } }
            out.step("readLine") {
                fos("l.txt").use { it.write("x\r\ny\nz".toByteArray()) }
                raf("l.txt", "r").use { listOf(it.readLine(), it.readLine(), it.readLine(), it.readLine()) }
            }
            out.step("fd stream") { raf("l.txt", "r").use { r -> FileInputStream(r.fd).readBytes().size } }
        }

    @Test
    fun nio() =
        same { out ->
            out.step("write lines") { Files.write(path("n/lines.txt"), listOf("a", "b")) }
            Files.createDirectories(path("n/sub/deep"))
            out.step("write lines") { Files.write(path("n/lines.txt"), listOf("a", "b")) }
            out.step("read lines") { Files.readAllLines(path("n/lines.txt")) }
            out.step("size") { Files.size(path("n/lines.txt")) }
            out.step("exists") { "${Files.exists(path("n"))} ${Files.notExists(path("n/none"))} ${Files.isDirectory(path("n/sub"))} ${Files.isRegularFile(path("n/lines.txt"))}" }
            out.step("read missing") { Files.readAllBytes(path("n/none")) }
            out.step("create file twice") { Files.createFile(path("n/one")); Files.createFile(path("n/one")) }
            out.step("create dir twice") { Files.createDirectory(path("n/sub")) }
            out.step("list") { Files.list(path("n")).use { s -> s.map { it.fileName.toString() }.sorted().toList() } }
            out.step("walk") { Files.walk(path("n")).use { s -> s.map { path("n").relativize(it).toString() }.sorted().toList() } }
            out.step("find") { Files.find(path("n"), 5, { _, a -> a.isRegularFile }).use { s -> s.map { it.fileName.toString() }.sorted().toList() } }
            out.step("copy") { Files.copy(path("n/lines.txt"), path("n/sub/copy.txt")) }
            out.step("copy exists") { Files.copy(path("n/lines.txt"), path("n/sub/copy.txt")) }
            out.step("copy replace") { Files.copy(path("n/one"), path("n/sub/copy.txt"), StandardCopyOption.REPLACE_EXISTING); Files.size(path("n/sub/copy.txt")) }
            out.step("move") { Files.move(path("n/one"), path("n/two")) }
            out.step("move missing") { Files.move(path("n/one"), path("n/three")) }
            out.step("delete full") { Files.delete(path("n/sub")) }
            out.step("delete missing") { Files.delete(path("n/none")) }
            out.step("deleteIfExists") { "${Files.deleteIfExists(path("n/two"))} ${Files.deleteIfExists(path("n/two"))}" }
            out.step("attributes") { Files.readAttributes(path("n/lines.txt"), BasicFileAttributes::class.java).let { "${it.size()} ${it.isDirectory} ${it.isRegularFile} ${it.isSymbolicLink}" } }
            out.step("attribute map") { Files.readAttributes(path("n/lines.txt"), "size,isDirectory").toSortedMap() }
            out.step("posix") { Files.setPosixFilePermissions(path("n/lines.txt"), PosixFilePermissions.fromString("rw-r-----")); PosixFilePermissions.toString(Files.getPosixFilePermissions(path("n/lines.txt"))) }
            out.step("times") { Files.setLastModifiedTime(path("n/lines.txt"), FileTime.fromMillis(1_500_000_000_000)); Files.getLastModifiedTime(path("n/lines.txt")).toMillis() }
            out.step("symlink") { Files.createSymbolicLink(path("n/link"), path("lines.txt")); "${Files.isSymbolicLink(path("n/link"))} ${Files.readSymbolicLink(path("n/link"))} ${Files.readAllLines(path("n/link"))}" }
            out.step("link attrs") { Files.readAttributes(path("n/link"), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isSymbolicLink }
            out.step("same file") { Files.isSameFile(path("n/link"), path("n/lines.txt")) }
            out.step("buffered") { Files.newBufferedWriter(path("n/b.txt")).use { it.write("x\ny") }; Files.newBufferedReader(path("n/b.txt")).use { it.readLines() } }
            out.step("lines") { Files.lines(path("n/b.txt")).use { it.toList() } }
            out.step("create new twice") { Files.newByteChannel(path("n/b.txt"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE) }
            out.step("append") { Files.write(path("n/b.txt"), "z".toByteArray(), StandardOpenOption.APPEND); Files.readAllBytes(path("n/b.txt")) }
            out.step("truncate") { Files.write(path("n/b.txt"), "q".toByteArray(), StandardOpenOption.TRUNCATE_EXISTING); Files.readAllBytes(path("n/b.txt")) }
            out.step("channel") {
                FileChannel.open(path("n/c.bin"), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE).use {
                    it.write(ByteBuffer.wrap(byteArrayOf(1, 2, 3)))
                    val back = ByteBuffer.allocate(3)
                    it.read(back, 0)
                    "${it.size()} ${it.position()} ${back.array().toList()}"
                }
            }
            out.step("temp in dir") { Files.createTempFile(path("n"), "pre", ".suf").fileName.toString().let { it.startsWith("pre") && it.endsWith(".suf") } }
            out.step("walk depth") { Files.walk(path("n"), 1, FileVisitOption.FOLLOW_LINKS).use { it.count() } }
            out.step("to file") { path("n/lines.txt").toFile().length() }
        }

    @Test
    fun pathsWithoutFiles() =
        same { out ->
            out.step("normalize") { path("/a/./b/../c").normalize() }
            out.step("relativize") { path("/a/b").relativize(path("/a/c/d")) }
            out.step("resolve") { path("/a").resolve("b/c") }
            out.step("resolve absolute") { path("/a").resolve("/x") }
            out.step("sibling") { path("/a/b").resolveSibling("c") }
            out.step("subpath") { path("/a/b/c").subpath(1, 3) }
            out.step("names") { path("/a/b/c").map { it.toString() } }
            out.step("parts") { path("/a/b/c").let { "${it.fileName} ${it.parent} ${it.root} ${it.nameCount} ${it.isAbsolute}" } }
            out.step("starts") { "${path("/a/b").startsWith("/a")} ${path("/a/b").startsWith("/ab")} ${path("/a/b").endsWith("b")}" }
            out.step("relative") { Paths.get("x/y").let { "${it.isAbsolute} ${it.parent} ${it.root}" } }
            out.step("empty") { path("").let { "${it.nameCount} ${it.fileName}" } }
            out.step("uri") { path("/a/b").toUri() }
            out.step("matcher") { path("/a/b.txt").fileSystem.getPathMatcher("glob:**/*.{txt,md}").matches(path("/a/b.txt")) }
            out.step("nul") { path("/a\u0000b") }
        }

    @Test
    fun zips() =
        same { out ->
            ZipOutputStream(fos("z.zip")).use { zip ->
                zip.setComment("archive comment")
                zip.putNextEntry(ZipEntry("dir/"))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("dir/deflated.txt").apply { comment = "entry comment" })
                zip.write("deflated ".repeat(50).toByteArray())
                zip.closeEntry()
                val bytes = "stored".toByteArray()
                zip.putNextEntry(
                    ZipEntry("stored.txt").apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        crc = CRC32().apply { update(bytes) }.value
                    },
                )
                zip.write(bytes)
                zip.closeEntry()
            }
            val zip = zip("z.zip")
            out.step("comment") { zip.comment }
            out.step("names") { zip.names }
            out.step("dir") { zip.describe("dir") }
            out.step("deflated") { zip.describe("dir/deflated.txt") }
            out.step("stored") { zip.describe("stored.txt") }
            out.step("missing") { zip.describe("none") }
            out.step("read deflated") { zip.read("dir/deflated.txt")?.length }
            out.step("read stored") { zip.read("stored.txt") }
            zip.close()
            JarOutputStream(fos("j.jar"), Manifest().apply { mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"; mainAttributes.putValue("Created-By", "test") }).use { jar ->
                jar.putNextEntry(ZipEntry("a.txt"))
                jar.write("a".toByteArray())
                jar.closeEntry()
            }
            val jar = jar("j.jar")
            out.step("manifest") { jar.manifest() }
            out.step("jar names") { jar.names }
            jar.close()
            out.step("not a zip") { fos("bad.zip").use { it.write("nope".toByteArray()) }; zip("bad.zip") }
            out.step("missing zip") { zip("none.zip") }
        }
}
