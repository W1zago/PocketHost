package com.pockethost.app.util

import android.util.Log
import java.io.File

/**
 * РўРѕС‡РєРѕРІРµ РѕРЅРѕРІР»РµРЅРЅСЏ server.properties.
 * РЎРїС–Р»СЊРЅРµ РґР»СЏ ServerService (РїР°С‚С‡ РїСЂРё РєРѕР¶РЅРѕРјСѓ СЃС‚Р°СЂС‚С–) С– ServerDetailActivity
 * (РјРёС‚С‚С”РІРµ Р·Р°СЃС‚РѕСЃСѓРІР°РЅРЅСЏ Р·РјС–РЅ РїРѕРІР·СѓРЅРєС–РІ РЅР° Р·СѓРїРёРЅРµРЅРѕРјСѓ СЃРµСЂРІРµСЂС–).
 * Р РµС€С‚Сѓ С„Р°Р№Р»Р° (motd, СЃС–Рґ, РіРµР№РјРјРѕРґ...) РЅРµ С‡С–РїР°С”.
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
            // РєР»СЋС‡С–РІ РЅРµ Р±СѓР»Рѕ (СЃС‚Р°СЂРёР№/СЂСѓС‡РЅРёР№ С„Р°Р№Р») вЂ” РґРѕРїРёСЃСѓС”РјРѕ
            for ((k, v) in wanted) {
                if (!seen.contains(k)) out.add("$k=$v")
            }
            file.writeText(out.joinToString("\n").trimEnd() + "\n")
        } catch (e: Exception) {
            Log.w("ServerProperties", "patch failed", e)
        }
    }
}
