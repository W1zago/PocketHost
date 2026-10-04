package com.pockethost.app.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MotdGenerator {

    fun generate(context: Context, workingDir: File, deviceIp: String): String {
        val kernel = readKernelVersion()
        val arch = readArch()
        val load = readLoadAvg()
        val temp = readTemperature()
        val (diskUsedPct, diskTotalGb) = readDiskUsage(workingDir)
        val procCount = readProcessCount()
        val (memPct, swapPct) = readMemoryUsage()
        val now = Date()
        val sysInfoDate = SimpleDateFormat("EEEE, d MMMM yyyy HH:mm:ss Z", Locale("uk", "UA")).format(now)
        val lastLogin = SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy", Locale.US).format(now)

        val sb = StringBuilder()
        sb.appendLine("Welcome to Ubuntu 22.04.5 LTS (GNU/Linux $kernel $arch)")
        sb.appendLine()
        sb.appendLine(" * Documentation:  https://help.ubuntu.com")
        sb.appendLine(" * Management:     https://landscape.canonical.com")
        sb.appendLine(" * Support:        https://ubuntu.com/pro")
        sb.appendLine()
        sb.appendLine(" System information as of $sysInfoDate")
        sb.appendLine()
        sb.appendLine(String.format(Locale.US, "  %-13s %-23s %-22s %s", "System load:", load, "Temperature:", "${temp} C"))
        sb.appendLine(String.format(Locale.US, "  %-13s %-23s %-22s %s", "Usage of /:", String.format(Locale.US, "%.1f%% of %.2fGB", diskUsedPct, diskTotalGb), "Processes:", procCount.toString()))
        sb.appendLine(String.format(Locale.US, "  %-13s %-23s %-22s %s", "Memory usage:", "$memPct%", "Users logged in:", "1"))
        sb.appendLine(String.format(Locale.US, "  %-13s %-23s %-22s %s", "Swap usage:", "$swapPct%", "IPv4 address for wlan0:", deviceIp))
        sb.appendLine()
        sb.appendLine(" * Strictly confined Kubernetes makes edge and IoT secure. Learn how MicroK8s")
        sb.appendLine("   just raised the bar for easy, resilient and secure K8s cluster deployment.")
        sb.appendLine()
        sb.appendLine("   https://ubuntu.com/engage/secure-kubernetes-at-the-edge")
        sb.appendLine()
        sb.appendLine("Expanded Security Maintenance for Applications is not enabled.")
        sb.appendLine()
        sb.appendLine("19 updates can be applied immediately.")
        sb.appendLine("1 of these updates is a standard security update.")
        sb.appendLine("To see the list of additional updates, issue the command")
        sb.appendLine(" apt list --upgradable")
        sb.appendLine()
        sb.appendLine("12 additional security updates can be applied with ESM Apps.")
        sb.appendLine("Learn more about enabling ESM Apps service at https://ubuntu.com/esm")
        sb.appendLine()
        sb.appendLine("New release '24.04.4 LTS' available.")
        sb.appendLine("Run 'do-release-upgrade' to upgrade to it.")
        sb.appendLine()
        sb.appendLine("*** System restart required ***")
        sb.appendLine("Activate the web console with: systemctl enable --now cockpit.socket")
        sb.appendLine()
        sb.appendLine("Last login: $lastLogin from ${lastLoginIp(deviceIp)}")
        return sb.toString()
    }

    private fun readKernelVersion(): String {
        return try {
            File("/proc/version").readText().split(" ").getOrNull(2) ?: "5.15.0-164-generic"
        } catch (_: Exception) {
            "5.15.0-164-generic"
        }
    }

    private fun readArch(): String {
        return try {
            val result = Runtime.getRuntime().exec("uname -m").inputStream.bufferedReader().readText().trim()
            if (result.isNotEmpty()) result else "x86_64"
        } catch (_: Exception) {
            "x86_64"
        }
    }

    private fun readLoadAvg(): String {
        return try {
            File("/proc/loadavg").readText().split(" ").firstOrNull() ?: "0.21"
        } catch (_: Exception) {
            "0.21"
        }
    }

    private fun readTemperature(): String {
        val candidates = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp"
        )
        for (path in candidates) {
            try {
                val raw = File(path).readText().trim().toLongOrNull() ?: continue
                val c = if (raw > 1000) raw / 1000.0 else raw.toDouble()
                return String.format(Locale.US, "%.1f", c)
            } catch (_: Exception) {
            }
        }
        return "45.0"
    }

    private fun readDiskUsage(dir: File): Pair<Double, Double> {
        return try {
            val total = dir.totalSpace.toDouble() / (1024 * 1024 * 1024)
            val free = dir.usableSpace.toDouble() / (1024 * 1024 * 1024)
            val used = total - free
            val pct = if (total > 0) used * 100 / total else 51.9
            val safeTotal = if (total < 1) 64.0 else total
            Pair(pct, safeTotal)
        } catch (_: Exception) {
            Pair(51.9, 64.0)
        }
    }

    private fun readProcessCount(): Int {
        return try {
            File("/proc").listFiles()?.count { it.isDirectory && it.name.all { c -> c.isDigit() } } ?: 204
        } catch (_: Exception) {
            204
        }
    }

    private fun readMemoryUsage(): Pair<Int, Int> {
        return try {
            val lines = File("/proc/meminfo").readLines()
            fun kb(key: String): Long {
                val line = lines.firstOrNull { it.startsWith(key) } ?: return 0
                return line.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: 0
            }
            val total = kb("MemTotal:").toDouble()
            val avail = kb("MemAvailable:").toDouble()
            val memPct = if (total > 0) ((total - avail) * 100 / total).toInt() else 15
            val swapTotal = kb("SwapTotal:").toDouble()
            val swapFree = kb("SwapFree:").toDouble()
            val swapPct = if (swapTotal > 0) ((swapTotal - swapFree) * 100 / swapTotal).toInt() else 3
            Pair(memPct.coerceIn(1, 99), swapPct.coerceIn(0, 99))
        } catch (_: Exception) {
            Pair(15, 3)
        }
    }

    private fun lastLoginIp(deviceIp: String): String {
        return try {
            val parts = deviceIp.split(".")
            if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.${(90..99).random()}" else deviceIp
        } catch (_: Exception) {
            deviceIp
        }
    }
}
