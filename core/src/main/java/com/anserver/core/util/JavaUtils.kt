package com.anserver.core.util

import java.io.File

object JavaUtils {
    /**
     * Find available Java executable on Android device
     * Returns null if no Java runtime is found
     */
    fun findJavaExecutable(): String? {
        val possiblePaths = listOf(
            // Termux Java
            "/data/data/com.termux/files/usr/bin/java",
            // Custom installed Java in app's private directory
            "/data/data/com.anserver.app/files/java/bin/java",
            // System-wide Java (rare on Android)
            "/system/bin/java",
            "/system/xbin/java",
            // Check if java is in PATH
            "java"
        )

        for (path in possiblePaths) {
            if (isJavaExecutable(path)) {
                return path
            }
        }

        return null
    }

    /**
     * Check if the given path is a valid Java executable
     */
    private fun isJavaExecutable(path: String): Boolean {
        return try {
            val file = File(path)
            if (path == "java") {
                // Check if java is in PATH by trying to execute it
                val process = Runtime.getRuntime().exec(arrayOf("which", "java"))
                val result = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                result.isNotEmpty()
            } else {
                file.exists() && file.canExecute()
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get Java version from the executable
     */
    fun getJavaVersion(javaPath: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf(javaPath, "-version"))
            val error = process.errorStream.bufferedReader().readText()
            process.waitFor()

            // Java version is typically in stderr
            val versionLine = error.lines().firstOrNull() ?: return null
            versionLine
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if Java is available and return its path with version info
     */
    fun checkJavaAvailability(): JavaInfo? {
        val javaPath = findJavaExecutable() ?: return null
        val version = getJavaVersion(javaPath)
        return JavaInfo(javaPath, version)
    }

    data class JavaInfo(
        val path: String,
        val version: String?
    )
}
