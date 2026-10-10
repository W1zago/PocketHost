# jvm-host - local Ubuntu Server core for the PC port

Pure-JVM/Kotlin module (zero Android APIs, zero third-party runtime
dependencies) that implements the "local Ubuntu Server" feature for
PocketHost on desktop PCs. It mirrors the Android flow
(`EmbeddedBootstrap` + `EmbeddedUbuntu` + `EmbeddedProcessManager` +
`ServerService.startUbuntu`) with host-appropriate backends.

## Backends

| Host OS | Isolated Ubuntu guest | Fallback |
|---|---|---|
| Linux (amd64/arm64) | ubuntu-base 22.04 rootfs + system `proot` (`PROOT_GUEST`) | interactive host shell |
| Windows | WSL2 Ubuntu distro (`WSL`) | interactive host shell (PowerShell) |
| macOS / other | - | interactive host shell |

Notes:

- On Linux, `proot` cannot be bundled like on Android (the Termux `.deb`
  builds target bionic, not desktop glibc). The system package is used
  instead (`sudo apt install proot`). The rootfs tarball is the same
  official ubuntu-base 22.04.5 build the Android port uses
  (`cdimage.ubuntu.com/ubuntu-base`), extracted with the built-in
  pure-JDK `.tar.gz` unpacker.
- On Windows, `proot` cannot run natively, so Ubuntu comes from WSL2.
  Image provisioning belongs to `wsl --install -d Ubuntu-22.04` itself;
  this module only verifies the distro and starts `wsl -d ... bash`.
  `wsl.exe` inherits the server folder as its cwd (via drvfs), so no
  manual `E:\...` to `/mnt/e/...` translation is needed.
- When the isolated guest is unavailable, `start()` still launches a
  host shell in the server folder and says so in the log - same idea as
  the Android "built-in shell works, Ubuntu on demand".

## Layout

```
<baseDir>/              (~/.pockethost, or %LOCALAPPDATA%/PocketHost on Windows)
  ubuntu/               Ubuntu rootfs + .installed + .version (Linux)
  cache/                downloaded archives (deleted after extraction)
  servers/<id>/         server work dir, each with server.log
```

## Build / test / run

```powershell
.\gradlew.bat :jvm-host:build        # compile + tests
.\gradlew.bat :jvm-host:test         # tests only
.\gradlew.bat :jvm-host:run --args="status"
.\gradlew.bat :jvm-host:run --args="install"
.\gradlew.bat :jvm-host:run --args="start"       # streams the log, Enter stops
.\gradlew.bat :jvm-host:run --args="run-cmd uname -a"
.\gradlew.bat :jvm-host:run --args="log 50"
.\gradlew.bat :jvm-host:run --args="stop"
.\gradlew.bat :jvm-host:run --args="serve 8765"  # shell + LAN bridge (phone URL)
```

## LAN bridge (`serve`, port 8765)

`serve [port]` starts the shell and a pure-JDK HTTP bridge on
**0.0.0.0:[port]** (see `BridgeServer.kt`, `LanInfo.kt`). This is what the
phone connects to through the PocketHost Android app console:

```sh
curl http://<pcLanIp>:8765/api/status
curl "http://<pcLanIp>:8765/api/exec?cmd=uname%20-a"
```

Endpoints: `GET /` (human page), `GET /api/status` (JSON),
`GET /api/ping` (`pong`), `GET /api/log?lines=N`,
`GET /api/exec?cmd=...` / `POST /api/exec` (runs one backend command,
returns `{ok, code, output}`). Every request is logged. `stop` also stops
a background `serve` (via `serve.pid`). `ServerViewModel.kt` wraps
`UbuntuServer` + `BridgeServer` for the future Compose Desktop UI
(listener callbacks, no coroutines - see `state()`, `serve()`, `lanUrls()`).

Interactive console (`shell`) needs a real terminal stdin, which
`gradlew run` does not forward - use the installed binary instead:

```powershell
.\gradlew.bat :jvm-host:installDist
.\jvm-host\build\install\jvm-host\bin\jvm-host.bat shell
```

`install` / `start` are blocking and log to stdout; `start` returns once
the shell is launched (the log pump keeps writing `server.log`).

## Consuming from a future PC UI (Compose Desktop)

`UbuntuServer` is intentionally dependency-free (callbacks, no coroutines).
A `ServerViewModel` wraps it and mirrors state into `MutableStateFlow`:

```kotlin
class ServerViewModel : ViewModel() {
    private val server = UbuntuServer(log = { line ->
        _logLines.update { it + line }
    })
    private val _status = MutableStateFlow(server.status())
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    fun install() = viewModelScope.launch(Dispatchers.IO) {
        server.install()
        _status.value = server.status()
    }
    fun start() = viewModelScope.launch(Dispatchers.IO) {
        server.start()
        _status.value = server.status()
    }
    fun stop() = viewModelScope.launch(Dispatchers.IO) {
        server.stop()
        _status.value = server.status()
    }
    fun exec(cmd: String) = viewModelScope.launch(Dispatchers.IO) {
        server.exec(cmd)
    }
}
```

## What remains for full PC UI integration

1. Compose Desktop shell (`pc-app` module): server list, console view
   tailing `tailLog()` (or the `log` callback), start/stop/install buttons
   bound to `UbuntuServer` as above.
2. Minecraft-on-PC launchers (reuse `LocalProcessManager.start()` with a
   `java -jar ... nogui` argv; desktop needs no linker trick).
3. Optional: `proot` auto-provision on Linux (package-manager prompt),
   WSL distro auto-install (`wsl --install` requires elevation),
   Docker/systemd-nspawn backend as an alternative on Linux.
