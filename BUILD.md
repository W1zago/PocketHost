# PocketHost Build Instructions

## Prerequisites

1. **Android Studio**: Arctic Fox (2020.3.1) or newer
2. **Android SDK**: API 26 (Android 8.0) minimum, API 34 (Android 14) target
3. **Android NDK**: Version 25.0 or newer
4. **CMake**: Version 3.22.1 or newer
5. **Java**: JDK 17
6. **Gradle**: 8.4 (included via wrapper)

## Setup

1. Clone or extract the project to your desired location
2. Open Android Studio
3. Select "Open an Existing Project"
4. Navigate to the PocketHost directory and select it
5. Wait for Gradle sync to complete

## Building

### Via Android Studio

1. Select Build > Make Project (Ctrl+F9)
2. Wait for the build to complete
3. Check the Build output for any errors

### Via Command Line

#### Windows
```cmd
gradlew.bat assembleDebug
```

#### Linux/Mac
```bash
./gradlew assembleDebug
```

## Running

### On Emulator
1. Create an AVD with API 26+ in AVD Manager
2. Start the emulator
3. Click Run > Run 'app' (Shift+F10)

### On Physical Device
1. Enable Developer Options on your device
2. Enable USB Debugging
3. Connect device via USB
4. Click Run > Run 'app' (Shift+F10)

## Build Variants

- **debug**: Development build with debugging enabled
- **release**: Production build with ProGuard/R8 optimization

To build release APK:
```bash
./gradlew assembleRelease
```

## Output Locations

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `app/build/outputs/apk/release/app-release.apk`

## Troubleshooting

### NDK Not Found
1. Open SDK Manager in Android Studio
2. Go to SDK Tools tab
3. Check "NDK (Side by side)"
4. Click Apply

### CMake Not Found
1. Open SDK Manager
2. Go to SDK Tools tab
3. Check "CMake"
4. Click Apply

### Build Fails
1. Clean project: Build > Clean Project
2. Invalidate caches: File > Invalidate Caches / Restart
3. Delete `.gradle` and `.idea` folders
4. Sync project again

## Testing

Run unit tests:
```bash
./gradlew test
```

Run instrumented tests:
```bash
./gradlew connectedAndroidTest
```

## Project Structure

```
PocketHost/
в”њв”Ђв”Ђ app/              # Main application module
в”њв”Ђв”Ђ core/             # Core business logic
в”њв”Ђв”Ђ native/           # Native C++ code
в”њв”Ђв”Ђ plugins/
в”‚   в””в”Ђв”Ђ minecraft/    # Minecraft plugin module
в”њв”Ђв”Ђ gradle/           # Gradle wrapper
в”њв”Ђв”Ђ build.gradle.kts  # Root build script
в””в”Ђв”Ђ settings.gradle.kts
```

## Dependencies

All dependencies are managed via Gradle and will be downloaded automatically during sync.

## Notes

- First build may take longer due to NDK compilation
- Native libraries are built for arm64-v8a, armeabi-v7a, and x86_64
- Minimum 4GB RAM recommended for building
- SSD recommended for faster build times
