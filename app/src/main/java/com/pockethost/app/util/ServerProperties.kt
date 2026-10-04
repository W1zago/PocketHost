package com.pockethost.app.util

import android.util.Log
import java.io.File

/**
 * Targeted server.properties updates.
 * Shared by ServerService (patch on every start) and ServerDetailActivity
 * (instant slider changes applied on a stopped server).
 * Leaves the rest of the file (motd, seed, gamemode...) alone.
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
        val wanted = mapOf(
            "server-port" to port.toString(),
            "online-mode" to onlineMode.toString(),
            "view-distance" to vd.toString()
        )
        if (!file.exists()) {
            file.writeText(
                "server-port=$port\nmax-players=20\ngamemode=survival\ndifficulty=normal\n" +
                    "pvp=true\nonline-mode=$onlineMode\nmotd=A Minecraft Server - $serverName\nview-distance=$vd\n" +
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
}
