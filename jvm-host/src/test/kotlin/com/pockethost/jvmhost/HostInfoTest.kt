package com.pockethost.jvmhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostInfoTest {

    @Test
    fun currentHostIsDetected() {
        val host = HostInfo.current()
        // The test JVM always reports a real OS/arch pair.
        assertNotNull(host.os)
        assertNotNull(host.cpu)
        assertTrue(host.osName.isNotBlank())
        assertTrue(host.osArch.isNotBlank())
    }

    @Test
    fun defaultBaseDirIsAbsolute() {
        val dir = HostInfo.defaultBaseDir()
        assertTrue(dir.isAbsolute)
        assertTrue(dir.path.isNotBlank())
    }

    @Test
    fun javaExecutableIsFoundOnPath() {
        // `java` launched this test, so PATH lookup must resolve something.
        // (Windows honours PATHEXT, Unix uses the bare name.)
        val javaBin = HostInfo.findOnPath("java")
        assertNotNull(javaBin)
        assertTrue(javaBin!!.isFile)
    }

    @Test
    fun runCaptureEcho() {
        val host = HostInfo.current()
        val argv = if (host.os == OsKind.WINDOWS) {
            listOf("cmd.exe", "/c", "echo", "hello-pockethost")
        } else {
            listOf("sh", "-c", "echo hello-pockethost")
        }
        val result = HostInfo.runCapture(argv)
        assertNotNull(result)
        assertEquals(0, result!!.code)
        assertTrue(result.output.contains("hello-pockethost"))
    }

    @Test
    fun wslQueryNeverThrows() {
        // On non-Windows hosts this is trivially empty; on Windows it must
        // at worst return empty, never throw.
        val distros = HostInfo.wslDistros()
        assertNotNull(distros)
    }

    @Test
    fun freeBytesRoundTrip() {
        val tmp = java.nio.file.Files.createTempDirectory("pockethost-hostinfo").toFile().apply { deleteOnExit() }
        val free = HostInfo.freeBytes(tmp)
        assertTrue(free > 0)
    }
}
