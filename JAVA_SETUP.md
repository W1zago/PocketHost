# Java Setup for ANServer on Android

## Problem

Android devices do not have a standard Java Runtime Environment (JRE) installed. The error `sh: /apex/com.android.art/bin/java: inaccessible or not found` occurs because the app tries to use Android's internal ART runtime, which is not accessible for running Java applications like Minecraft servers.

## Solution

To run Java-based servers (like Minecraft) on Android, you need to install a Java runtime. The recommended approach is using **Termux**.

## Installation Steps

### Option 1: Termux (Recommended)

1. **Install Termux**
   - Download from F-Droid: https://f-droid.org/packages/com.termux/
   - **Do NOT use Google Play version** (it's outdated and broken)

2. **Install Java in Termux**
   ```bash
   pkg update
   pkg upgrade
   pkg install openjdk-17
   ```

3. **Verify Installation**
   ```bash
   java -version
   ```
   You should see output like:
   ```
   openjdk version "17.0.x"
   ```

4. **Grant Storage Permissions**
   ```bash
   termux-setup-storage
   ```

5. **Restart ANServer**
   - Close and reopen ANServer app
   - The app will automatically detect Java from Termux

### Option 2: Manual Java Installation (Advanced)

If you want to bundle Java with ANServer:

1. Download a compatible ARM64 JRE (e.g., Zulu OpenJDK for Android)
2. Extract to `/data/data/com.anserver.app/files/java/`
3. Ensure the `bin/java` executable has proper permissions

## How ANServer Finds Java

ANServer searches for Java in the following locations (in order):

1. `/data/data/com.termux/files/usr/bin/java` (Termux)
2. `/data/data/com.anserver.app/files/java/bin/java` (Bundled)
3. `/system/bin/java` (System-wide, rare)
4. `java` in PATH

## Troubleshooting

### Java Not Found After Installation

1. Make sure Termux is properly installed from F-Droid
2. Verify Java is installed in Termux: `pkg list-installed | grep openjdk`
3. Check Java path: `which java` in Termux
4. Restart ANServer app

### Permission Denied

If you get "Permission denied" errors:

```bash
# In Termux
chmod +x $PREFIX/bin/java
```

### Memory Issues

If Minecraft server crashes due to memory:

1. Reduce `-Xmx` and `-Xms` values in server start command
2. Close other apps to free up RAM
3. Use lightweight server software (Paper instead of Vanilla)

## Recommended Java Versions

- **Minecraft 1.17+**: Java 17 or higher
- **Minecraft 1.16.5 and below**: Java 8 or 11
- **Minecraft 1.12.2 and below**: Java 8

Install specific versions in Termux:
```bash
pkg install openjdk-17  # For modern Minecraft
pkg install openjdk-11  # For older versions
```

## Alternative: Use PRoot Environment

ANServer also supports running servers in PRoot containers (Ubuntu, Debian, Alpine), which come with their own Java installations:

1. Create an Ubuntu Server instead of Minecraft
2. Install Java inside the container
3. Run Minecraft server from within the container

## Performance Tips

1. **Use Paper/Purpur** instead of Vanilla for better performance
2. **Limit view distance** in server.properties (6-8 chunks)
3. **Reduce max players** to 5-10
4. **Allocate appropriate memory**: 1-2GB for small servers
5. **Use SSD storage** if possible (faster world loading)

## Support

If you continue to have issues:

1. Check ANServer logs in the Console tab
2. Check Termux logs: `logcat | grep ServerProcess`
3. Verify Java installation: `java -version` in Termux
4. Report issues with full error logs

## References

- Termux Wiki: https://wiki.termux.com/
- Termux Java: https://wiki.termux.com/wiki/Development_Environments#Java
- Minecraft Server Requirements: https://minecraft.fandom.com/wiki/Server/Requirements
