package com.pockethost.app.embedded

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Р’Р±СѓРґРѕРІР°РЅРµ СЃС…РѕРІРёС‰Рµ Р·Р°РјС–СЃС‚СЊ SAF + Termux.
 *
 * РЎРµСЂРІРµСЂ РјРѕР¶Рµ Р¶РёС‚Рё Сѓ С‚СЂСЊРѕС… РјС–СЃС†СЏС…:
 *   INTERNAL: filesDir/servers/<serverId>/ ... (Р·Р° Р·Р°РјРѕРІС‡СѓРІР°РЅРЅСЏРј)
 *   EXTERNAL: getExternalFilesDir("servers")/<serverId>/ ... (РІРёРґРЅРѕ С‡РµСЂРµР· USB, Р±РµР· РґРѕР·РІРѕР»С–РІ)
 *   CUSTOM: РґРѕРІС–Р»СЊРЅР° РїР°РїРєР° (Р°Р±СЃРѕР»СЋС‚РЅРёР№ С€Р»СЏС…; Р»РёС€Рµ Р· РґРѕСЃС‚СѓРїРѕРј РґРѕ РІСЃС–С… С„Р°Р№Р»С–РІ)
 * РџРµСЂС€С– РґРІР° вЂ” Р±РµР· Р¶РѕРґРЅРёС… РґРѕР·РІРѕР»С–РІ С– РїС–РґС…РѕРґСЏС‚СЊ СЏРє СЂРѕР±РѕС‡Р° РїР°РїРєР° РїСЂРѕС†РµСЃСѓ.
 *   filesDir/tools/  - proot, jre, busybox (Р·Р°РІР°РЅС‚Р°Р¶СѓСЋС‚СЊСЃСЏ Р°РІС‚РѕРјР°С‚РёС‡РЅРѕ, Р·Р°РІР¶РґРё internal)
 *   filesDir/ubuntu/ - rootfs РґР»СЏ Ubuntu (Р·Р°РІР°РЅС‚Р°Р¶СѓС”С‚СЊСЃСЏ Р·Р° РїРѕС‚СЂРµР±РѕСЋ, Р·Р°РІР¶РґРё internal)
 *
 * РњС–СЃС†Рµ РєРѕРґСѓС”С‚СЊСЃСЏ СЃС…РµРјРѕСЋ URI СЃРµСЂРІРµСЂР°: "embedded://<id>" Р°Р±Рѕ "external://<id>".
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
     * Р§Рё С” РґРѕСЃС‚СѓРї РґРѕ РґРѕРІС–Р»СЊРЅРёС… РїР°РїРѕРє СЃРїС–Р»СЊРЅРѕРіРѕ СЃС…РѕРІРёС‰Р°.
     * Android 11+: "РґРѕСЃС‚СѓРї РґРѕ РІСЃС–С… С„Р°Р№Р»С–РІ" (СЃРёСЃС‚РµРјРЅС– РЅР°Р»Р°С€С‚СѓРІР°РЅРЅСЏ).
     * РЎС‚Р°СЂС–С€С–: РєР»Р°СЃРёС‡РЅРёР№ WRITE_EXTERNAL_STORAGE.
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

    /** Р†РЅС‚РµРЅС‚Рё РЅР° СЃРёСЃС‚РµРјРЅРёР№ РµРєСЂР°РЅ "РґРѕСЃС‚СѓРї РґРѕ РІСЃС–С… С„Р°Р№Р»С–РІ" (РїРµСЂС€РёР№ С‰Рѕ РІС–РґРєСЂРёС”С‚СЊСЃСЏ). */
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

    /** РЎРїСЂР°РІР¶РЅСЏ РїРµСЂРµРІС–СЂРєР° Р·Р°РїРёСЃСѓ (canWrite С–РЅРѕРґС– Р±СЂРµС€Рµ РЅР° С‡СѓР¶РёС… Р¤РЎ). */
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
     * Р“РѕС‚СѓС” РґРѕРІС–Р»СЊРЅСѓ РїР°РїРєСѓ СЃРµСЂРІРµСЂР°: СЃС‚РІРѕСЂСЋС”, РїРµСЂРµРІС–СЂСЏС” Р·Р°РїРёСЃ.
     * РџРѕРІРµСЂС‚Р°С” File Р°Р±Рѕ null (Р· РїСЂРёС‡РёРЅР°РјРё РІ Р»РѕРіР°С…).
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
     * РљРѕСЂС–РЅСЊ СЃРµСЂРІРµСЂС–РІ Сѓ Р·РѕРІРЅС–С€РЅСЊРѕРјСѓ СЃС…РѕРІРёС‰С– РґРѕРґР°С‚РєСѓ (РЅРµ РїРѕС‚СЂРµР±СѓС” РґРѕР·РІРѕР»С–РІ).
     * null СЏРєС‰Рѕ СЃС…РѕРІРёС‰Рµ РЅРµРґРѕСЃС‚СѓРїРЅРµ (РІРёС‚СЏРіРЅСѓС‚Р° SD-РєР°СЂС‚Р° С‚РѕС‰Рѕ).
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

    /** Р’С–Р»СЊРЅС– Р±Р°Р№С‚Рё РґР»СЏ СЃС…РѕРІРёС‰Р° (РґР»СЏ РїРѕРєР°Р·Сѓ РІ UI). -1 СЏРєС‰Рѕ РЅРµРґРѕСЃС‚СѓРїРЅРµ. */
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
            // Р—РѕРІРЅС–С€РЅС” РЅРµРґРѕСЃС‚СѓРїРЅРµ вЂ” С‡РµСЃРЅРёР№ fallback Сѓ internal (РІРёРєР»РёРєР°С‡ РјР°С” РїРѕРїРµСЂРµРґРёС‚Рё).
            return serverDir(context, serverId, StorageKind.INTERNAL)
        }
        return File(serversRoot(context), sanitizeId(serverId)).apply { mkdirs() }
    }

    /**
     * Lookup Р±РµР· РїРѕР±С–С‡РЅРѕРіРѕ mkdirs() вЂ” РґР»СЏ РІС–РґРєСЂРёС‚С‚СЏ/РїРµСЂРµРІС–СЂРѕРє Р·Р° РґРѕРІС–Р»СЊРЅРёРј РІРІРѕРґРѕРј.
     * РЁСѓРєР°С” Р·РѕРІРЅС–С€РЅСЋ РїР°РїРєСѓ РїРµСЂС€РѕСЋ (СЏРєС‰Рѕ С–СЃРЅСѓС”), С–РЅР°РєС€Рµ РІРЅСѓС‚СЂС–С€РЅСЋ.
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
        // Р°Р±СЃРѕР»СЋС‚РЅРёР№ С€Р»СЏС… РІСЃРµСЂРµРґРёРЅС– РїСЂРёРІР°С‚РЅРѕС— РїР°РїРєРё РґРѕРґР°С‚РєСѓ
        return path.startsWith("/data/") || path.startsWith("/storage/emulated/0/Android/data/")
    }

    /**
     * Р РµР·РѕР»РІРёС‚СЊ СЂРѕР±РѕС‡Сѓ РїР°РїРєСѓ СЃРµСЂРІРµСЂР° Сѓ File.
     * - content:// (СЃС‚Р°СЂС– SAF СЃРµСЂРІРµСЂРё) -> РїРѕРІРµСЂС‚Р°С”РјРѕ embedded dir РґР»СЏ РјС–РіСЂР°С†С–С—
     *   (С‡РёС‚Р°РЅРЅСЏ SAF Р»РёС€Р°С”С‚СЊСЃСЏ РІ FileManager РґР»СЏ РїРµСЂРµРіР»СЏРґСѓ, Р°Р»Рµ Р·Р°РїСѓСЃРє С–РґРµ РІ embedded)
     * - embedded://<id> -> РІРЅСѓС‚СЂС–С€РЅС” СЃС…РѕРІРёС‰Рµ
     * - external://<id> -> Р·РѕРІРЅС–С€РЅС” СЃС…РѕРІРёС‰Рµ РґРѕРґР°С‚РєСѓ (СЏРєС‰Рѕ РЅРµРґРѕСЃС‚СѓРїРЅРµ вЂ” fallback internal)
     * - Р°Р±СЃРѕР»СЋС‚РЅРёР№ С€Р»СЏС… РІСЃРµСЂРµРґРёРЅС– filesDir Р°Р±Рѕ app-external dirs -> File(path)
     * - РїРѕСЂРѕР¶РЅСЊРѕ / С‡СѓР¶РёР№ С€Р»СЏС… -> embedded dir Р·Р° serverId
     */
    fun resolveWorkDir(context: Context, serverId: String, workDirUri: String): File {
        if (workDirUri.isEmpty() || isLegacySafPath(workDirUri)) {
            return serverDir(context, serverId)
        }
        // embedded://<id> С„РѕСЂРјР°С‚
        if (workDirUri.startsWith("embedded://")) {
            val id = workDirUri.removePrefix("embedded://").ifEmpty { serverId }
            return serverDir(context, id)
        }
        // external://<id> С„РѕСЂРјР°С‚
        if (workDirUri.startsWith("external://")) {
            val id = workDirUri.removePrefix("external://").ifEmpty { serverId }
            return serverDir(context, id, StorageKind.EXTERNAL)
        }
        // СЃРїСЂРѕР±Р° СЏРє Uri -> СЏРєС‰Рѕ content, С‚Рѕ embedded
        try {
            val uri = Uri.parse(workDirUri)
            if (uri.scheme == "content") return serverDir(context, serverId)
        } catch (_: Exception) {}
        // Р·РІРёС‡Р°Р№РЅРёР№ С„Р°Р№Р»РѕРІРёР№ С€Р»СЏС….
        // Р”РѕРІС–Р»СЊРЅР° РїР°РїРєР° РїРѕР·Р° РїС–СЃРѕС‡РЅРёС†РµСЋ вЂ” Р»РёС€Рµ Р· РґРѕСЃС‚СѓРїРѕРј РґРѕ РІСЃС–С… С„Р°Р№Р»С–РІ,
        // С–РЅР°РєС€Рµ fallback internal (РІРёРєР»РёРєР°С‡ РјР°С” РїРѕРєР°Р·Р°С‚Рё РїСЂРёС‡РёРЅСѓ).
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
        if (kind == StorageKind.EXTERNAL) "Р—РѕРІРЅС–С€РЅС” СЃС…РѕРІРёС‰Рµ/servers/$serverId"
        else "Р’РЅСѓС‚СЂС–С€РЅС” СЃС…РѕРІРёС‰Рµ/servers/$serverId"
}
