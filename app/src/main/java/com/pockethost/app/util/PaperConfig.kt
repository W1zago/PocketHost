package com.pockethost.app.util

import android.util.Log
import java.io.File

/**
 * Paper warning silencer (harmless upstream defaults that spam every boot).
 *
 * - "The timings profiler has been enabled but has been scheduled for removal"
 *   Paper enables timings by default; on a phone it only costs CPU + log noise.
 *   Disabled here (paper.yml for <=1.20.4, paper-global.yml for newer).
 *   Existing user files are edited in place (only the timings.enabled key),
 *   never overwritten.
 */
object PaperConfig {

    fun disableTimings(workDir: File, log: (String) -> Unit = {}) {
        try {
            patchTimingsKey(File(workDir, "paper-global.yml"), log)
            patchTimingsKey(File(workDir, "paper.yml"), log)
        } catch (e: Exception) {
            Log.w("PaperConfig", "disableTimings failed", e)
        }
    }

    private fun patchTimingsKey(file: File, log: (String) -> Unit) {
        try {
            if (!file.isFile) return
            val lines = file.readLines()
            var changed = false
            val out = lines.map { line ->
                val trimmed = line.trimStart()
                if (!trimmed.startsWith("#") && trimmed.startsWith("enabled") &&
                    line.contains("true") && isUnderTimings(lines, line)
                ) {
                    changed = true
                    line.replace("true", "false")
                } else line
            }
            if (changed) {
                file.writeText(out.joinToString("\n").trimEnd() + "\n")
                log("Disabled Paper timings profiler (${file.name}) - removes the removal warning + saves CPU.")
            }
        } catch (_: Exception) {}
    }

    private fun isUnderTimings(all: List<String>, target: String): Boolean {
        return try {
            val idx = all.indexOf(target)
            if (idx < 0) return false
            // walk upwards for the nearest non-empty section header
            for (i in idx - 1 downTo maxOf(0, idx - 8)) {
                val t = all[i].trim()
                if (t.startsWith("timings")) return true
                if (t.isEmpty() || t.startsWith("#")) continue
                // another top-level key in between - not timings
                if (!all[i].startsWith(" ") && !all[i].startsWith("\t")) return false
            }
            false
        } catch (_: Exception) { false }
    }
}
