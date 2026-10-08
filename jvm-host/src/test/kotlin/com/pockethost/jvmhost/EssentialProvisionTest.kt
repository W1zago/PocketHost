package com.pockethost.jvmhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Functional check of the first-start essential-packages provision
 * that does NOT need proot/apt: marker gating + one-shot plumbing.
 * Real `apt under proot` runs only on a phone / Linux+proot host.
 */
class EssentialProvisionTest {

    private fun tempBase(): File =
        Files.createTempDirectory("pockethost-essential-test").toFile()

    private fun fakeInstalledRootfs(installer: UbuntuInstaller) {
        val root = installer.rootfsDir()
        File(root, "usr/bin").mkdirs()
        File(root, "usr/bin/bash").createNewFile()
        File(root, "usr/bin/env").createNewFile()
        installer.versionFile().writeText(UbuntuSpec.UBUNTU_VERSION)
        installer.installedMarker().writeText("test")
    }

    @Test
    fun markerGatesProvision() {
        val base = tempBase()
        try {
            val installer = UbuntuInstaller(base) {}
            // Nothing installed -> no provision attempt.
            assertFalse(installer.needsEssentialPackages())

            fakeInstalledRootfs(installer)
            assertTrue(installer.isInstalled())
            // Installed but no marker -> provision needed.
            assertTrue(installer.needsEssentialPackages())

            installer.markEssentialPackagesInstalled()
            assertEquals(
                UbuntuSpec.ESSENTIAL_PACKAGES_VERSION,
                installer.packagesMarker().readText().trim()
            )
            // Marker written -> no more provision.
            assertFalse(installer.needsEssentialPackages())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun ensureRefusesHonestlyWhenNothingInstalled() {
        val base = tempBase()
        try {
            // Fresh base: no rootfs, must return false fast, never apt.
            val server = UbuntuServer(base, "essential-refuse") {}
            assertFalse(server.ensureEssentialPackages(5_000L))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun runOneShotPlumbingWorksOnHostShell() {
        // Exercises the exact runOneShot machinery ensureEssentialPackages
        // uses (launch, log pump, exit code) - on the host-shell backend.
        org.junit.Assume.assumeTrue(HostInfo.current().os == OsKind.WINDOWS)
        val base = tempBase()
        try {
            val server = UbuntuServer(base, "oneshot-plumbing") {}
            val code = server.runOneShot("echo POCKETHOST_ONESHOT_OK", 60_000L)
            assertEquals(0, code)
            assertTrue(server.tailLog(50).contains("POCKETHOST_ONESHOT_OK"))
        } finally {
            base.deleteRecursively()
        }
    }
}
