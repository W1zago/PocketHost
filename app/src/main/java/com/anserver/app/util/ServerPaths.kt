package com.anserver.app.util

import android.content.Context
import java.io.File

object ServerPaths {

    fun resolve(context: Context, path: String): File {
        val file = File(path)
        return if (file.isAbsolute) file else File(context.filesDir, path)
    }
}
