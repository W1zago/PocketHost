package com.pockethost.app.embedded

import android.util.Log
import org.tukaani.xz.XZInputStream
import java.util.zip.GZIPInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * .tar.xz / .tar.gz unpacker in pure Java.
 *
 * Why: system toybox-tar on many devices is built without xz
 * ("tar: exec xz: No such file or directory", code 127), so the Pojav JRE archive
 * cannot be unpacked by an external process. XZ layer comes from
 * org.tukaani:xz (already a dependency), gzip from stdlib, tar parser is our own minimal one:
 * ustar/gnu headers, files, dirs, symlinks/hardlinks, GNU longname ('L'),
 * minimal pax ('x': path/size). Streams, only buffers in memory.
 *
 * Safety: zip-slip is rejected (absolute paths, "..", escape from dest).
 */
object TarExtractor {

    private const val TAG = "TarExtractor"
    private const val BLOCK = 512

    fun extractXzTar(
        archive: File,
        destDir: File,
        log: (String) -> Unit = {},
        stripComponents: Int = 0,
        requireBinJava: Boolean = true
    ): Boolean {
        return try {
            if (!archive.exists() || archive.length() < 1024) {
                log("Archive missing or truncated (${archive.length()} B)")
                return false
            }
            destDir.mkdirs()
            FileInputStream(archive).use { fis ->
                BufferedInputStream(fis, 128 * 1024).use { bis ->
                    XZInputStream(bis).use { xz ->
                        extractTar(xz, destDir, log, stripComponents, requireBinJava)
                    }
                }
            }
            true
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM while extracting " + archive.name, e)
            log("Out of memory during extraction. Close other apps and retry.")
            false
        } catch (e: Exception) {
            Log.e(TAG, "extract failed for " + archive.name, e)
            log("Built-in unpacker: ${e.message}")
            false
        }
    }

    /** Quick check: does the file look like .xz at all (magic bytes FD 37 7A 58 5A 00). */
    fun looksLikeXz(file: File): Boolean {
        return try {
            if (!file.isFile || file.length() < 1024) return false
            FileInputStream(file).use { fis ->
                val magic = ByteArray(6)
                var off = 0
                while (off < magic.size) {
                    val n = fis.read(magic, off, magic.size - off)
                    if (n == -1) return false
                    if (n == 0) continue
                    off += n
                }
                magic[0] == 0xFD.toByte() && magic[1] == 0x37.toByte() &&
                    magic[2] == 0x7A.toByte() && magic[3] == 0x58.toByte() &&
                    magic[4] == 0x5A.toByte() && magic[5] == 0x00.toByte()
            }
        } catch (_: Exception) { false }
    }

    fun extractGzTar(
        archive: File,
        destDir: File,
        log: (String) -> Unit = {},
        stripComponents: Int = 0
    ): Boolean {
        return try {
            if (!archive.exists() || archive.length() < 1024) {
                log("archive missing or truncated")
                return false
            }
            destDir.mkdirs()
            FileInputStream(archive).use { fis ->
                BufferedInputStream(fis, 128 * 1024).use { bis ->
                    GZIPInputStream(bis).use { gz ->
                        extractTar(gz, destDir, log, stripComponents, requireBinJava = false)
                    }
                }
            }
            true
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM while extracting " + archive.name, e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "extract failed for " + archive.name, e)
            log("embedded extractor: ${e.message}")
            false
        }
    }

    /** Quick check: does the file look like .gz (magic bytes 1F 8B). */
    fun looksLikeGz(file: File): Boolean {
        return try {
            if (!file.isFile || file.length() < 1024) return false
            FileInputStream(file).use { fis ->
                val magic = ByteArray(2)
                var off = 0
                while (off < magic.size) {
                    val n = fis.read(magic, off, magic.size - off)
                    if (n == -1) return false
                    if (n == 0) continue
                    off += n
                }
                magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte()
            }
        } catch (_: Exception) { false }
    }

    private fun extractTar(
        input: InputStream,
        destDir: File,
        log: (String) -> Unit,
        stripComponents: Int = 0,
        requireBinJava: Boolean = false
    ) {
        val destCanon = try { destDir.canonicalPath } catch (_: Exception) { destDir.absolutePath }
        val header = ByteArray(BLOCK)
        var pendingLongName: String? = null
        var pendingLongLink: String? = null
        var paxPath: String? = null
        var paxSize: Long? = null
        var entries = 0
        var files = 0
        var writtenMb = 0L
        var lastLogMb = 0L

        while (true) {
            try {
                readFully(input, header)
            } catch (e: java.io.EOFException) {
                if (entries == 0) throw IllegalStateException("empty or truncated tar stream")
                break
            }
            if (isZeroBlock(header)) break // end of archive (two zero blocks; first suffices)

            val rawName = readName(header)
            val mode = parseOctal(header, 100, 8)
            val mtime = parseOctal(header, 136, 12) * 1000L
            val dataSize = parseSize(header)
            val type = header[156].toInt().toChar()
            val linkName = readString(header, 157, 100)

            // Service records: long names and pax metadata apply to the NEXT record.
            if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                val text = readDataAsString(input, dataSize)
                skipPadding(input, dataSize)
                when (type) {
                    'L' -> pendingLongName = text
                    'K' -> pendingLongLink = text
                    'x' -> parsePax(text)?.let { (p, s) -> paxPath = p; paxSize = s }
                    // 'g': global pax properties - ignored
                }
                continue
            }

            val entryName = pendingLongName ?: paxPath ?: rawName
            pendingLongName = null
            val entryLink = pendingLongLink ?: linkName
            pendingLongLink = null
            val entrySize = paxSize ?: dataSize
            paxPath = null
            paxSize = null

            val target = safeTarget(destDir, destCanon, entryName, stripComponents)
            if (target == null) {
                skipFully(input, entrySize)
                skipPadding(input, entrySize)
                Log.w(TAG, "skip suspicious path: $entryName")
                continue
            }

            when (type) {
                '5' -> {
                    target.mkdirs()
                    applyMode(target, mode, isDir = true, mtime)
                }
                '2' -> {
                    // Symlink. JRE has many; works on ext4, dangling target is fine too.
                    try {
                        target.parentFile?.mkdirs()
                        if (target.exists() || isSymlink(target)) {
                            try { target.delete() } catch (_: Exception) {}
                        }
                        android.system.Os.symlink(entryLink, target.absolutePath)
                    } catch (e: Exception) {
                        Log.w(TAG, "symlink failed: $entryName -> $entryLink: ${e.message}")
                    }
                }
                '1' -> {
                    // Hardlink: copy contents (simpler and works everywhere).
                    try {
                        val src = safeTarget(destDir, destCanon, entryLink, stripComponents)
                        if (src != null && src.isFile) {
                            target.parentFile?.mkdirs()
                            copyFile(src, target)
                            applyMode(target, mode, isDir = false, mtime)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "hardlink copy failed: $entryName: ${e.message}")
                    }
                }
                '0', '\u0000', '7' -> {
                    target.parentFile?.mkdirs()
                    copyStream(input, target, entrySize)
                    applyMode(target, mode, isDir = false, mtime)
                    files++
                    writtenMb += entrySize / 1024 / 1024
                }
                else -> {
                    // Devices, fifos, sockets etc. are not needed in a JRE - skip the data.
                    skipFully(input, entrySize)
                }
            }
            skipPadding(input, entrySize)
            entries++
            if (writtenMb - lastLogMb >= 10) {
                lastLogMb = writtenMb
                log("Extracted ~$writtenMb MB ($files files)...")
            }
        }

        log("Extracted records: $entries, files: $files")
        if (entries == 0) throw IllegalStateException("no records in archive")
        // JRE sanity check: bin/java directly or one level deeper (caller normalizes).
        if (requireBinJava) {
            val direct = File(destDir, "bin/java").exists()
            val nested = !direct && (destDir.listFiles()?.any { it.isDirectory && File(it, "bin/java").exists() } == true)
            if (!direct && !nested) throw IllegalStateException("bin/java not found after extraction")
        }
    }

    // ---------- tar low-level ----------

    private fun isZeroBlock(b: ByteArray): Boolean {
        for (x in b) if (x != 0.toByte()) return false
        return true
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n == -1) throw java.io.EOFException("unexpected end of stream at byte $off/${buf.size}")
            if (n == 0) continue
            off += n
        }
    }

    private fun readString(buf: ByteArray, off: Int, len: Int): String {
        var end = off
        val max = minOf(off + len, buf.size)
        while (end < max && buf[end] != 0.toByte()) end++
        return try {
            String(buf, off, end - off, Charsets.UTF_8)
        } catch (_: Exception) {
            String(buf, off, end - off)
        }
    }

    private fun readName(header: ByteArray): String {
        val name = readString(header, 0, 100)
        val prefix = readString(header, 345, 155)
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    /** Octal parsing; supports GNU base-256 for large values. */
    private fun parseOctal(buf: ByteArray, off: Int, len: Int): Long {
        if (off < buf.size && (buf[off].toInt() and 0x80) != 0) {
            var v = (buf[off].toInt() and 0x7F).toLong()
            var i = off + 1
            while (i < off + len && i < buf.size) {
                v = (v shl 8) or (buf[i].toInt() and 0xFF).toLong()
                i++
            }
            return v
        }
        var end = off
        while (end < off + len && end < buf.size && buf[end] != 0.toByte() && buf[end] != ' '.code.toByte()) end++
        if (end <= off) return 0L
        return try {
            String(buf, off, end - off, Charsets.US_ASCII).trim().toLong(8)
        } catch (_: Exception) { 0L }
    }

    private fun parseSize(header: ByteArray): Long {
        val v = parseOctal(header, 124, 12)
        return if (v < 0) 0L else v
    }

    private fun readDataAsString(input: InputStream, size: Long): String {
        if (size <= 0) return ""
        if (size > 16 * 1024 * 1024) throw IllegalStateException("oversized service block ($size B)")
        val data = ByteArray(size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        readFully(input, data)
        var end = data.size
        while (end > 0 && data[end - 1] == 0.toByte()) end--
        return try { String(data, 0, end, Charsets.UTF_8) } catch (_: Exception) { String(data, 0, end) }
    }

    /** Minimal pax: extracts path= and size=. Record format: "<len> <key>=<value>\n". */
    private fun parsePax(text: String): Pair<String?, Long?> {
        var path: String? = null
        var size: Long? = null
        for (line in text.split('\n')) {
            val body = line.substringAfter(' ', missingDelimiterValue = "")
            if (body.isEmpty()) continue
            val eq = body.indexOf('=')
            if (eq <= 0) continue
            val key = body.substring(0, eq)
            val value = body.substring(eq + 1)
            when (key) {
                "path" -> if (value.isNotEmpty()) path = value
                "size" -> size = value.toLongOrNull()
            }
        }
        if (path == null && size == null) return Pair(null, null)
        return Pair(path, size)
    }

    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(32 * 1024)
        while (left > 0) {
            val want = minOf(buf.size.toLong(), left).toInt()
            val r = input.read(buf, 0, want)
            if (r == -1) throw java.io.EOFException("stream cut while skipping ($left B left)")
            if (r == 0) continue
            left -= r
        }
    }

    private fun skipPadding(input: InputStream, dataSize: Long) {
        val pad = ((BLOCK - (dataSize % BLOCK)) % BLOCK)
        if (pad > 0) skipFully(input, pad)
    }

    private fun copyStream(input: InputStream, target: File, size: Long) {
        FileOutputStream(target).use { out ->
            var left = size
            val buf = ByteArray(64 * 1024)
            while (left > 0) {
                val want = minOf(buf.size.toLong(), left).toInt()
                val r = input.read(buf, 0, want)
                if (r == -1) throw java.io.EOFException("file truncated, $left B left")
                if (r == 0) continue
                out.write(buf, 0, r)
                left -= r
            }
            out.flush()
            try { out.fd.sync() } catch (_: Exception) {}
        }
    }

    private fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { inp ->
            FileOutputStream(dst).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = inp.read(buf)
                    if (r == -1) break
                    if (r == 0) continue
                    out.write(buf, 0, r)
                }
                out.flush()
                try { out.fd.sync() } catch (_: Exception) {}
            }
        }
    }

    /** zip-slip guard: only inside destDir. null = reject the record. */
    private fun safeTarget(
        destDir: File,
        destCanon: String,
        name: String,
        stripComponents: Int = 0
    ): File? {
        return try {
            var n = name.trim().trimEnd('/')
            if (stripComponents > 0) {
                val parts0 = n.split('/').filter { it.isNotEmpty() }
                // NOTE: a leading "." counts as a component (GNU tar semantics), so it is
                // intentionally NOT filtered: './data/.../usr/bin/proot' with strip=6 gives 'bin/proot'.
                if (parts0.size <= stripComponents) return null
                n = parts0.drop(stripComponents).joinToString("/")
            }
            while (n.startsWith("./")) n = n.removePrefix("./")
            if (n.isEmpty()) return null
            if (n.startsWith("/") || n.startsWith("\\")) return null
            if (n.length > 2 && n[1] == ':' && n[0].isLetter()) return null // Windows drive
            val parts = n.split('/')
            if (parts.any { it == ".." }) return null
            val target = File(destDir, n)
            val canon = target.canonicalPath
            if (canon != destCanon && !canon.startsWith(destCanon + File.separator)) return null
            target
        } catch (_: Exception) { null }
    }

    private fun isSymlink(f: File): Boolean {
        return try {
            java.nio.file.Files.isSymbolicLink(f.toPath())
        } catch (_: Exception) { false }
    }

    private fun applyMode(f: File, mode: Long, isDir: Boolean, mtimeMs: Long) {
        try {
            if (isDir) {
                f.setExecutable(true, false)
                f.setReadable(true, false)
                f.setWritable(true, false)
            } else {
                // Any exec bit -> executable (JRE binaries and .so).
                if ((mode and 0b001001001L) != 0L) f.setExecutable(true, false)
                f.setReadable(true, false)
                if ((mode and 0b010000000L) != 0L) f.setWritable(true, false)
            }
            if (mtimeMs > 0) {
                try { f.setLastModified(mtimeMs) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
}
