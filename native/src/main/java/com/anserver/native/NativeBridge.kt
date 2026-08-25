package com.anserver.native

import com.anserver.core.model.ProcessInfo
import com.anserver.core.model.SystemStats

object NativeBridge {

    init {
        System.loadLibrary("anserver_native")
    }

    external fun startProcess(
        command: String,
        workingDir: String,
        environment: Array<String>
    ): Int

    external fun stopProcess(pid: Int): Boolean

    external fun killProcess(pid: Int): Boolean

    external fun isProcessAlive(pid: Int): Boolean

    external fun getProcessInfo(pid: Int): ProcessInfo?

    external fun getSystemStats(): SystemStats

    external fun getCpuUsage(): Float

    external fun getMemoryInfo(): LongArray

    external fun getNetworkStats(): LongArray

    external fun getActivePorts(): IntArray

    external fun bindPort(port: Int, protocol: String): Boolean

    external fun unbindPort(port: Int): Boolean

    external fun isPortOpen(port: Int): Boolean

    external fun executeCommand(command: String): String

    external fun readProcessOutput(pid: Int): String

    external fun writeProcessInput(pid: Int, data: String): Boolean

    external fun setEnvironmentVariable(key: String, value: String): Boolean

    external fun getEnvironmentVariable(key: String): String?

    external fun initPRoot(rootPath: String): Boolean

    external fun mountDirectory(source: String, target: String): Boolean

    external fun unmountDirectory(target: String): Boolean

    external fun changeRoot(newRoot: String): Boolean

    external fun createIsolatedEnvironment(name: String, rootPath: String): Boolean

    external fun destroyIsolatedEnvironment(name: String): Boolean
}
