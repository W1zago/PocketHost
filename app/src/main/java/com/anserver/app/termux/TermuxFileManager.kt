package com.anserver.app.termux

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

data class DocItem(
    val name: String?,
    val isDirectory: Boolean,
    val length: Long,
    val lastModified: Long,
    val uri: Uri
)

object TermuxFileManager {

    fun listFiles(context: Context, treeUri: Uri, subPath: String? = null): List<DocItem> {
        val target = resolve(context, treeUri, subPath) ?: return emptyList()
        return target.listFiles().map {
            DocItem(it.name, it.isDirectory, it.length(), it.lastModified(), it.uri)
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name?.lowercase() }))
            .take(500)
    }

    fun listFilesForDoc(context: Context, docUri: Uri): List<DocItem> {
        val doc = DocumentFile.fromTreeUri(context, docUri) ?: DocumentFile.fromSingleUri(context, docUri) ?: return emptyList()
        if (doc.isDirectory) {
            return doc.listFiles().map { DocItem(it.name, it.isDirectory, it.length(), it.lastModified(), it.uri) }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name?.lowercase() }))
                .take(500)
        }
        return emptyList()
    }

    private fun resolve(context: Context, treeUri: Uri, subPath: String?): DocumentFile? {
        var current = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        if (subPath != null) {
            subPath.split("/").filter { it.isNotEmpty() }.forEach { part ->
                current = current.findFile(part) ?: return null
            }
        }
        return current
    }

    fun readFile(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
        } catch (_: Exception) { null }
    }

    fun writeFile(context: Context, uri: Uri, content: String): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray()) }
            true
        } catch (_: Exception) { false }
    }

    fun createFile(context: Context, dirUri: Uri, name: String, mime: String = "text/plain"): Uri? {
        return try {
            val dir = DocumentFile.fromTreeUri(context, dirUri) ?: return null
            dir.createFile(mime, name)?.uri
        } catch (_: Exception) { null }
    }

    fun delete(context: Context, uri: Uri): Boolean {
        return try {
            val doc = DocumentFile.fromTreeUri(context, uri) ?: DocumentFile.fromSingleUri(context, uri) ?: return false
            doc.delete()
        } catch (_: Exception) { false }
    }
}
