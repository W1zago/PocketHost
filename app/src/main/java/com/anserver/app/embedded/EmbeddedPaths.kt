package com.anserver.app.embedded

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Вбудоване сховище замість SAF + Termux.
 *
 * Сервер може жити у трьох місцях:
 *   INTERNAL: filesDir/servers/<serverId>/ ... (за замовчуванням)
 *   EXTERNAL: getExternalFilesDir("servers")/<serverId>/ ... (видно через USB, без дозволів)
 *   CUSTOM: довільна папка (абсолютний шлях; лише з доступом до всіх файлів)
 * Перші два — без жодних дозволів і підходять як робоча папка процесу.
 *   filesDir/tools/  - proot, jre, busybox (завантажуються автоматично, завжди internal)
 *   filesDir/ubuntu/ - rootfs для Ubuntu (завантажується за потребою, завжди internal)
 *
 * Місце кодується схемою URI сервера: "embedded://<id>" або "external://<id>".
 */
object EmbeddedPaths {

    enum class StorageKind { INTERNAL, EXTERNAL, CUSTOM }

    fun kindOfUri(workDirUri: String): StorageKind = when {
        workDirUri.startsWith("external://") -> StorageKind.EXTERNAL
        workDirUri.startsWith("/") || workDirUri.startsWith("file://") -> StorageKind.CUSTOM
        else -> StorageKind.INTERNAL
    }

    fun storageUri(serverId: String, kind: StorageKind): String =
        if (kind == StorageKind.EXTERNAL) "external://$serverId" else "embedded://$serverId"

    /**
     * Чи є доступ до довільних папок спільного сховища.
     * Android 11+: "доступ до всіх файлів" (системні налаштування).
     * Старіші: класичний WRITE_EXTERNAL_STORAGE.
     */
    fun hasAllFilesAccess(context: Context): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                android.os.Environment.isExternalStorageManager()
            } else {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        } catch (_: Exception) { false }
    }

    /** Інтенти на системний екран "доступ до всіх файлів" (перший що відкриється). */
    fun allFilesAccessIntents(context: Context): List<android.content.Intent> {
        val pkg = context.packageName
        return listOf(
            android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:$pkg")
            ),
            android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:$pkg")
            )
        )
    }

    /** Справжня перевірка запису (canWrite іноді бреше на чужих ФС). */
    fun isDirWritable(dir: File): Boolean {
        return try {
            if (!dir.isDirectory || !dir.canWrite()) return false
            val probe = File(dir, ".write_test")
            probe.createNewFile()
            probe.delete()
            true
        } catch (_: Exception) { false }
    }

    /**
     * Готує довільну папку сервера: створює, перевіряє запис.
     * Повертає File або null (з причинами в логах).
     */
    fun prepareCustomDir(path: String): File? {
        return try {
            val clean = if (path.startsWith("file://")) {
                try { android.net.Uri.parse(path).path ?: return null } catch (_: Exception) { return null }
            } else path
            if (clean.isEmpty() || !clean.startsWith("/")) return null
            val dir = File(clean)
            if (dir.exists() && !dir.isDirectory) return null
            if (!dir.exists() && !dir.mkdirs()) return null
            if (!isDirWritable(dir)) return null
            dir
        } catch (_: Exception) { null }
    }

    fun serversRoot(context: Context): File =
        File(context.filesDir, "servers").apply { mkdirs() }

    /**
     * Корінь серверів у зовнішньому сховищі додатку (не потребує дозволів).
     * null якщо сховище недоступне (витягнута SD-карта тощо).
     */
    fun externalServersRoot(context: Context): File? {
        return try {
            val root = context.getExternalFilesDir("servers") ?: return null
            if ((!root.exists() && !root.mkdirs()) || !root.canWrite()) return null
            root
        } catch (_: Exception) { null }
    }

    fun isExternalStorageAvailable(context: Context): Boolean =
        externalServersRoot(context) != null

    /** Вільні байти для сховища (для показу в UI). -1 якщо недоступне. */
    fun freeBytes(context: Context, kind: StorageKind): Long {
        return try {
            val dir = if (kind == StorageKind.EXTERNAL) {
                externalServersRoot(context) ?: return -1
            } else {
                context.filesDir
            }
            android.os.StatFs(dir.absolutePath).availableBytes
        } catch (_: Exception) { -1 }
    }

    private fun sanitizeId(serverId: String): String =
        serverId.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64).ifEmpty { "server" }

    fun serverDir(context: Context, serverId: String, kind: StorageKind = StorageKind.INTERNAL): File {
        if (kind == StorageKind.EXTERNAL) {
            val root = externalServersRoot(context)
            if (root != null) return File(root, sanitizeId(serverId)).apply { mkdirs() }
            // Зовнішнє недоступне — чесний fallback у internal (викликач має попередити).
            return serverDir(context, serverId, StorageKind.INTERNAL)
        }
        return File(serversRoot(context), sanitizeId(serverId)).apply { mkdirs() }
    }

    /**
     * Lookup без побічного mkdirs() — для відкриття/перевірок за довільним вводом.
     * Шукає зовнішню папку першою (якщо існує), інакше внутрішню.
     */
    fun lookupServerDir(context: Context, serverId: String): File {
        val id = sanitizeId(serverId)
        try {
            val extRoot = try { context.getExternalFilesDir("servers") } catch (_: Exception) { null }
            if (extRoot != null) {
                val ext = File(extRoot, id)
                if (ext.exists() && ext.isDirectory) return ext
            }
        } catch (_: Exception) {}
        return File(File(context.filesDir, "servers"), id)
    }

    fun logFile(context: Context, serverId: String): File =
        File(lookupServerDir(context, serverId), "server.log")

    fun toolsDir(context: Context): File =
        File(context.filesDir, "tools").apply { mkdirs() }

    fun ubuntuRootfsDir(context: Context): File =
        File(context.filesDir, "ubuntu").apply { mkdirs() }

    fun isLegacySafPath(path: String): Boolean =
        path.startsWith("content://")

    fun isEmbeddedPath(path: String): Boolean {
        if (path.isEmpty()) return false
        if (path.startsWith("embedded://")) return true
        // абсолютний шлях всередині приватної папки додатку
        return path.startsWith("/data/") || path.startsWith("/storage/emulated/0/Android/data/")
    }

    /**
     * Резолвить робочу папку сервера у File.
     * - content:// (старі SAF сервери) -> повертаємо embedded dir для міграції
     *   (читання SAF лишається в FileManager для перегляду, але запуск іде в embedded)
     * - embedded://<id> -> внутрішнє сховище
     * - external://<id> -> зовнішнє сховище додатку (якщо недоступне — fallback internal)
     * - абсолютний шлях всередині filesDir або app-external dirs -> File(path)
     * - порожньо / чужий шлях -> embedded dir за serverId
     */
    fun resolveWorkDir(context: Context, serverId: String, workDirUri: String): File {
        if (workDirUri.isEmpty() || isLegacySafPath(workDirUri)) {
            return serverDir(context, serverId)
        }
        // embedded://<id> формат
        if (workDirUri.startsWith("embedded://")) {
            val id = workDirUri.removePrefix("embedded://").ifEmpty { serverId }
            return serverDir(context, id)
        }
        // external://<id> формат
        if (workDirUri.startsWith("external://")) {
            val id = workDirUri.removePrefix("external://").ifEmpty { serverId }
            return serverDir(context, id, StorageKind.EXTERNAL)
        }
        // спроба як Uri -> якщо content, то embedded
        try {
            val uri = Uri.parse(workDirUri)
            if (uri.scheme == "content") return serverDir(context, serverId)
        } catch (_: Exception) {}
        // звичайний файловий шлях.
        // Довільна папка поза пісочницею — лише з доступом до всіх файлів,
        // інакше fallback internal (викликач має показати причину).
        return try {
            val f = File(workDirUri)
            if (f.isAbsolute) {
                if (hasAllFilesAccess(context)) {
                    prepareCustomDir(f.absolutePath) ?: serverDir(context, serverId)
                } else {
                    val canon = try { f.canonicalPath } catch (_: Exception) { f.absolutePath }
                    val allowedRoots = mutableListOf<String>()
                    try {
                        allowedRoots.add(File(context.filesDir, "").canonicalPath + File.separator)
                    } catch (_: Exception) {}
                    try {
                        context.getExternalFilesDirs(null).forEach { root ->
                            if (root != null) allowedRoots.add(File(root, "").canonicalPath + File.separator)
                        }
                    } catch (_: Exception) {}
                    if (allowedRoots.any { canon.startsWith(it) }) f.apply { mkdirs() }
                    else serverDir(context, serverId)
                }
            } else {
                serverDir(context, serverId)
            }
        } catch (_: Exception) {
            serverDir(context, serverId)
        }
    }

    fun embeddedUri(serverId: String): String = "embedded://$serverId"

    fun displayPath(context: Context, serverId: String, kind: StorageKind = StorageKind.INTERNAL): String =
        if (kind == StorageKind.EXTERNAL) "Зовнішнє сховище/servers/$serverId"
        else "Внутрішнє сховище/servers/$serverId"
}
