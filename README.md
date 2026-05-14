# ANServer - Android Server Management Platform

ANServer is a comprehensive Android application for creating and managing local/dedicated servers directly on Android devices. It provides a universal server platform for running various servers, containers, scripts, and network services.

## Architecture

```
Kotlin (Android UI + Business Logic)
    ↓ JNI
C/C++ via Android NDK (Network, Processes, File System, Monitoring)
    ↓
PRoot/QEMU (Isolated Linux Environment)
```

## Features

### 1. Server Manager (Core Feature)
- Create custom servers of any type
- Run local TCP/UDP servers
- Launch Linux services in containers
- Support for Java, Python, Node.js, Docker-like environments
- Create multiple server instances
- Start / Stop / Restart / View Logs

### 2. File System
- Full access to server files
- Built-in file manager
- Configuration file editing
- Archive and backup functionality

### 3. Monitoring
- CPU, RAM, network load tracking
- Active ports monitoring
- Process status tracking

### 4. Linux Environment
- Run via PRoot or QEMU
- Package installation support
- Environment isolation

### 5. Plugin System
- **Minecraft Server Manager** (Additional Module):
  - Create Minecraft servers
  - Vanilla / Paper / Spigot / Fabric / Forge / Purpur
  - Manage mods/plugins
  - Server console
  - Automatic server.jar download

### 6. Security
- Android permissions management
- Sandbox isolation
- File access control

## Project Structure

```
ANServer/
├── app/                          # Main Android application
│   ├── src/main/
│   │   ├── java/com/anserver/app/
│   │   │   ├── ui/              # UI components (Activities, Fragments)
│   │   │   ├── service/         # Background services
│   │   │   └── ANServerApplication.kt
│   │   ├── res/                 # Resources (layouts, strings, etc.)
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
│
├── core/                         # Core business logic
│   ├── src/main/java/com/anserver/core/
│   │   ├── model/               # Data models
│   │   ├── repository/          # Data repositories
│   │   └── util/                # Utility classes
│   └── build.gradle.kts
│
├── native/                       # Native C++ code
│   ├── src/main/
│   │   ├── cpp/
│   │   │   ├── include/         # Header files
│   │   │   ├── native_bridge.cpp
│   │   │   ├── process_manager.cpp
│   │   │   ├── system_monitor.cpp
│   │   │   ├── network_manager.cpp
│   │   │   ├── file_system.cpp
│   │   │   └── proot_manager.cpp
│   │   └── java/com/anserver/native/
│   │       └── NativeBridge.kt  # JNI interface
│   ├── CMakeLists.txt
│   └── build.gradle.kts
│
└── plugins/
    └── minecraft/                # Minecraft plugin
        ├── src/main/java/com/anserver/plugins/minecraft/
        │   ├── MinecraftServer.kt
        │   ├── MinecraftServerManager.kt
        │   └── ui/
        └── build.gradle.kts
```

## Requirements

### For Building
- Android 8.0 (API 26) or higher
- Android NDK
- CMake 3.22.1+
- Gradle 8.4+
- Kotlin 1.9.22+

### For Running Java Servers (Minecraft, etc.)
- **Java Runtime Environment** required
- Recommended: Install Termux from F-Droid and run `pkg install openjdk-17`
- See [JAVA_SETUP.md](JAVA_SETUP.md) for detailed instructions

## Building

1. Clone the repository
2. Open in Android Studio
3. Sync Gradle
4. Build the project

```bash
./gradlew build
```

## Installation

1. Build the APK
2. Install on Android device
3. Grant required permissions:
   - Storage access
   - Network access
   - Notifications

## Usage

### Creating a Server

1. Open ANServer app
2. Tap the "+" button
3. Select server type
4. Configure server settings
5. Tap "Create"

### Managing Servers

- **Start**: Tap the "Start" button
- **Stop**: Tap the "Stop" button
- **Console**: Access server console for command input
- **Files**: Browse and edit server files
- **Monitor**: View CPU, RAM, and network usage

### Minecraft Servers

**Important**: Minecraft servers require Java. Install Termux and Java first:
```bash
# In Termux (from F-Droid)
pkg install openjdk-17
```

Then in ANServer:
1. Select "Minecraft" server type
2. Choose server software (Vanilla, Paper, Spigot, etc.)
3. Select Minecraft version
4. Configure port and settings
5. Server JAR will be downloaded automatically
6. Manage plugins/mods through the file manager

For detailed Java setup instructions, see [JAVA_SETUP.md](JAVA_SETUP.md)

## Native Features

The native C++ layer provides:

- **Process Management**: Start, stop, monitor processes
- **System Monitoring**: CPU, memory, network statistics
- **Network Management**: Port binding, network operations
- **File System**: File operations with proper permissions
- **PRoot Integration**: Isolated Linux environments

## Security

- All servers run in isolated environments
- File access is controlled and sandboxed
- Network operations require explicit permissions
- Process isolation prevents interference

## License

This project is provided as-is for educational and development purposes.

## Contributing

Contributions are welcome! Please ensure code follows the existing architecture and style.

## Support

For issues and feature requests, please create an issue in the repository.
