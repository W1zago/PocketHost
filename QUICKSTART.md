# ANServer - Quick Start Guide

## Installation

### Prerequisites
- Android Studio Arctic Fox or newer
- JDK 17
- Android SDK with API 26-34
- Android NDK 25.0+
- CMake 3.22.1+

### Steps

1. **Open Project**
   ```
   File > Open > Select C:\Users\W1zago\ANServer
   ```

2. **Sync Gradle**
   - Wait for automatic Gradle sync
   - If prompted, install missing SDK components

3. **Build Project**
   ```
   Build > Make Project (Ctrl+F9)
   ```

4. **Run on Device**
   ```
   Run > Run 'app' (Shift+F10)
   ```

## First Server Setup

### Creating a Basic Server

1. Launch ANServer app
2. Tap the **+** (FAB) button
3. Select server type:
   - **CUSTOM**: Generic server
   - **MINECRAFT**: Minecraft server
   - **WEB_HTTP**: HTTP web server
   - **TCP/UDP**: Network server
   - **PYTHON/NODEJS/JAVA**: Script server

4. Configure settings:
   - Server name
   - Port number
   - Working directory

5. Tap **Create**

### Creating a Minecraft Server

1. Tap **+** button
2. Select **MINECRAFT**
3. Fill in details:
   - Server name: "My MC Server"
   - Version: "1.20.4"
   - Software: Paper/Vanilla/Spigot
   - Port: 25565

4. Tap **Create Minecraft Server**
5. Wait for server JAR download
6. Server is ready!

## Managing Servers

### Start Server
- Tap **Start** button on server card
- Server status changes to "RUNNING"
- Green indicator shows active

### Stop Server
- Tap **Stop** button
- Server gracefully shuts down
- Status changes to "STOPPED"

### View Console
1. Tap on server card
2. Tap **Console** button
3. View real-time logs
4. Send commands via input field

### Browse Files
1. Tap on server card
2. Tap **Files** button
3. Navigate directories
4. Long-press for file options:
   - Copy
   - Move
   - Delete
   - Edit

### Monitor Performance
- Server card shows:
  - CPU usage
  - Memory usage
  - Network activity
  - Uptime

## Minecraft Server Management

### Server Properties
Edit `server.properties` via file manager:
- max-players
- gamemode
- difficulty
- pvp
- online-mode
- motd

### Installing Plugins (Paper/Spigot/Bukkit)
1. Open file manager
2. Navigate to server directory
3. Open `plugins` folder
4. Copy plugin JAR files here
5. Restart server

### Installing Mods (Fabric/Forge)
1. Open file manager
2. Navigate to server directory
3. Open `mods` folder
4. Copy mod JAR files here
5. Restart server

## Troubleshooting

### Server Won't Start
- Check port availability
- Verify Java installation
- Check server logs in console
- Ensure sufficient storage space

### Permission Denied
- Grant storage permissions in Settings
- Enable "All files access" on Android 11+
- Check file permissions

### Native Library Error
- Rebuild project
- Clean build: `Build > Clean Project`
- Invalidate caches: `File > Invalidate Caches`

### Port Already in Use
- Change server port
- Stop conflicting server
- Check active ports in system monitor

## Advanced Features

### Custom Start Commands
Edit server config:
```kotlin
startCommand = "java -Xmx2G -Xms1G -jar server.jar nogui"
```

### Environment Variables
Add to server config:
```kotlin
environment = mapOf(
    "JAVA_HOME" to "/path/to/java",
    "MAX_MEMORY" to "2048"
)
```

### Auto-Start
Enable in server settings:
```kotlin
autoStart = true
```

### Background Service
Servers run in foreground service:
- Persistent notification
- Survives app closure
- Battery optimized

## Tips & Best Practices

### Performance
- Allocate appropriate memory (1-2GB for Minecraft)
- Use Paper/Purpur for better performance
- Monitor CPU/RAM usage
- Close unused servers

### Security
- Use strong passwords
- Enable online-mode for Minecraft
- Whitelist trusted players
- Keep server software updated

### Backups
- Regular backups via file manager
- Export server directory
- Save world files separately
- Test restore process

### Networking
- Use ports 25565+ for Minecraft
- Avoid system ports (1-1023)
- Check firewall settings
- Use local IP for LAN play

## Common Commands

### Minecraft Server Console
```
/stop              - Stop server
/list              - List online players
/op <player>       - Give operator status
/whitelist add     - Add to whitelist
/gamemode survival - Change gamemode
/difficulty normal - Set difficulty
```

### File Operations
- Navigate: Tap folders
- Back: Use back button
- Create: Long-press > New
- Delete: Long-press > Delete
- Edit: Tap text files

## Support

### Getting Help
- Check logs in console
- Review error messages
- Verify configuration
- Test with minimal setup

### Reporting Issues
Include:
- Android version
- Device model
- Server type
- Error logs
- Steps to reproduce

## Resources

### Documentation
- README.md - Full documentation
- BUILD.md - Build instructions
- PROJECT_SUMMARY.md - Technical overview

### Minecraft Resources
- [PaperMC](https://papermc.io) - Paper server
- [SpigotMC](https://spigotmc.org) - Spigot/Bukkit
- [Fabric](https://fabricmc.net) - Fabric mod loader
- [Forge](https://files.minecraftforge.net) - Forge mod loader

### Server Software
- Vanilla: Official Mojang server
- Paper: High-performance fork
- Spigot: Plugin support
- Fabric: Lightweight modding
- Forge: Extensive mod support
- Purpur: Paper fork with extras

---

**Project Location**: `C:\Users\W1zago\ANServer`

**Version**: 1.0.0

**Last Updated**: 2026-05-04
