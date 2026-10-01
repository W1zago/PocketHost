package com.pockethost.core.util

object JavaUtils {
    private val VERSION_REGEX = Regex("\"(\\d+)(?:\\.(\\d+))?.*\"")

    fun parseMajorVersion(versionString: String?): Int? {
        if (versionString == null) return null
        val match = VERSION_REGEX.find(versionString) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        return if (major == 1) {
            match.groupValues[2].toIntOrNull() ?: major
        } else major
    }
}
