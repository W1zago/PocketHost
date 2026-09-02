package com.anserver.app.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.anserver.app.termux.TermuxPaths

object ServerPaths {

    fun resolveSaf(context: Context, uriString: String): DocumentFile? {
        return try {
            val uri = Uri.parse(uriString)
            DocumentFile.fromTreeUri(context, uri) ?: DocumentFile.fromSingleUri(context, uri)
        } catch (_: Exception) { null }
    }

    fun getTermuxPath(context: Context, uriString: String): String {
        return TermuxPaths.safStringToTermux(context, uriString)
    }

    fun getDisplayPath(context: Context, uriString: String): String {
        return try {
            TermuxPaths.safUriToDisplayPath(context, Uri.parse(uriString))
        } catch (_: Exception) { uriString }
    }

    @Deprecated("Use resolveSaf for SAF")
    fun resolve(context: Context, path: String): java.io.File {
        val file = java.io.File(path)
        return if (file.isAbsolute) file else java.io.File(context.filesDir, path)
    }
}
