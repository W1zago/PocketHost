package com.pockethost.jvmhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Builds a small .tar.gz in memory (own minimal ustar writer) and checks
 * that [TarGzExtractor] restores files, dirs, symlinks, GNU long names,
 * and rejects zip-slip entries.
 */
class TarGzExtractorTest {

    @Test
    fun roundTripWithSlipRejection() {
        val tar = ByteArrayOutputStream()
        writeEntry(tar, "hello.txt", fileBytes("hello ubuntu"))
        writeEntry(tar, "sub/", ByteArray(0), type = '5')
        writeEntry(tar, "sub/nested.txt", fileBytes("nested"))
        writeEntry(tar, "link.txt", ByteArray(0), type = '2', link = "hello.txt")
        val longName = "d/".repeat(40) + "long.txt"
        writeEntry(tar, longName, fileBytes("long"), gnuLongName = true)
        // Attacks: must be skipped, never written outside dest.
        writeEntry(tar, "../evil.txt", fileBytes("evil"))
        writeEntry(tar, "/abs.txt", fileBytes("abs"))
        writeEntry(tar, "sub/../../escape.txt", fileBytes("escape"))
        tar.write(ByteArray(1024)) // end-of-archive zero blocks

        val archive = File.createTempFile("rootfs-test", ".tar.gz").apply { deleteOnExit() }
        GZIPOutputStream(archive.outputStream()).use { it.write(tar.toByteArray()) }

        val dest = java.nio.file.Files.createTempDirectory("pockethost-tar").toFile().apply { deleteOnExit() }
        val messages = mutableListOf<String>()
        assertTrue(TarGzExtractor.extract(archive, dest, log = { messages.add(it) }))

        assertEquals("hello ubuntu", File(dest, "hello.txt").readText())
        assertTrue(File(dest, "sub").isDirectory)
        assertEquals("nested", File(dest, "sub/nested.txt").readText())
        assertEquals("long", File(dest, longName).readText())

        val link = File(dest, "link.txt").toPath()
        try {
            // Symlinks need privilege (Windows developer mode / Unix): assert only when created.
            if (java.nio.file.Files.isSymbolicLink(link)) {
                assertEquals("hello.txt", java.nio.file.Files.readSymbolicLink(link).toString())
            }
        } catch (_: Exception) {
            // Privilege missing - extractor must have skipped it without failing.
        }

        assertFalse(File(dest, "../evil.txt").exists())
        assertFalse(File(dest, "evil.txt").exists())
        assertFalse(File(dest, "abs.txt").exists())
        assertFalse(File(dest, "escape.txt").exists())
        assertFalse(File(dest.parentFile, "evil.txt").exists())
        assertTrue(messages.any { it.contains("Extracted records") })
    }

    @Test
    fun truncatedArchiveFailsCleanly() {
        val archive = File.createTempFile("broken-test", ".tar.gz").apply { deleteOnExit() }
        archive.writeBytes(byteArrayOf(0x1F.toByte(), 0x8B.toByte(), 0x01, 0x02))
        val dest = java.nio.file.Files.createTempDirectory("pockethost-tar-broken").toFile().apply { deleteOnExit() }
        val messages = mutableListOf<String>()
        assertFalse(TarGzExtractor.extract(archive, dest, log = { messages.add(it) }))
    }

    // ---------- minimal ustar writer ----------

    private fun fileBytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    private fun writeEntry(
        out: ByteArrayOutputStream,
        name: String,
        data: ByteArray,
        type: Char = '0',
        link: String = "",
        gnuLongName: Boolean = false
    ) {
        if (gnuLongName) {
            val raw = (name + "\u0000").toByteArray(Charsets.UTF_8)
            writeHeader(out, "././@LongLink", raw, 'L')
            out.write(raw)
            out.write(ByteArray((512 - raw.size % 512) % 512))
        }
        val storedName = if (gnuLongName) name.take(100) else name
        writeHeader(out, storedName, data, type, link)
        if (type == '0' && data.isNotEmpty()) {
            out.write(data)
            out.write(ByteArray((512 - data.size % 512) % 512))
        }
    }

    private fun writeHeader(
        out: ByteArrayOutputStream,
        name: String,
        data: ByteArray,
        type: Char,
        link: String = ""
    ) {
        val header = ByteArray(512)
        putString(header, 0, 100, name)
        putOctal(header, 100, 8, if (type == '5') 493L else 420L) // 0755 / 0644
        putOctal(header, 108, 8, 0)
        putOctal(header, 116, 8, 0)
        putOctal(header, 124, 12, data.size.toLong())
        putOctal(header, 136, 12, 1_700_000_000L)
        header[156] = type.code.toByte()
        putString(header, 157, 100, link)
        putString(header, 257, 6, "ustar")
        putString(header, 263, 2, "00")
        // Checksum over the header with the checksum field filled with spaces.
        for (i in 148 until 156) header[i] = 0x20.toByte()
        var sum = 0L
        for (b in header) sum += b.toInt() and 0xFF
        putString(header, 148, 6, sum.toString(8).padStart(6, '0'))
        header[154] = 0
        header[155] = 0x20.toByte()
        out.write(header)
    }

    private fun putString(buf: ByteArray, off: Int, len: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, len)
        System.arraycopy(bytes, 0, buf, off, n)
    }

    private fun putOctal(buf: ByteArray, off: Int, len: Int, value: Long) {
        val text = value.toString(8).padStart(len - 1, '0')
        putString(buf, off, len - 1, text.takeLast(len - 1))
    }
}
