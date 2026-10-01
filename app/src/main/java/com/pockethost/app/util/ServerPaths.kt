package com.pockethost.app.util

import android.content.Context
import android.net.Uri
import com.pockethost.app.embedded.EmbeddedPaths
import java.io.File

object ServerPaths {

    fun resolveSaf(context: Context, uriString: String): androidx.documentfile.provider.DocumentFile? {
        return try {
            val uri = Uri.parse(uriString)
            androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                ?: androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
        } catch (_: Exception) { null }
    }

    /** Р’Р±СѓРґРѕРІР°РЅРёР№ С€Р»СЏС… РґРѕ РїР°РїРєРё СЃРµСЂРІРµСЂР° (File). РџСЂР°С†СЋС” С– РґР»СЏ legacy SAF (РјС–РіСЂР°С†С–СЏ). */
    fun getWorkDir(context: Context, serverId: String, uriString: String): File =
        EmbeddedPaths.resolveWorkDir(context, serverId, uriString)

    fun getTermuxPath(context: Context, uriString: String): String {
        // Legacy РЅР°Р·РІР°: С‚РµРїРµСЂ РїРѕРІРµСЂС‚Р°С”РјРѕ РІР±СѓРґРѕРІР°РЅРёР№ Р°Р±СЃРѕР»СЋС‚РЅРёР№ С€Р»СЏС…
        return try {
            if (uriString.startsWith("embedded://")) {
                EmbeddedPaths.serverDir(context, uriString.removePrefix("embedded://")).absolutePath
            } else if (uriString.startsWith("external://")) {
                EmbeddedPaths.serverDir(
                    context,
                    uriString.removePrefix("external://"),
                    EmbeddedPaths.StorageKind.EXTERNAL
                ).absolutePath
            } else if (uriString.startsWith("content://")) {
                // РјС–РіСЂР°С†С–Р№РЅР° РїР°РїРєР° - РІРёР·РЅР°С‡РёС‚Рё serverId РЅРµРјРѕР¶Р»РёРІРѕ, РїРѕРІРµСЂС‚Р°С”РјРѕ СЏРє С” РґР»СЏ Р»РѕРіС–РІ
                uriString
            } else if (uriString.startsWith("/")) {
                uriString
            } else {
                EmbeddedPaths.serverDir(context, uriString).absolutePath
            }
        } catch (_: Exception) { uriString }
    }

    fun getDisplayPath(context: Context, uriString: String): String {
        return try {
            when {
                uriString.startsWith("embedded://") ->
                    EmbeddedPaths.displayPath(context, uriString.removePrefix("embedded://"))
                uriString.startsWith("external://") ->
                    EmbeddedPaths.displayPath(
                        context,
                        uriString.removePrefix("external://"),
                        EmbeddedPaths.StorageKind.EXTERNAL
                    )
                uriString.startsWith("content://") ->
                    com.pockethost.app.termux.SafHelper.getDisplayPath(context, Uri.parse(uriString)) + " [legacy]"
                uriString.startsWith("/") -> uriString
                else -> uriString
            }
        } catch (_: Exception) { uriString }
    }
}
