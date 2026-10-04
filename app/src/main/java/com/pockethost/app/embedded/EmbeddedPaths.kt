package com.pockethost.app.embedded

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Built-in storage instead of SAF + Termux.
 *
 * A server can live in three places:
 *   INTERNAL: filesDir/servers/<serverId>/ ... (default)
 *   EXTERNAL: getExternalFilesDir("servers")/<serverId>/ ... (visible over USB, no permissions)
 *   CUSTOM: arbitrary folder (absolute path; only with all-files access)
 * First two need no permissions and serve as the process workdir.
 *   filesDir/tools/  - proot, jre, busybox (auto-downloaded, always internal)
 *   filesDir/ubuntu/ - Ubuntu rootfs (on demand, always internal)
 *
 * Location is encoded by the server URI scheme: "embedded://<id>" or "external://<id>".
 */
object EmbeddedPaths {

    enum class StorageKind { INTERNAL, EXTERNAL, CUSTOM }

    private val SAFE_ID_REGEX = Regex("[^a-zA-Z0-9._-]")

    fun kindOfUri(workDirUri: String): StorageKind = when {
        workDirUri.startsWith("external://") -> StorageKind.EXTERNAL
        workDirUri.startsWith("/") || workDirUri.startsWith("file://") -> StorageKind.CUSTOM
        else -> StorageKind.INTERNAL
    }

    fun storageUri(serverId: String, kind: StorageKind): String =
        if (kind == StorageKind.EXTERNAL) "external://$serverId" else "embedded://$serverId"

    /**
     * Is shared-storage custom-folder access available.
     * Android 11+: "all files access" (system settings).
     * Older: classic WRITE_EXTERNAL_STORAGE.
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

    /** Intents to the system "all files access" screen (first one that opens). */
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

    /** Real writability probe (canWrite sometimes lies on foreign FS). */
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
     * Prepares a custom server folder: creates, checks writability.
     * Returns File or null (reasons in logs).
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
     * Server root on app-external storage (no permissions needed).
     * null when storage unavailable (ejected SD card etc).
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

    /** Free bytes for the storage (for UI display). -1 when unavailable. */
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
        serverId.replace(SAFE_ID_REGEX, "_").take(64).ifEmpty { "server" }

    fun serverDir(context: Context, serverId: String, kind: StorageKind = StorageKind.INTERNAL): File {
        if (kind == StorageKind.EXTERNAL) {
            val root = externalServersRoot(context)
            if (root != null) return File(root, sanitizeId(serverId)).apply { mkdirs() }
            // External unavailable - honest fallback to internal (caller must warn).
            return serverDir(context, serverId, StorageKind.INTERNAL)
        }
        return File(serversRoot(context), sanitizeId(serverId)).apply { mkdirs() }
    }

    /**
     * Lookup without side-effect mkdirs() - for open/check with arbitrary input.
     * Checks the external folder first (when present), otherwise internal.
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
        // absolute path inside the app private dir
        return path.startsWith("/data/") || path.startsWith("/storage/emulated/0/Android/data/")
    }

    /**
     * Resolves the server work dir to a File.
     * - content:// (old SAF servers) -> return embedded dir for migration
     *   (SAF reading stays in FileManager for viewing, but launch goes embedded)
     * - embedded://<id> -> internal storage
     * - external://<id> -> app-external storage (fallback internal when unavailable)
     * - absolute path inside filesDir or app-external dirs -> File(path)
     * - empty / foreign path -> embedded dir for serverId
     */
    fun resolveWorkDir(context: Context, serverId: String, workDirUri: String): File {
        if (workDirUri.isEmpty() || isLegacySafPath(workDirUri)) {
            return serverDir(context, serverId)
        }
        // embedded://<id> format
        if (workDirUri.startsWith("embedded://")) {
            val id = workDirUri.removePrefix("embedded://").ifEmpty { serverId }
            return serverDir(context, id)
        }
        // external://<id> format
        if (workDirUri.startsWith("external://")) {
            val id = workDirUri.removePrefix("external://").ifEmpty { serverId }
            return serverDir(context, id, StorageKind.EXTERNAL)
        }
        // try as Uri -> content means embedded
        try {
            val uri = Uri.parse(workDirUri)
            if (uri.scheme == "content") return serverDir(context, serverId)
        } catch (_: Exception) {}
        // plain file path.
        // A folder outside the sandbox - only with all-files access,
        // otherwise fallback internal (caller must show the reason).
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
        if (kind == StorageKind.EXTERNAL) "External storage/servers/$serverId"
        else "Internal storage/servers/$serverId"
}
