package com.pockethost.app.util

import java.io.File

/**
 * Paper/Spigot override guard for the view-distance slider.
 *
 * `spigot.yml` -> `world-settings.<world>.view-distance` (and since 1.18 also
 * `simulation-distance`) shadows `server.properties` when numeric: the server
 * then ignores the slider value entirely. The sentinel `default` means
 * "follow server.properties" (this is what Paper ships by default).
 * Same for Purpur's `purpur.yml` world-settings.
 *
 * Only numeric values are reset to `default`; everything else (comments,
 * other keys, missing file) is left alone. Never creates yml files -
 * Paper generates them on first boot with `default` already.
 */
object SpigotConfig {

    fun ensureFollowsServerProperties(workDir: File, log: (String) -> Unit = {}): Boolean {
        var any = false
        if (resetFile(File(workDir, "spigot.yml"), log)) any = true
        if (resetFile(File(workDir, "purpur.yml"), log)) any = true
        return any
    }

    private fun resetFile(file: File, log: (String) -> Unit): Boolean {
        if (!file.isFile) return false
        return try {
            val lines = file.readLines()
            var changed = 0
            val out = lines.map { line ->
                val trimmed = line.trimStart()
                if (!trimmed.startsWith("#") &&
                    (trimmed.startsWith("view-distance:") || trimmed.startsWith("simulation-distance:"))
                ) {
                    val value = trimmed.substringAfter(":").trim()
                    if (value.toIntOrNull() != null) {
                        changed++
                        line.substringBefore(":") + ": default"
                    } else line
                } else line
            }
            if (changed > 0) {
                file.writeText(out.joinToString("\n").trimEnd() + "\n")
                log("${file.name}: view/simulation-distance reset to 'default' ($changed entries) - the slider (server.properties) is authoritative again.")
                true
            } else false
        } catch (_: Exception) { false }
    }
}
