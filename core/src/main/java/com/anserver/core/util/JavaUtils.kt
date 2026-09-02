package com.anserver.core.util

object JavaUtils {
    fun parseMajorVersion(versionString: String?): Int? {
        if (versionString == null) return null
        val regex = Regex("\"(\\d+)(?:\\.(\\d+))?.*\"")
        val match = regex.find(versionString) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        return if (major == 1) {
            match.groupValues[2].toIntOrNull() ?: major
        } else major
    }
}
