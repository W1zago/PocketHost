package com.anserver.app.util

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

internal object TarExtractor {

    private const val BLOCK_SIZE = 512
    private const val EXEC_BITS_MASK = (64 + 8 + 1).toLong()

    class ExtractResult {
        var fileCount: Int = 0
            private set
        var dirCount: Int = 0
            private set
        val failedLinks: MutableList<Pair<String, String>> = mutableListOf()

        internal fun recordFile() {
            fileCount++
        }

        internal fun recordDir() {
            dirCount++
        }

        internal fun recordFailedLink(name: String, linkName: String) {
            failedLinks.add(name to linkName)
        }
    }

    fun extract(tarStream: InputStream, destination: File): ExtractResult {
        val result = ExtractResult()
        destination.mkdirs()
        var pendingLongName: String? = null
        var pendingLongLink: String? = null
        var pendingPaxPath: String? = null

        while (true) {
            val header = ByteArray(BLOCK_SIZE)
            if (!readFully(tarStream, header)) {
                break
            }
            if (isZeroBlock(header)) {
                break
            }

            val rawName = readString(header, 0, 100)
            val mode = parseOctal(header, 100, 8)
            val size = parseSize(header, 124, 12)
            val rawTypeFlag = header[156].toInt().toChar()
            val typeFlag = if (rawTypeFlag.code == 0) '0' else rawTypeFlag
            val rawLinkName = readString(header, 157, 100)

            var name = pendingPaxPath ?: pendingLongName ?: rawName
            if (pendingPaxPath == null && pendingLongName == null) {
                val prefix = readString(header, 345, 155)
                if (prefix.isNotEmpty()) {
                    name = prefix + "/" + name
                }
            }
            val linkName = rawLinkName.ifEmpty { pendingLongLink ?: "" }
            pendingPaxPath = null
            pendingLongName = null
            pendingLongLink = null

            when (typeFlag) {
                'L' -> {
                    pendingLongName = readContentString(tarStream, size)
                }
                'K' -> {
                    pendingLongLink = readContentString(tarStream, size)
                }
                'x', 'X', 'g' -> {
                    val pax = readContentString(tarStream, size)
                    pendingPaxPath = parsePaxPath(pax)
                }
                '5' -> {
                    skipEntry(tarStream, size)
                    safeTarget(destination, name).mkdirs()
                    result.recordDir()
                }
                '0' -> {
                    val file = safeTarget(destination, name)
                    file.parentFile?.mkdirs()
                    file.outputStream().use { output ->
                        copyExactly(tarStream, output, size)
                    }
                    skipPadding(tarStream, size)
                    applyPermissions(file, mode)
                    result.recordFile()
                }
                '1' -> {
                    skipEntry(tarStream, size)
                    if (!createHardLink(destination, linkName, name)) {
                        result.recordFailedLink(name, linkName)
                    }
                }
                '2' -> {
                    skipEntry(tarStream, size)
                    if (!createSymbolicLink(destination, linkName, name)) {
                        result.recordFailedLink(name, linkName)
                    }
                }
                else -> {
                    skipEntry(tarStream, size)
                }
            }
        }
        return result
    }

    fun repairLinks(destination: File, failedLinks: List<Pair<String, String>>): Int {
        var repaired = 0
        failedLinks.forEach { (name, linkName) ->
            try {
                val link = safeTarget(destination, name)
                val target = safeTarget(destination, linkName)
                if (link.exists() || Files.isSymbolicLink(link.toPath())) {
                    return@forEach
                }
                link.parentFile?.mkdirs()
                if (target.isDirectory) {
                    Files.createSymbolicLink(Paths.get(link.absolutePath), Paths.get(linkName))
                    repaired++
                } else if (target.exists()) {
                    Files.createSymbolicLink(Paths.get(link.absolutePath), Paths.get(linkName))
                    repaired++
                }
            } catch (_: Exception) {
            }
        }
        return repaired
    }

    private fun isZeroBlock(block: ByteArray): Boolean {
        block.forEach { if (it.toInt() != 0) return false }
        return true
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) {
                return false
            }
            offset += read
        }
        return true
    }

    private fun copyExactly(input: InputStream, output: OutputStream, size: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val chunk = minOf(buffer.size.toLong(), remaining).toInt()
            var offset = 0
            while (offset < chunk) {
                val read = input.read(buffer, offset, chunk - offset)
                if (read < 0) throw IOException("Unexpected end of tar stream")
                offset += read
            }
            output.write(buffer, 0, chunk)
            remaining -= chunk
        }
    }

    private fun skipData(input: InputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val chunk = minOf(buffer.size.toLong(), remaining).toInt()
            var offset = 0
            while (offset < chunk) {
                val read = input.read(buffer, offset, chunk - offset)
                if (read < 0) throw IOException("Unexpected end of tar stream")
                offset += read
            }
            remaining -= chunk
        }
    }

    private fun skipEntry(input: InputStream, size: Long) {
        skipData(input, size)
        skipPadding(input, size)
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val padding = ((BLOCK_SIZE - (size % BLOCK_SIZE)) % BLOCK_SIZE).toInt()
        if (padding > 0) {
            skipData(input, padding.toLong())
        }
    }

    private fun readContentString(input: InputStream, size: Long): String {
        val data = ByteArray(size.toInt())
        var offset = 0
        while (offset < data.size) {
            val read = input.read(data, offset, data.size - offset)
            if (read < 0) throw IOException("Unexpected end of tar stream")
            offset += read
        }
        skipPadding(input, size)
        return String(data, Charsets.ISO_8859_1).trimEnd { it == ' ' || it.code == 0 }
    }

    private fun parsePaxPath(content: String): String? {
        var position = 0
        while (position < content.length) {
            val spaceIndex = content.indexOf(' ', position)
            if (spaceIndex < 0) break
            val length = content.substring(position, spaceIndex).toIntOrNull() ?: break
            if (length <= 0 || position + length > content.length) break
            val record = content.substring(position, position + length)
            val valueStart = record.indexOf('=')
            if (valueStart > 0) {
                val key = record.substring(0, valueStart)
                val value = record.substring(valueStart + 1).trimEnd('\n')
                if (key == "path") {
                    return value
                }
            }
            position += length
        }
        return null
    }

    private fun readString(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val max = offset + length
        while (end < max && header[end].toInt() != 0) {
            end++
        }
        return String(header, offset, end - offset, Charsets.ISO_8859_1).trim()
    }

    private fun parseSize(header: ByteArray, offset: Int, length: Int): Long {
        if (header[offset].toInt() and 0x80 != 0) {
            var value = 0L
            for (i in offset until offset + length) {
                value = (value shl 8) or (header[i].toLong() and 0xFF)
            }
            return value
        }
        return parseOctal(header, offset, length)
    }

    private fun parseOctal(header: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        var started = false
        for (i in offset until offset + length) {
            val byte = header[i]
            if (byte.toInt() == 0 || byte == 32.toByte()) {
                if (started) break
                continue
            }
            started = true
            value = (value shl 3) or (byte.toLong() - 48L)
        }
        return value
    }

    private fun safeTarget(root: File, name: String): File {
        val normalized = name.trim().trimStart('/')
        if (normalized.isEmpty()) {
            throw IOException("Invalid tar entry name: '$name'")
        }
        val target = File(root, normalized)
        val canonicalRoot = root.canonicalPath
        val canonicalTarget = target.canonicalPath
        if (canonicalTarget != canonicalRoot && !canonicalTarget.startsWith(canonicalRoot + File.separator)) {
            throw IOException("Tar entry escapes destination: '$name'")
        }
        return target
    }

    private fun applyPermissions(file: File, mode: Long) {
        try {
            if (mode and EXEC_BITS_MASK != 0L) {
                Files.setPosixFilePermissions(
                    Paths.get(file.absolutePath),
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_EXECUTE
                    )
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun createHardLink(destination: File, linkName: String, name: String): Boolean {
        if (linkName.isEmpty()) return false
        return try {
            val existing = safeTarget(destination, linkName)
            val link = safeTarget(destination, name)
            if (!existing.exists()) {
                false
            } else {
                if (link.exists() || Files.isSymbolicLink(link.toPath())) {
                    link.delete()
                }
                link.parentFile?.mkdirs()
                Files.createLink(Paths.get(link.absolutePath), Paths.get(existing.absolutePath))
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun createSymbolicLink(destination: File, linkName: String, name: String): Boolean {
        if (linkName.isEmpty()) return false
        return try {
            val link = safeTarget(destination, name)
            if (link.exists() || Files.isSymbolicLink(link.toPath())) {
                link.delete()
            }
            link.parentFile?.mkdirs()
            Files.createSymbolicLink(Paths.get(link.absolutePath), Paths.get(linkName))
            true
        } catch (_: Exception) {
            false
        }
    }
}
