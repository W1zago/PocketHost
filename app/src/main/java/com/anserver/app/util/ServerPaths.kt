package com.anserver.app.util

import android.content.Context
import android.net.Uri
import com.anserver.app.embedded.EmbeddedPaths
import java.io.File

object ServerPaths {

    fun resolveSaf(context: Context, uriString: String): androidx.documentfile.provider.DocumentFile? {
        return try {
            val uri = Uri.parse(uriString)
            androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                ?: androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
        } catch (_: Exception) { null }
    }

    /** Вбудований шлях до папки сервера (File). Працює і для legacy SAF (міграція). */
    fun getWorkDir(context: Context, serverId: String, uriString: String): File =
        EmbeddedPaths.resolveWorkDir(context, serverId, uriString)

    fun getTermuxPath(context: Context, uriString: String): String {
        // Legacy назва: тепер повертаємо вбудований абсолютний шлях
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
                // міграційна папка - визначити serverId неможливо, повертаємо як є для логів
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
                    com.anserver.app.termux.SafHelper.getDisplayPath(context, Uri.parse(uriString)) + " [legacy]"
                uriString.startsWith("/") -> uriString
                else -> uriString
            }
        } catch (_: Exception) { uriString }
    }
}
