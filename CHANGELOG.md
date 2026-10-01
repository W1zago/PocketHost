# Changelog

All notable changes to PocketHost will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-05-04

### Added
- Initial release of PocketHost
- Server creation and management system
- Support for multiple server types (Custom, Minecraft, Web, TCP/UDP, Python, Node.js, Java)
- Native C++ integration via JNI for system operations
- Process management (start, stop, restart, monitor)
- System monitoring (CPU, RAM, network usage)
- File manager with full file system access
- Server console with command input
- Foreground service for background server operation
- PRoot integration for isolated Linux environments
- Minecraft server plugin with multiple software support:
  - Vanilla
  - Paper
  - Spigot
  - Fabric
  - Forge
  - Purpur
  - Bukkit
- Automatic Minecraft server JAR download
- Server properties configuration
- Plugin and mod management for Minecraft
- Material Design 3 UI
- ViewBinding for type-safe view access
- Kotlin Coroutines for async operations
- Repository pattern for data management
- Multi-ABI support (arm64-v8a, armeabi-v7a, x86_64)

### Technical Features
- Kotlin 1.9.22
- Android SDK 26-34
- Android NDK 25.0+
- CMake 3.22.1
- Gradle 8.4
- AndroidX libraries
- Lifecycle components
- Navigation components
- WorkManager
- Room database ready
- ProGuard/R8 optimization

### Documentation
- README.md with full project overview
- BUILD.md with build instructions
- QUICKSTART.md for getting started
- ROADMAP.md for future development
- PROJECT_SUMMARY.md with technical details
- Inline code documentation

### Security
- Android permissions management
- Sandboxed execution
- File access control
- Process isolation
- Secure native code implementation

## [Unreleased]

### Planned for 1.1.0
- Dark mode support
- Server templates
- Backup and restore functionality
- Performance graphs
- Enhanced logging
- Widget support

### Planned for 1.2.0
- Cloud backup integration
- Advanced monitoring
- Docker support
- Remote management

### Planned for 2.0.0
- Plugin marketplace
- Web dashboard
- REST API
- Multi-device sync

---

## Version History

- **1.0.0** (2026-05-04) - Initial Release

---

**Note**: This project follows semantic versioning. Version numbers are structured as MAJOR.MINOR.PATCH where:
- MAJOR version for incompatible API changes
- MINOR version for backwards-compatible functionality additions
- PATCH version for backwards-compatible bug fixes
