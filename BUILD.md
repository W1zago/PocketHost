# PocketHost Build Instructions

## Overview

PocketHost is a multi-module Android project designed for running and managing dedicated server processes (such as Minecraft, Web servers, Python, Node.js, and Java apps) directly on Android devices.

The project consists of three Gradle modules:
- `:app` - Android application UI, Navigation, Background Services, and Native C++ In-Process JVM Launcher (`libpockethost_jvm.so`).
- `:core` - Core domain models, process management, storage handling, and server lifecycle utilities.
- `:plugins:minecraft` - Plugin providing UI and management logic specifically tailored for Minecraft servers (Paper, Spigot, Vanilla, Fabric, etc.).

---

## Technical Specifications & Requirements

### System Requirements
1. **JDK**: JDK 17 (recommended: Eclipse Adoptium / Temurin OpenJDK 17).
2. **Android Studio**: Android Studio Ladybug / Koala / Jellyfish or newer (Gradle 8.13 support required).
3. **Android SDK**:
   - `compileSdk`: 34 (Android 14)
   - `minSdk`: 26 (Android 8.0 Oreo)
   - `targetSdk`: 34 (Android 14)
4. **Android NDK**: Version 25.0 or newer (required for C++ CMake build).
5. **CMake**: Version 3.22.1 or newer.
6. **Gradle**: 8.13 (bundled via Gradle Wrapper).

---

## Environment Setup

### 1. Repository Setup
Clone the repository or open the project folder:
```bash
git clone https://github.com/your-repo/PocketHost.git
cd PocketHost
```

### 2. JDK 17 Configuration
Ensure your `JAVA_HOME` environment variable points to a valid **JDK 17** installation.

To verify in terminal:
```bash
java -version
```

---

## Building the Project

### Option 1: Via Android Studio (GUI)
1. Launch Android Studio.
2. Select **Open** and choose the `PocketHost` root directory.
3. Wait for Gradle sync to complete automatically (or trigger **Sync Project with Gradle Files**).
4. Ensure NDK and CMake paths are properly set in **SDK Manager > SDK Tools**:
   - `NDK (Side by side)`
   - `CMake 3.22.1`
5. Select the build variant (`debug` or `release`) in the **Build Variants** window.
6. Click **Build > Make Project** (`Ctrl + F9` / `Cmd + F9`) or run the `:app` module.

### Option 2: Via Command Line (Gradle Wrapper)

#### Windows (PowerShell / CMD):
```cmd
# Clean build artifacts
.\gradlew.bat clean

# Assemble Debug APK
.\gradlew.bat assembleDebug

# Assemble Release APK
.\gradlew.bat assembleRelease
```

#### Linux / macOS:
```bash
# Grant execution permissions to wrapper
chmod +x gradlew

# Clean build artifacts
./gradlew clean

# Assemble Debug APK
./gradlew assembleDebug

# Assemble Release APK
./gradlew assembleRelease
```

The generated APKs will be located in:
`app/build/outputs/apk/debug/app-debug.apk`
`app/build/outputs/apk/release/app-release.apk`

---

## Native C++ Build (JNI / JVM Launcher)

PocketHost includes native C++ code (`app/src/main/cpp/jvm_launcher.c`) configured via `app/src/main/cpp/CMakeLists.txt`.

### Architecture Features:
- **Target ABIs**: `arm64-v8a`, `armeabi-v7a`, `x86_64`.
- **In-Process JVM Launcher**: Ported from MojoLauncher/PojavLauncher for headless server execution.
- **16KB Page Alignment**: Configured with `LINKER:-z,max-page-size=16384` for compatibility with Android 15+ devices using 16KB memory page sizes.

When building via Gradle (`assembleDebug` / `assembleRelease`), CMake will automatically compile `libpockethost_jvm.so` and bundle it inside the final APK.

---

## Troubleshooting Build Issues

### 1. NDK / CMake Missing
- **Error**: `NDK not configured` or `CMake 3.22.1 not found`.
- **Fix**: Open Android Studio > **Settings/Preferences** > **Languages & Frameworks** > **Android SDK** > **SDK Tools**, check `NDK (Side by side)` and `CMake`, then click Apply. Alternatively, specify `ndk.dir` in `local.properties`.

### 2. Gradle JVM Compatibility
- **Error**: Unsupported Class Version or Kotlin compilation errors.
- **Fix**: Ensure Android Studio Gradle JDK is explicitly set to JDK 17 (**Settings** > **Build, Execution, Deployment** > **Build Tools** > **Gradle** > **Gradle JDK**).
