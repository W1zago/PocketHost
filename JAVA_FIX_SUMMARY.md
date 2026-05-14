# Fix: Java Runtime Detection on Android

## Problem
The app was trying to use `System.getProperty("java.home")` which returns `/apex/com.android.art/bin/java` on Android - the internal ART runtime that is not accessible for executing Java applications like Minecraft servers.

## Solution
Created a utility class that searches for Java in multiple locations and provides proper error messages to users.

## Changes Made

### 1. Created JavaUtils.kt
**File**: `core/src/main/java/com/anserver/core/util/JavaUtils.kt`

- Searches for Java in multiple locations:
  - Termux: `/data/data/com.termux/files/usr/bin/java`
  - Bundled: `/data/data/com.anserver.app/files/java/bin/java`
  - System paths: `/system/bin/java`, `/system/xbin/java`
  - PATH: `java`
- Validates Java executables
- Gets Java version information
- Returns `JavaInfo` with path and version

### 2. Updated CreateServerActivity.kt
**File**: `app/src/main/java/com/anserver/app/ui/CreateServerActivity.kt`

- Replaced `System.getProperty("java.home")` with `JavaUtils.findJavaExecutable()`
- Added `checkJavaAvailability()` method
- Shows warning Snackbar if Java is not found
- Logs Java path and version when found

### 3. Updated MinecraftServerManager.kt
**File**: `plugins/minecraft/src/main/java/com/anserver/plugins/minecraft/MinecraftServerManager.kt`

- Updated `createStartScript()` to use `JavaUtils.findJavaExecutable()`
- Generates correct start scripts with proper Java path

### 4. Updated ServerService.kt
**File**: `app/src/main/java/com/anserver/app/service/ServerService.kt`

- Added Java availability check before starting Minecraft servers
- Provides detailed error messages with installation instructions
- Logs Java path and version to console
- Prevents server start if Java is not found

### 5. Created Documentation
**File**: `JAVA_SETUP.md`

Comprehensive guide covering:
- Problem explanation
- Installation steps (Termux recommended)
- How ANServer finds Java
- Troubleshooting tips
- Java version requirements for different Minecraft versions
- Performance tips
- Alternative approaches (PRoot containers)

### 6. Updated README.md
**File**: `README.md`

- Added Java requirement to Requirements section
- Added Java installation instructions to Minecraft Servers section
- Links to JAVA_SETUP.md for detailed instructions

## User Experience Improvements

1. **Clear Error Messages**: Users now see helpful messages like:
   - "Java runtime not found. Please install Termux and run: pkg install openjdk-17"
   - Links to Termux installation

2. **Proactive Warnings**: Warning shown when creating servers if Java is not available

3. **Console Logging**: Java path and version logged to server console for debugging

4. **Comprehensive Documentation**: Step-by-step guide for users to set up Java

## Testing Recommendations

1. Test on device without Termux - should show warning
2. Test with Termux + Java installed - should detect and use it
3. Test Minecraft server start - should log Java info
4. Verify error messages are clear and actionable

## Future Enhancements

1. Bundle a lightweight JRE with the app (increases APK size)
2. Add in-app Termux installation guide/link
3. Detect Java version and warn if incompatible with Minecraft version
4. Add Java download/installation wizard
