package com.pockethost.jvmhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UbuntuSpecTest {

    @Test
    fun rootfsUrlMatchesUpstreamLayout() {
        val amd64 = UbuntuSpec.rootfsUrl(CpuKind.AMD64)
        assertEquals(
            "https://cdimage.ubuntu.com/ubuntu-base/releases/jammy/release/" +
                "ubuntu-base-22.04.5-base-amd64.tar.gz",
            amd64
        )
        val arm64 = UbuntuSpec.rootfsUrl(CpuKind.ARM64)
        assertTrue(arm64 != null && arm64.endsWith("ubuntu-base-22.04.5-base-arm64.tar.gz"))
    }

    @Test
    fun unsupportedCpuHasNoUrl() {
        assertNull(UbuntuSpec.rootfsUrl(CpuKind.UNSUPPORTED))
        assertFalse(UbuntuSpec.isSupported(Host(OsKind.LINUX, CpuKind.UNSUPPORTED, "Linux", "i386")))
        assertTrue(UbuntuSpec.supportHint(Host(OsKind.LINUX, CpuKind.UNSUPPORTED, "Linux", "i386")).isNotBlank())
    }

    @Test
    fun versionConstantsAreConsistent() {
        assertEquals("22.04.5", UbuntuSpec.UBUNTU_VERSION)
        assertEquals("jammy", UbuntuSpec.UBUNTU_CODENAME)
        assertTrue(UbuntuSpec.rootfsUrl(CpuKind.AMD64)!!.contains(UbuntuSpec.UBUNTU_VERSION))
        assertTrue(UbuntuSpec.rootfsUrl(CpuKind.AMD64)!!.contains(UbuntuSpec.UBUNTU_CODENAME))
    }
}
