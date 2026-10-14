package com.pockethost.app.util

import android.util.Log
import java.io.File

/**
 * Targeted server.properties updates.
 * Shared by ServerService (patch on every start) and ServerDetailActivity
 * (instant slider changes applied on a stopped server).
 * Leaves the rest of the file (motd, seed, gamemode...) alone.
 *
 * The view-distance slider controls BOTH view-distance and simulation-distance:
 * since 1.18 the server has two radii (visible vs ticked). Patching only
 * view-distance leaves simulation at the old 10, so far chunks stay frozen
 * and the slider looks "not working".
 */
object ServerProperties {

    fun patch(
        workDir: File,
        port: Int,
        onlineMode: Boolean,
        viewDistance: Int,
        serverName: String
    ) {
        val vd = viewDistance.coerceIn(2, 32)
        val file = File(workDir, "server.properties")
        // Offline (pirate) mode with enforce-secure-profile=true breaks chat
        // signing on 1.19.1+: Mojang chat reports require online auth.
        // Force it off when online-mode=false, restore true when online.
        val secureProfile = onlineMode.toString()
        val wanted = mutableMapOf(
            "server-port" to port.toString(),
            "online-mode" to onlineMode.toString(),
            "view-distance" to vd.toString(),
            "simulation-distance" to vd.toString()
        )
        // Only enforce when the key already exists or we create the file:
        // appending it to a foreign manual file is harmless and fixes chat.
        wanted["enforce-secure-profile"] = secureProfile
        if (!file.exists()) {
            file.writeText(
                "server-port=$port\nmax-players=20\ngamemode=survival\ndifficulty=normal\n" +
                    "pvp=true\nonline-mode=$onlineMode\nenforce-secure-profile=$secureProfile\nmotd=A Minecraft Server - $serverName\nview-distance=$vd\nsimulation-distance=$vd\n" +
                    "spawn-protection=16\nenable-command-block=false\nallow-flight=false\nwhite-list=false\nenforce-whitelist=false\n"
            )
            return
        }
        try {
            val lines = file.readLines()
            val seen = mutableSetOf<String>()
            val out = lines.map { line ->
                val key = line.substringBefore('=').trim()
                if (wanted.containsKey(key) && !line.trimStart().startsWith("#")) {
                    seen.add(key)
                    "$key=${wanted[key]}"
                } else line
            }.toMutableList()
            // keys were missing (old/manual file) - append them
            for ((k, v) in wanted) {
                if (!seen.contains(k)) out.add("$k=$v")
            }
            file.writeText(out.joinToString("\n").trimEnd() + "\n")
        } catch (e: Exception) {
            Log.w("ServerProperties", "patch failed", e)
        }
    }

    /** Reads back the actual view-distance from server.properties (null = missing/unparseable). */
    fun readViewDistance(workDir: File): Int? {
        return readKey(workDir, "view-distance")
    }

    /** Reads back the actual simulation-distance (null = missing/unparseable, e.g. old file). */
    fun readSimulationDistance(workDir: File): Int? {
        return readKey(workDir, "simulation-distance")
    }

    private fun readKey(workDir: File, key: String): Int? {
        return try {
            val file = File(workDir, "server.properties")
            if (!file.isFile) return null
            file.readLines()
                .filter { !it.trimStart().startsWith("#") && it.contains("=") }
                .map { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
                .firstOrNull { it.first == key }
                ?.second?.toIntOrNull()
        } catch (_: Exception) { null }
    }
}
