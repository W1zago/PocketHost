package com.pockethost.jvmhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerViewModelTest {

    @Test
    fun initialStateReflectsHost() {
        val tmp = java.nio.file.Files.createTempDirectory("pockethost-vm").toFile()
        try {
            val vm = ServerViewModel(tmp, "vm-test")
            val s = vm.state()
            assertNotNull(s.host)
            assertFalse(s.shellRunning)
            assertFalse(s.bridgeRunning)
            assertTrue(s.workDir.absolutePath.isNotBlank())
        } finally {
            try {
                tmp.deleteRecursively()
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun listenerGetsInitialAndRefresh() {
        val tmp = java.nio.file.Files.createTempDirectory("pockethost-vm-l").toFile()
        try {
            val vm = ServerViewModel(tmp, "vm-listener")
            var calls = 0
            var last: ServerViewModel.VmState? = null
            vm.addListener { st ->
                calls++
                last = st
            }
            assertEquals(1, calls)
            assertNotNull(last)
            vm.refresh()
            assertEquals(2, calls)
        } finally {
            try {
                tmp.deleteRecursively()
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun serveStopWithoutServeIsSafe() {
        val tmp = java.nio.file.Files.createTempDirectory("pockethost-vm-s").toFile()
        try {
            val vm = ServerViewModel(tmp, "vm-safe")
            // Nothing started: stop paths must not throw.
            assertFalse(vm.stopBridge())
            vm.refresh()
            assertFalse(vm.state().bridgeRunning)
        } finally {
            try {
                tmp.deleteRecursively()
            } catch (_: Exception) {
            }
        }
    }
}
