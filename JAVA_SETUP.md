# Java Setup for PocketHost on Android

## Problem Context

Standard Android devices do not ship with a standalone Java Runtime Environment (JRE/JDK).
If an application attempts to run a Java binary directly using Android's internal ART runtime path (such as `/apex/com.android.art/bin/java`), it will fail with errors such as:
`sh: /apex/com.android.art/bin/java: inaccessible or not found`

To run Java-based servers (such as Minecraft Paper, Spigot, Vanilla, or custom JAR applications) on PocketHost, a full OpenJDK binary compatible with ARM64/ARMv7 Android environments is required.

---

## Execution Modes in PocketHost

PocketHost supports **two execution strategies** for Java applications:

1. **In-Process Native JVM (`libpockethost_jvm.so`)**:
   - Uses PocketHost's custom C++ native JNI launcher (ported from MojoLauncher/PojavLauncher).
   - Runs the JVM inside the app process, optimized for headless servers with low overhead and direct JNI control.
   - Requires bundled or external OpenJDK runtime libraries (`libjvm.so`, `modules`/`rt.jar`).

2. **External Process (Termux / Standalone Binary)**:
   - Spawns Java as a separate system process via command line.
   - Ideal when using Termux packages or isolated chroot/PRoot environments.

---

## Installation & Setup Guide

### Method 1: Termux Setup (Recommended for External Process)

Using **Termux** is the most reliable way to obtain and update OpenJDK binaries on Android.

#### Step 1: Install Termux
- Download and install **Termux** from [F-Droid](https://f-droid.org/packages/com.termux/).
- ⚠️ **Important**: Do NOT install Termux from the Google Play Store (the Play Store version is deprecated and unsupported).

#### Step 2: Install OpenJDK in Termux
Open Termux and execute:

```bash
# Update package repositories
pkg update && pkg upgrade -y

# Install OpenJDK 17 (recommended for Minecraft 1.18+)
pkg install openjdk-17 -y

# Alternatively, for newer Minecraft versions requiring Java 21:
# pkg install openjdk-21 -y
```

#### Step 3: Verify Installation Path
Check the installed Java path in Termux:
```bash
which java
# Output: /data/data/com.termux/files/usr/bin/java
```

PocketHost automatically scans standard locations including `/data/data/com.termux/files/usr/bin/java`.

---

### Method 2: Embedded / Bundled Java Binaries

If you are using PocketHost with a bundled JRE package stored in app internal storage:

1. Extract your OpenJDK build to the app's internal files directory:
   `/data/data/com.pockethost.app/files/java/`
2. Ensure the binary structure follows the standard layout:
   - Binaries: `/data/data/com.pockethost.app/files/java/bin/java`
   - Libraries: `/data/data/com.pockethost.app/files/java/lib/`
3. PocketHost's internal `JavaUtils` scanner automatically detects and validates executables in this directory.

---

## How PocketHost Detects Java (`JavaUtils.kt`)

PocketHost uses `com.pockethost.core.util.JavaUtils` to detect and parse major Java versions. The detection algorithm scans paths in the following priority order:

1. **Termux JRE**: `/data/data/com.termux/files/usr/bin/java`
2. **Bundled JRE**: `/data/data/com.pockethost.app/files/java/bin/java`
3. **System Paths**: `/system/bin/java`, `/system/xbin/java`
4. **Environment PATH**: `java` binary available in current PATH environment variable.

---

## Troubleshooting & Common Issues

| Issue / Error | Root Cause | Solution |
| :--- | :--- | :--- |
| `java: inaccessible or not found` | Attempted to run Android ART internal binary | Install OpenJDK in Termux or place OpenJDK in app files directory. |
| `UnsupportedClassVersionError` | Server JAR requires a higher Java version (e.g. Java 17/21) than installed | Upgrade OpenJDK in Termux (`pkg install openjdk-17` or `openjdk-21`). |
| `Permission denied` | The `java` binary lacks execution (`+x`) permissions | In Termux or ADB, run `chmod +x /path/to/java`. |
