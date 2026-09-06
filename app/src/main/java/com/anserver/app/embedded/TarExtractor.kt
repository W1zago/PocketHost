package com.anserver.app.embedded

import android.util.Log
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Розпаковувач .tar.xz на чистій Java.
 *
 * Навіщо: системний toybox-tar на багатьох пристроях зібраний без xz
 * ("tar: exec xz: No such file or directory", код 127), тому JRE-архів
 * Pojav неможливо розпакувати зовнішнім процесом. XZ-шар беремо з
 * org.tukaani:xz (вже є в залежностях), tar-парсер — свій мінімальний:
 * ustar/gnu заголовки, файли, папки, симлінки/хардлінки, GNU longname ('L'),
 * мінімальний pax ('x': path/size). Працює стрімінгом, в пам'яті лише буфери.
 *
 * Безпека: zip-slip відсікається (абсолютні шляхи, "..", вихід за dest).
 */
object TarExtractor {

    private const val TAG = "TarExtractor"
    private const val BLOCK = 512

    fun extractXzTar(archive: File, destDir: File, log: (String) -> Unit = {}): Boolean {
        return try {
            if (!archive.exists() || archive.length() < 1024) {
                log("Архів відсутній або крихітний (${archive.length()} B)")
                return false
            }
            destDir.mkdirs()
            FileInputStream(archive).use { fis ->
                BufferedInputStream(fis, 128 * 1024).use { bis ->
                    XZInputStream(bis).use { xz ->
                        extractTar(xz, destDir, log)
                    }
                }
            }
            true
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM while extracting " + archive.name, e)
            log("Розпакуванню не вистачило пам'яті. Закрий інші додатки і спробуй ще раз.")
            false
        } catch (e: Exception) {
            Log.e(TAG, "extract failed for " + archive.name, e)
            log("Вбудований розпаковувач: ${e.message}")
            false
        }
    }

    /** Швидка перевірка: чи файл взагалі схожий на .xz (магічні байти FD 37 7A 58 5A 00). */
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

    private fun extractTar(input: InputStream, destDir: File, log: (String) -> Unit) {
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
                if (entries == 0) throw IllegalStateException("порожній або обірваний tar-потік")
                break
            }
            if (isZeroBlock(header)) break // кінець архіву (два нульові блоки; вистачить першого)

            val rawName = readName(header)
            val mode = parseOctal(header, 100, 8)
            val mtime = parseOctal(header, 136, 12) * 1000L
            val dataSize = parseSize(header)
            val type = header[156].toInt().toChar()
            val linkName = readString(header, 157, 100)

            // Службові записи: довгі імена та pax-метадані — застосовуємо до НАСТУПНОГО запису.
            if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                val text = readDataAsString(input, dataSize)
                skipPadding(input, dataSize)
                when (type) {
                    'L' -> pendingLongName = text
                    'K' -> pendingLongLink = text
                    'x' -> parsePax(text)?.let { (p, s) -> paxPath = p; paxSize = s }
                    // 'g': глобальні pax-властивості — ігноруємо
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

            val target = safeTarget(destDir, destCanon, entryName)
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
                    // Симлінк. У JRE їх багато; на ext4 працює, крізь неіснуючий target — теж (dangling ок).
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
                    // Хардлінк: копіюємо вміст (простіше і працює всюди).
                    try {
                        val src = safeTarget(destDir, destCanon, entryLink)
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
                    // Пристрої, fifo, сокети тощо в JRE не потрібні — пропускаємо дані.
                    skipFully(input, entrySize)
                }
            }
            skipPadding(input, entrySize)
            entries++
            if (writtenMb - lastLogMb >= 10) {
                lastLogMb = writtenMb
                log("Розпаковано ~$writtenMb MB ($files файлів)...")
            }
        }

        log("Розпаковано записів: $entries, файлів: $files")
        if (entries == 0) throw IllegalStateException("в архіві немає записів")
        // Санітарна перевірка JRE: bin/java прямо або на рівень глибше (нормалізує викликач).
        val direct = File(destDir, "bin/java").exists()
        val nested = !direct && (destDir.listFiles()?.any { it.isDirectory && File(it, "bin/java").exists() } == true)
        if (!direct && !nested) throw IllegalStateException("bin/java не знайдено після розпакування")
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
            if (n == -1) throw java.io.EOFException("несподіваний кінець потоку на байті $off/${buf.size}")
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

    /** Парсинг octal; підтримує GNU base-256 для великих значень. */
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
        if (size > 16 * 1024 * 1024) throw IllegalStateException("занадто великий службовий блок ($size B)")
        val data = ByteArray(size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        readFully(input, data)
        var end = data.size
        while (end > 0 && data[end - 1] == 0.toByte()) end--
        return try { String(data, 0, end, Charsets.UTF_8) } catch (_: Exception) { String(data, 0, end) }
    }

    /** Мінімальний pax: витягує path= та size=. Формат записів: "<len> <key>=<value>\n". */
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
            if (r == -1) throw java.io.EOFException("потік обірвався під час пропуску ($left B лишилось)")
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
                if (r == -1) throw java.io.EOFException("файл обірвано, лишилось $left B")
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

    /** Захист від zip-slip: тільки всередині destDir. null = відхилити запис. */
    private fun safeTarget(destDir: File, destCanon: String, name: String): File? {
        return try {
            var n = name.trim().trimEnd('/')
            while (n.startsWith("./")) n = n.removePrefix("./")
            if (n.isEmpty()) return null
            if (n.startsWith("/") || n.startsWith("\\")) return null
            if (n.length > 2 && n[1] == ':' && n[0].isLetter()) return null // Windows-диск
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
                // Будь-який exec-біт -> виконуваний (JRE-бінарники та .so).
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
