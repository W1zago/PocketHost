package com.anserver.app.termux

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

object TermuxPaths {

    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val TERMUX_STORAGE_SHARED = "$TERMUX_HOME/storage/shared"

    /**
     * Convert SAF tree Uri (content://com.android.externalstorage.documents/tree/primary%3APocketHost%2Fmc-xxx)
     * to display path /storage/emulated/0/PocketHost/mc-xxx
     */
    fun safUriToDisplayPath(context: Context, uri: Uri): String {
        return SafHelper.getDisplayPath(context, uri)
    }

    /**
     * Convert SAF Uri to Termux absolute path for RUN_COMMAND_WORKDIR
     * content:// -> /data/data/com.termux/files/home/storage/shared/PocketHost/...
     */
    fun safToTermux(context: Context, uri: Uri): String {
        val display = SafHelper.getDisplayPath(context, uri)
        // display = /storage/emulated/0/PocketHost/mc-1
        val external = Environment.getExternalStorageDirectory().absolutePath // /storage/emulated/0
        val relative = if (display.startsWith(external)) {
            display.substring(external.length).trimStart('/')
        } else {
            // fallback: try to parse treeDocumentId
            try {
                val docId = DocumentsContract.getTreeDocumentId(uri)
                if (docId.startsWith("primary:")) docId.substringAfter("primary:") else docId
            } catch (_: Exception) { "" }
        }
        return if (relative.isEmpty()) TERMUX_STORAGE_SHARED else "$TERMUX_STORAGE_SHARED/$relative"
    }

    fun safStringToTermux(context: Context, uriString: String): String {
        return try {
            safToTermux(context, Uri.parse(uriString))
        } catch (_: Exception) {
            TERMUX_STORAGE_SHARED
        }
    }

    fun termuxPathToDisplay(termuxPath: String): String {
        // /data/data/com.termux/files/home/storage/shared/PocketHost/x -> /storage/emulated/0/PocketHost/x
        return if (termuxPath.startsWith(TERMUX_STORAGE_SHARED)) {
            val rel = termuxPath.substring(TERMUX_STORAGE_SHARED.length).trimStart('/')
            "${Environment.getExternalStorageDirectory().absolutePath}/$rel"
        } else termuxPath
    }
}
