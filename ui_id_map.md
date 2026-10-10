# ui_id_map — old screen/layout → view IDs referenced from code (branch ui-redesign-v2)

All activities use ViewBinding (`Activity*Binding.inflate`). Only `R.id.*` direct uses:
menus + `dialog_create_instance` (via `findViewById`) + dynamic chip lookup.
Only `R.layout.*` manually inflated: `dialog_create_instance` (+ system `simple_dropdown_item_1line`).

## activity_main.xml ↔ MainActivity.kt (+ ServerAdapter.kt)
| ID | Used in |
|---|---|
| root | onCreate insets/setContentView; all Snackbar.make |
| toolbar | setupToolbar (setSupportActionBar, menu click) |
| alivePill / aliveDot / aliveText | updateAlivePill ("N/M Alive") |
| searchInput | setupFilters (filterQuery) |
| btnClearSearch | setupFilters (visible iff query non-empty; clears searchInput) |
| filterGroup / chipAll(RUNNING/STOPPED/MINECRAFT/UBUNTU via tag) | setupFilters + applyFilters |
| sortInput | setupFilters (sortMode) |
| recyclerView | setupRecyclerView |
| emptyView | applyFilters/observeServers (container card) |
| btnEmptyScan | setupScanButton → DeviceScanActivity |
| fab | setupFab → showCreateServerDialog |
| btnScan | click → DeviceScanActivity; long-click → autoRequestAllPermissions |
| btnRemote | click → RemoteConsoleActivity |
| menu_main: action_scan / action_remote | toolbar menu handler |

## dialog_create_instance.xml ↔ MainActivity.showCreateServerDialog()
sheetClose, btnDismiss, optionMinecraft, optionUbuntu — all 4 via findViewById. OK.

## activity_create_server.xml (+ include_minecraft_settings as mcSettings) ↔ CreateServerActivity.kt
root, toolbar, cardMinecraft/checkMinecraft, cardUbuntu/checkUbuntu, etServerName,
tvPortStatus, portRow, etPort, btnVerifyPort, etWorkingDir, btnBrowseDirectory,
etStartCommand, minecraftSettings, tvRamHost, tvRamValue, sliderRam,
spinnerMinecraftVersion, spinnerMinecraftLoader, etMaxMemory, etMinMemory,
btnUseMaxResources, mcSettings.switchPirate, mcSettings.sliderViewDistance,
mcSettings.tvViewDistance, btnCancel, btnCreate.
Orphan (static only): mcSettings.tvSettingsHint.

## activity_server_detail.xml (+ mcSettings include) ↔ ServerDetailActivity.kt
root, toolbar ("Server Studio"), scrollView, serverIcon ("[X]"), serverName,
serverType ("Minecraft {Loader} {ver}" / "Ubuntu PRoot sandbox" — real config),
statusBadge/Dot/Status (10sp), serverPort chip, serverUptime (plain format),
serverIpAddress (Device-IP pill), serverFolderPath (bare path), tvErrorMessage,
tabOverview/Console/Files/Telemetry/Config (active-pill swap in setActiveTab),
resourcesCard (scroll target), tileUptime, tileMemory/tileMemoryPct/heapBar,
tileCores, tileDisk/tileDiskSub/diskBar, tvBindAddress, tvBoundSocket (new),
btnCopyAddress (hero IP pill), btnCopyGateway (new, network card),
btnCopyPath (new, folder bar → copyWorkDirPath), tvLogLive, tvLogPreview,
btnOpenTerminal, minecraftSettingsCard ("Shared Config" + server.properties chip),
mcSettings.switchPirate/sliderViewDistance/tvViewDistance ("N chunks"),
ubuntuSettingsCard, tvUbuntuStatus, btnInstallUbuntu,
btnStart/Stop/Restart/Console/Files (mono, deck icon tints),
btnDelete (delete-row container → deleteServer, no enable logic).
Menu menu_server_detail: action_copy_address, action_delete.
Orphans (containers/static): logCard, mcSettings.tvSettingsHint.

## activity_console.xml ↔ ConsoleActivity.kt
custom 56dp header (no toolbar): btnBack, tvServerName (14sp mono), statusBadge/Dot/Text (10sp),
btnCopy/btnShare/btnClear (direct → copyAll/shareLog/clearConsole), btnMenu (popup from menu_console).
scrollView is ConsoleScrollView in FrameLayout (weight-1, fillViewport,
top-aligned child (never layout_gravity=bottom: it collapses scroll range);
short logs pinned low via minimumHeight + text gravity; 8dp bottom padding;
child-driven scroll suppressed so nothing yanks the viewport;
direct finger drag + fling (content follows the finger), no system scrollbar;
streaming output never touches the viewport mid-gesture or mid-fling;
history: last 256KB of server.log on open, up to 8000 buffered lines;
follow pauses on scroll-up, resumes at bottom; slim single-row
scrollOverlay with btnToTop/btnToBottom with "N new" badge while paused;
draggable fastScrollTrack/fastScrollThumb on the right edge (thumb size =
viewport/content, hidden when nothing scrolls; direct manipulation
to scroll the log),
logView (Termux-style inline terminal: single editable field, typing after
the prompt; history guarded, copy via long-press),
tvScrollPos pill (top-right: live percent + buffered lines, tap = live tail),
green shell prompt from real facts (root/server-name + Build.DEVICE);
IME Send / newline submits to stdin, echo goes to the log.
keyEsc/Slash/Dash/Home/Up/End/Tab/Left/Down/Right/Ctrl/Alt send raw keys;
PgUp/PgDn page the viewport (tap), long-press sends terminal sequence.
Long-press menu (1-4) + btnMenu popup (menu_console) replace old options menu.
Extra keys panel under the input row: extraKeysPanel (same keys).

## activity_file_manager.xml ↔ FileManagerActivity.kt (UnifiedAdapter → item_file.xml)
root, toolbar, tvCrumbs, tvStorageInfo, btnNewFile, btnNewFolder, searchInput,
sortInput, recyclerView, btnSelectFolder (selectMode only).
item_file.xml: root, fileName, fileIcon, fileSize, filePerms, fileDate — all used by UnifiedAdapter.
NOTE (done): adapter/FileAdapter.kt was dead code — deleted in 256ad4f.

## activity_remote_console.xml ↔ RemoteConsoleActivity.kt
root, toolbar, statusBadge/Dot, tvRemoteStatus, etRemoteHost, progressBar,
btnConnect/Disconnect/Ping, btnRemoteUname/Pwd/Ls/Log, remoteScroll
(native scroll + visible scrollbar + pause-on-scroll),
remoteOutput, btnRemoteTop/btnRemoteBottom ("N new" badge while paused),
inputRemoteCommand, btnRemoteSend. All covered.

## activity_device_scan.xml + item_device_check.xml ↔ DeviceScanActivity.kt
activity: root, toolbar, healthTitle, healthSubtitle, healthStatusChip (new),
warnCard (new, issues only), autoCheckAge, checkContainer, btnRescan, btnAutoGrant.
(scrollView, diagnosticsChip static.)
item: checkIcon (ok/fail box bg), checkTitle, checkStatus (new, OK/FAILED),
checkAction, checkDetail. All covered.

## Intents / result contracts (must keep)
- Main→Detail/Console: extra "server_id". Main→FileManager: "workDirUri".
- Main→CreateServer: extra "server_type". Main→ServerService: action START/STOP_SERVER + "server_id".
- CreateServer ↔ FileManager folder picker: SelectIntent(SELECT_MODE=true, ROOT_PATH) → RESULT_OK(SELECTED_PATH). Keep.
- Detail→Console/FileManager/Service: same extras. Console/FileManager share via ACTION_SEND.
- DeviceScan: POST_NOTIFICATIONS permission + settings intents; CreateServer WRITE_EXTERNAL_STORAGE fallback.
- Manifest: Main (LAUNCHER), Detail, Create, FileManager, Console (parent Detail), Remote, DeviceScan, HardGate (noHistory), services.
