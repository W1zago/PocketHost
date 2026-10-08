package com.pockethost.jvmhost

import java.io.File
import java.io.RandomAccessFile

/**
 * Tiny CLI to exercise the local Ubuntu Server without any UI:
 *
 *   status          - host, backend readiness, running state, LAN urls
 *   install         - download/verify the Ubuntu image for this host
 *   start           - launch the Ubuntu/host shell in the background
 *   stop            - terminate the running shell (works across processes)
 *   run-cmd <cmd>  - run one command on the backend, wait, log the output
 *   shell           - attach an interactive console to the running shell
 *   log [lines]    - print the tail of server.log
 *   serve [port]   - shell + LAN HTTP bridge (what the phone connects to)
 *
 * Data lives under `~/.pockethost` (`%LOCALAPPDATA%/PocketHost` on Windows).
 * `start` returns once the shell is launched; use `log` to watch output.
 * `serve` blocks: Enter stops it in a terminal, kill stops it in background.
 */
fun main(args: Array<String>) {
    val baseDir = HostInfo.defaultBaseDir()
    val vm = ServerViewModel(baseDir, "ubuntu") { println(it) }
    val server = vm.server
    val cmd = args.getOrNull(0)?.lowercase() ?: "status"
    val exit = when (cmd) {
        "status" -> {
            val st = server.status()
            // Never trust a pid file blindly: a dead JVM leaves a stale
            // serve.pid behind that must not fake "bridge running".
            cleanStaleServePid(server.workDir()) { println(it) }
            println("Host: ${st.host.osName} (${st.host.osArch}) -> ${st.host.os}/${st.host.cpu}")
            println("Base dir: ${baseDir.absolutePath}")
            println("Work dir: ${st.workDir.absolutePath}")
            println("Ubuntu image ready: ${st.installed}")
            val sel = server.selectBackend(st.host)
            println("Selected backend: ${sel.backend}" + if (sel.degraded) " (DEGRADED)" else "")
            if (sel.degraded && sel.fallbackReason != null) {
                println("Fallback reason: ${sel.fallbackReason}")
            }
            if (st.host.os == OsKind.LINUX) {
                val proot = HostInfo.systemProot()
                println("proot: ${proot?.absolutePath ?: "missing (sudo apt install proot)"}")
            }
            if (st.host.os == OsKind.WINDOWS) {
                println("WSL distros: ${HostInfo.wslDistros().ifEmpty { listOf("<none>") }}")
            }
            println("Running: ${st.running}" + (st.pid?.let { " (pid $it)" } ?: ""))
            val servePid = readServePid(server.workDir())
            val serveAlive = servePid?.let { isPidAlive(it) } ?: false
            println("Bridge: ${if (serveAlive) "running (serve pid $servePid)" else "stopped"}")
            val ips = LanInfo.lanIPv4()
            if (ips.isEmpty()) {
                println("LAN IP: <none> (phone must share the network)")
            } else {
                println("LAN IP: ${ips.joinToString(", ")}")
            }
            val portFile = bridgePortFile(server.workDir())
            val port = try {
                portFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
            } catch (_: Exception) {
                null
            }
            if (serveAlive && port != null) {
                for (ip in ips) println("URL: http://$ip:$port/api/status")
            }
            0
        }
        "install" -> if (server.install()) 0 else 1
        "start" -> if (server.start()) {
            println("Shell launched. Streaming log, press Enter to stop.")
            streamLogUntilEnter(server)
            server.stop()
            println("Stopped.")
            0
        } else {
            1
        }
        "stop" -> {
            var stopped = false
            // A background `serve` lives in another JVM: stop it by pid file.
            val servePid = readServePid(server.workDir())
            if (servePid != null && servePid != ProcessHandle.current().pid()) {
                if (isPidAlive(servePid)) {
                    println("Stopping background serve (pid $servePid)...")
                    try {
                        ProcessHandle.of(servePid).ifPresent { h ->
                            h.destroy()
                            try {
                                h.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS)
                            } catch (_: Exception) {
                                try {
                                    h.destroyForcibly()
                                } catch (_: Exception) {
                                }
                            }
                        }
                        stopped = true
                    } catch (e: Exception) {
                        println("Serve stop error: ${e.message}")
                    }
                }
                deleteServePid(server.workDir())
            } else if (servePid != null) {
                deleteServePid(server.workDir())
            }
            if (vm.stopBridge()) stopped = true
            if (server.stop()) stopped = true
            if (stopped) 0 else {
                println("Nothing running.")
                1
            }
        }
        "serve" -> {
            val rest = args.drop(1)
            val allowDegraded = rest.any { it.equals("allow-degraded", ignoreCase = true) || it == "--allow-degraded" }
            val port = rest.firstOrNull()?.toIntOrNull() ?: BridgeServer.DEFAULT_PORT
            serveCommand(vm, port, allowDegraded)
        }
        "run-cmd" -> {
            if (args.size < 2) {
                println("Usage: run-cmd <command...>")
                return
            }
            server.runOneShot(args.drop(1).joinToString(" "))
        }
        "shell" -> attachShell(server)
        "log" -> {
            val lines = args.getOrNull(1)?.toIntOrNull() ?: 200
            val tail = server.tailLog(lines)
            if (tail.isEmpty()) println("(empty log)") else println(tail)
            0
        }
        else -> {
            println("Unknown command: $cmd")
            println("Usage: status | install | start | stop | run-cmd <cmd...> | shell | log [lines] | serve [port]")
            2
        }
    }
    // Log-pump threads are daemon threads, so the CLI exits normally.
    kotlin.system.exitProcess(exit)
}

/**
 * `serve [port]`: the "Ubuntu Server online" path shared with the future
 * PC UI ([ServerViewModel.serve]): shell first, then the LAN HTTP bridge
 * the phone connects to. Blocks until Enter in a terminal, or until the
 * process is killed when run in the background (stdin closed).
 */
private fun serveCommand(vm: ServerViewModel, port: Int, allowDegraded: Boolean = false): Int {
    val server = vm.server
    // Drop a dead serve.pid before anything else so a previous crash
    // can never fake a live bridge.
    cleanStaleServePid(server.workDir()) { println(it) }
    if (!vm.serve(port, allowDegraded)) {
        println("Serve failed. See lines above.")
        return 1
    }
    val actual = vm.bridge.actualPort ?: port
    writeServePid(server.workDir(), ProcessHandle.current().pid())
    writeBridgePort(server.workDir(), actual)
    Runtime.getRuntime().addShutdownHook(Thread({
        try {
            vm.serveStop()
        } catch (_: Exception) {
        }
        try {
            deleteServePid(server.workDir())
        } catch (_: Exception) {
        }
    }, "jvmhost-serve-shutdown"))

    println("Ubuntu Server ONLINE (backend=${server.selectBackend(HostInfo.current()).backend}).")
    for (url in vm.lanUrls()) println("PHONE URL: $url")
    println("Phone test (same Wi-Fi): curl ${vm.lanUrls().firstOrNull() ?: "http://127.0.0.1:$actual/api/status"}")

    if (System.console() != null) {
        println("Streaming log, press Enter to stop.")
        streamLogUntilEnter(server)
        vm.serveStop()
        deleteServePid(server.workDir())
        println("Stopped.")
    } else {
        // Background/daemon: park until killed (shutdown hook cleans up).
        println("Running in background (no console). Kill the process to stop.")
        try {
            while (vm.bridge.isRunning()) Thread.sleep(5000)
        } catch (_: InterruptedException) {
        }
        vm.serveStop()
        deleteServePid(server.workDir())
    }
    return 0
}

private fun servePidFile(workDir: File): File = File(workDir, "serve.pid")

private fun bridgePortFile(workDir: File): File = File(workDir, "bridge.port")

private fun readServePid(workDir: File): Long? = try {
    servePidFile(workDir).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
} catch (_: Exception) {
    null
}

/**
 * Drops a stale `serve.pid` left by a dead JVM so it can never fake
 * a live bridge. Returns true when a stale file was cleaned.
 * A live pid is left untouched (no false "cleaned").
 */
fun cleanStaleServePid(workDir: File, log: (String) -> Unit = {}): Boolean {
    return try {
        val f = servePidFile(workDir)
        if (!f.isFile) return false
        val pid = f.readText().trim().toLongOrNull() ?: run {
            try {
                f.delete()
                bridgePortFile(workDir).delete()
            } catch (_: Exception) {
            }
            log("stale pid cleaned (was unreadable)")
            return true
        }
        if (isPidAlive(pid)) return false
        try {
            f.delete()
            bridgePortFile(workDir).delete()
        } catch (_: Exception) {
        }
        log("stale pid cleaned (was $pid)")
        true
    } catch (_: Exception) {
        false
    }
}

private fun writeServePid(workDir: File, pid: Long) {
    try {
        workDir.mkdirs()
        servePidFile(workDir).writeText(pid.toString())
    } catch (_: Exception) {
    }
}

private fun deleteServePid(workDir: File) {
    try {
        servePidFile(workDir).delete()
    } catch (_: Exception) {
    }
    try {
        bridgePortFile(workDir).delete()
    } catch (_: Exception) {
    }
}

private fun writeBridgePort(workDir: File, port: Int) {
    try {
        workDir.mkdirs()
        bridgePortFile(workDir).writeText(port.toString())
    } catch (_: Exception) {
    }
}

private fun isPidAlive(pid: Long): Boolean = try {
    ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
} catch (_: Exception) {
    false
}

/**
 * Attaches the console to the running shell: prints new log output live
 * and forwards typed lines to the shell stdin. Ends on `exit` or EOF,
 * then stops the server.
 */
private fun attachShell(server: UbuntuServer): Int {
    if (!server.processes.isRunning(server.serverId, server.workDir())) {
        println("Server is not running, starting it...")
        if (!server.start()) return 1
    }
    println("Attached to '${server.serverId}'. Type commands, `exit` or EOF to detach and stop.")
    // The shell needs a moment to start; hold stdin until its first output
    // so piped/typed commands do not vanish into the startup gap.
    waitForFirstOutput(server, 15_000L)
    var done = false
    val tailer = streamLogLive(server) { done }

    val reader = System.`in`.bufferedReader()
    while (!done) {
        val line = try {
            reader.readLine()
        } catch (_: Exception) {
            null
        } ?: break
        if (line.trim() == "exit") break
        if (!server.exec(line)) {
            println("Server is not running.")
            break
        }
    }
    done = true
    try {
        tailer.join(1000)
    } catch (_: Exception) {
    }
    server.stop()
    println("Detached.")
    return 0
}

/**
 * Streams new `server.log` bytes to stdout until [isDone] returns true.
 * Returns the tailer thread so the caller can join it.
 */
private fun streamLogLive(server: UbuntuServer, isDone: () -> Boolean): Thread {
    val logFile = File(server.workDir(), LocalProcessManager.LOG_NAME)
    var offset = try {
        logFile.length()
    } catch (_: Exception) {
        0L
    }
    return Thread({
        while (!isDone()) {
            try {
                val len = if (logFile.isFile) logFile.length() else 0L
                if (len > offset) {
                    RandomAccessFile(logFile, "r").use { raf ->
                        raf.seek(offset)
                        val buf = ByteArray((len - offset).coerceAtMost(256 * 1024).toInt())
                        val n = raf.read(buf)
                        if (n > 0) print(String(buf, 0, n, Charsets.UTF_8))
                    }
                    offset = len
                } else if (len < offset) {
                    offset = len // log was rotated
                }
            } catch (_: Exception) {
            }
            try {
                Thread.sleep(300)
            } catch (_: Exception) {
                break
            }
        }
    }, "jvmhost-cli-tail").apply { isDaemon = true; start() }
}

/**
 * Waits until the shell prints its first output (prompt/banner) or
 * [timeoutMs] elapses. Returns immediately when the process dies.
 */
private fun waitForFirstOutput(server: UbuntuServer, timeoutMs: Long) {
    val logFile = File(server.workDir(), LocalProcessManager.LOG_NAME)
    val startLen = try {
        logFile.length()
    } catch (_: Exception) {
        0L
    }
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        try {
            if (logFile.isFile && logFile.length() > startLen) return
            if (!server.processes.isRunning(server.serverId, server.workDir())) return
        } catch (_: Exception) {
        }
        try {
            Thread.sleep(200)
        } catch (_: Exception) {
            return
        }
    }
    println("(shell is slow to answer, commands may take a moment)")
}

/** Blocks until Enter/EOF, streaming the log meanwhile. */
private fun streamLogUntilEnter(server: UbuntuServer) {    var done = false
    val tailer = streamLogLive(server) { done }
    try {
        System.`in`.bufferedReader().readLine()
    } catch (_: Exception) {
    }
    done = true
    try {
        tailer.join(1000)
    } catch (_: Exception) {
    }
}
