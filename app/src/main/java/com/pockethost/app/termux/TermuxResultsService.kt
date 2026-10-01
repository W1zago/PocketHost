package com.pockethost.app.termux

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TermuxResultsService : Service() {

    // H6: SupervisorJob + cancel РІ onDestroy С‰РѕР± РЅРµ РІРёС‚С–РєР°РІ scope.
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { scope.cancel() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { handleIntent(it) }
        return START_NOT_STICKY
    }

    private fun handleIntent(intent: Intent) {
        scope.launch {
            try {
                val stdout = intent.getStringExtra("com.termux.RUN_COMMAND_STDOUT") ?: ""
                val stderr = intent.getStringExtra("com.termux.RUN_COMMAND_STDERR") ?: ""
                val exitCode = intent.getIntExtra("com.termux.RUN_COMMAND_EXIT_CODE", -1)
                val executionId = intent.getIntExtra("com.termux.RUN_COMMAND_EXECUTION_ID", -1)

                Log.d("TermuxResults", "exit=$exitCode exec=$executionId stdout=${stdout.take(200)} stderr=${stderr.take(200)}")

                // Optionally write to a server.log if we can map workDir? For pkg/apt we don't have server mapping here.
                // This service currently just logs; ServerService will poll or handle specific pendingIntent extras for serverId.

                val serverId = intent.getStringExtra("server_id")
                val workDirUriStr = intent.getStringExtra("workDirUri")
                if (serverId != null && workDirUriStr != null) {
                    try {
                        val uri = Uri.parse(workDirUriStr)
                        val dir = DocumentFile.fromTreeUri(this@TermuxResultsService, uri)
                        val logFile = dir?.findFile("server.log") ?: dir?.createFile("text/plain", "server.log")
                        logFile?.let {
                            contentResolver.openOutputStream(it.uri, "wa")?.use { out ->
                                if (stdout.isNotEmpty()) out.write(stdout.toByteArray())
                                if (stderr.isNotEmpty()) out.write(("\n" + stderr).toByteArray())
                                out.write("\n[exit $exitCode]\n".toByteArray())
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("TermuxResults", "write log failed", e)
                    }
                }

            } catch (e: Exception) {
                Log.e("TermuxResults", "handle failed", e)
            } finally {
                stopSelf()
            }
        }
    }
}
