package com.pockethost.jvmhost

/**
 * Ubuntu release pinned by PocketHost (same as the Android port).
 *
 * Rootfs tarballs come from the official ubuntu-base builds:
 * `https://cdimage.ubuntu.com/ubuntu-base/releases/<codename>/release/`
 * one `ubuntu-base-<version>-base-<arch>.tar.gz` per architecture (~26 MB).
 */
object UbuntuSpec {

    const val UBUNTU_VERSION = "22.04.5"
    const val UBUNTU_CODENAME = "jammy"

    const val UBUNTU_BASE_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/jammy/release/"

    /** Suggested WSL distribution name on Windows. */
    const val WSL_DISTRO = "Ubuntu-22.04"

    /** Bump to re-run the first-start provision on existing installs (same as Android). */
    const val ESSENTIAL_PACKAGES_VERSION = "2"

    /**
     * "Maximum" first-start set, mirrored from the Android port
     * (EmbeddedUbuntu.ESSENTIAL_PACKAGES). Installed in background after
     * the console is live, never blocking start.
     */
    val ESSENTIAL_PACKAGES = listOf(
        "ca-certificates",
        "curl",
        "wget",
        "git",
        "nano",
        "vim",
        "iputils-ping",
        "htop",
        "python3",
        "python3-pip",
        "openssh-client",
        "openssh-server",
        "nodejs",
        "npm"
    )

    /** Non-interactive guest shell command for the first-start provision. */
    fun essentialAptCommand(): String {
        val pkgs = ESSENTIAL_PACKAGES.joinToString(" ")
        return "export DEBIAN_FRONTEND=noninteractive && " +
            "apt-get update && apt-get install -y $pkgs && apt-get clean && " +
            "mkdir -p /run/sshd && ssh-keygen -A"
    }

    /** Free space required for the max essential set (download + install + headroom). */
    const val ESSENTIAL_PACKAGES_MIN_FREE_BYTES = 800L * 1024 * 1024

    /** Minimum free space for rootfs download + extraction + headroom. */
    const val MIN_FREE_BYTES = 300L * 1024 * 1024

    /**
     * Minimum free space on the system drive for the WSL2 backend.
     * WSL needs gigabytes (distro image + virtual disk headroom),
     * not the ~300 MB that suffice for the Linux rootfs tarball.
     */
    const val WSL_MIN_FREE_BYTES = 5L * 1024 * 1024 * 1024

    /** Ubuntu-base architecture for this host CPU, or null when unsupported. */
    fun rootfsArch(cpu: CpuKind): String? = when (cpu) {
        CpuKind.AMD64 -> "amd64"
        CpuKind.ARM64 -> "arm64"
        CpuKind.UNSUPPORTED -> null
    }

    /** Download URL of the ubuntu-base tarball, or null when unsupported. */
    fun rootfsUrl(cpu: CpuKind): String? {
        val arch = rootfsArch(cpu) ?: return null
        return "${UBUNTU_BASE_URL}ubuntu-base-${UBUNTU_VERSION}-base-$arch.tar.gz"
    }

    fun isSupported(host: Host): Boolean = rootfsArch(host.cpu) != null

    fun supportHint(host: Host): String =
        "CPU ${host.osArch}: no Ubuntu $UBUNTU_VERSION rootfs upstream " +
            "(only amd64 / arm64). The server still runs as a host shell."
}
