package com.anserver.app.termux

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

object SafHelper {

    fun isOnPrimaryStorage(context: Context, uri: Uri): Boolean {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            docId.startsWith("primary:")
        } catch (_: Exception) {
            false
        }
    }

    fun getDisplayPath(context: Context, uri: Uri): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val relative = if (docId.contains(":")) docId.substringAfter(":") else docId
            "${Environment.getExternalStorageDirectory().absolutePath}/$relative"
        } catch (_: Exception) {
            uri.toString()
        }
    }

    fun hasPersistedPermission(context: Context, treeUri: Uri): Boolean {
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && it.isWritePermission
        }
    }

    fun hasAnyPersistedForUriString(context: Context, uriString: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)
            context.contentResolver.persistedUriPermissions.any {
                uriString.startsWith(it.uri.toString()) && it.isReadPermission && it.isWritePermission
            }
        } catch (_: Exception) { false }
    }

    fun persistPermission(context: Context, uri: Uri) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {}
    }

    fun createSubDir(context: Context, treeUri: Uri, dirName: String): Uri? {
        return try {
            val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            if (!tree.canWrite()) return null
            // Check if already exists
            tree.findFile(dirName)?.let { return it.uri }
            val sub = tree.createDirectory(dirName) ?: return null
            sub.uri
        } catch (_: Exception) { null }
    }

    fun validatePrimaryWritableEmpty(context: Context, uri: Uri): ValidationResult {
        try {
            if (!isOnPrimaryStorage(context, uri)) return ValidationResult(false, "Виберіть папку у внутрішній пам'яті")
            val doc = DocumentFile.fromTreeUri(context, uri) ?: return ValidationResult(false, "Не вдалося відкрити папку")
            if (!doc.exists()) return ValidationResult(false, "Папка не існує")
            if (!doc.canWrite()) return ValidationResult(false, "Нема запису")
            if (doc.listFiles().isNotEmpty()) return ValidationResult(false, "Папка не порожня")
            return ValidationResult(true, "")
        } catch (e: Exception) {
            return ValidationResult(false, e.message ?: "Помилка перевірки")
        }
    }

    fun isEmpty(context: Context, uri: Uri): Boolean {
        return try {
            val doc = DocumentFile.fromTreeUri(context, uri) ?: return true
            doc.listFiles().isEmpty()
        } catch (_: Exception) { true }
    }

    fun sanitizeName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(32).lowercase().ifEmpty { "server" }
    }

    data class ValidationResult(val ok: Boolean, val message: String)
}
