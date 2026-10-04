package com.pockethost.app.embedded

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Minimal `ar` extractor for Termux .deb packages.
 *
 * Termux proot and its runtime deps (libtalloc, libandroid-shmem) are only
 * distributed as .deb archives: `!<arch>` container with `debian-binary`,
 * `control.tar.xz` and `data.tar.xz` members. We need just `data.tar.*`,
 * extracted with prefix stripping (entries live under
 * `./data/data/com.termux/files/usr/...`).
 *
 * Pure JVM implementation, no external binaries. The payload itself is
 * unpacked by [TarExtractor] (xz via Tukaani, gzip via stdlib).
 */
object DebExtractor {

    private const val TAG = "DebExtractor"

    /**
     * Extracts the `data.tar.*` member of [debFile] into [destDir].
     * @param stripComponents path components to drop (as in tar
     * --strip-components). Termux debs need 6 to turn
     * `./data/data/com.termux/files/usr/bin/proot` into `bin/proot`.
     * @return true on success.
     */
    fun extractDataTar(
        debFile: File,
        destDir: File,
        stripComponents: Int = 0,
        log: (String) -> Unit = {}
    ): Boolean {
        var tmp: File? = null
        return try {
            if (!debFile.isFile || debFile.length() < 1024) {
                log("deb missing or truncated: ${debFile.name}")
                return false
            }
            val member = readDataMember(debFile)
            if (member == null) {
                log("no data.tar.* member in ${debFile.name}")
                return false
            }
            val (memberName, payload) = member
            log("deb member: $memberName (${payload.size / 1024} KB)")
            tmp = File.createTempFile("deb-data-", ".tar", destDir.parentFile ?: destDir)
            FileOutputStream(tmp).use { it.write(payload) }
            val ok = when {
                isXz(payload) -> TarExtractor.extractXzTar(tmp, destDir, log, stripComponents)
                isGz(payload) -> TarExtractor.extractGzTar(tmp, destDir, log, stripComponents)
                else -> {
                    log("unknown data compression in ${debFile.name}")
                    false
                }
            }
            if (!ok) log("failed to unpack data of ${debFile.name}")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "deb extract failed ${debFile.name}", e)
            log("deb extract: ${e.message}")
            false
        } finally {
            try {
                tmp?.delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun isXz(payload: ByteArray): Boolean =
        payload.size >= 6 &&
            payload[0] == 0xFD.toByte() && payload[1] == 0x37.toByte() &&
            payload[2] == 0x7A.toByte() && payload[3] == 0x58.toByte() &&
            payload[4] == 0x5A.toByte() && payload[5] == 0x00.toByte()

    private fun isGz(payload: ByteArray): Boolean =
        payload.size >= 2 &&
            payload[0] == 0x1F.toByte() && payload[1] == 0x8B.toByte()

    /** Returns (memberName, bytes) of the first `data.tar.*` member, or null. */
    private fun readDataMember(debFile: File): Pair<String, ByteArray>? {
        FileInputStream(debFile).use { fis ->
            val magic = ByteArray(8)
            readFully(fis, magic)
            val magicStr = String(magic, Charsets.US_ASCII)
            if (!magicStr.startsWith("!<arch>")) return null
            while (true) {
                val header = ByteArray(60)
                try {
                    readFully(fis, header)
                } catch (_: java.io.EOFException) {
                    return null
                }
                val rawName = String(header, 0, 16, Charsets.US_ASCII).trim()
                val sizeStr = String(header, 48, 10, Charsets.US_ASCII).trim()
                val endMagic = byteArrayOf(header[58], header[59])
                if (endMagic[0] != 0x60.toByte() || endMagic[1] != 0x0A.toByte()) return null
                val size = sizeStr.toLongOrNull() ?: return null
                if (size < 0 || size > 512L * 1024 * 1024) return null
                val name = rawName.trimEnd('/')
                if (name.startsWith("data.tar.")) {
                    val payload = ByteArray(size.toInt())
                    readFully(fis, payload)
                    if (size % 2 == 1L) fis.read()
                    return name to payload
                } else {
                    skipFully(fis, size + (size % 2))
                }
            }
        }
    }

    private fun readFully(fis: FileInputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = fis.read(buf, off, buf.size - off)
            if (n == -1) throw java.io.EOFException("unexpected end of deb")
            if (n == 0) continue
            off += n
        }
    }

    private fun skipFully(fis: FileInputStream, n: Long) {
        var left = n
        val buf = ByteArray(32 * 1024)
        while (left > 0) {
            val want = minOf(buf.size.toLong(), left).toInt()
            val r = fis.read(buf, 0, want)
            if (r == -1) throw java.io.EOFException("unexpected end of deb")
            if (r == 0) continue
            left -= r
        }
    }
}
