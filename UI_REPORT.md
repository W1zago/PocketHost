# Звіт по UI PocketHost — для нового дизайну

## 1. Про що проєкт

**PocketHost** — Android-додаток, який перетворює телефон на міні-хостинг серверів **без root**.
Сервери запускаються локально на пристрої в ізольованих PRoot-контейнерах (user-space Linux)
через фоновий `ServerService`, логи пишуться в `server.log`, метрики збираються з `/proc`.

Два типи серверів (`ServerType`):

- **MINECRAFT** — ігровий сервер (загрузчики: Vanilla, Forge, Fabric, NeoForge, Paper,
  Spigot, Bukkit, Purpur; версії 1.8.9–1.21.4, дефолт 1.20.4).
  Java (JRE) довантажується автоматично при першому старті.
- **UBUNTU_SERVER** — Linux PRoot-пісочниця (Ubuntu 22.04, ~30MB rootfs + фонові
  essential-пакети: python3, nodejs, git…), інтерактивний shell.
  Без завантаженого Ubuntu працює вбудований shell.

Статуси сервера: `STOPPED → STARTING → RUNNING → STOPPING`, плюс `ERROR`.
Все керування — старт/стоп/рестарт через `ServerService`, консоль — це запис у stdin
процесу + читання `server.log`, файли — прямий доступ до робочої папки сервера.

Стиль поточного дизайну: темна тема, моноширинні підписи-лейбли (`INSTANCES`,
`LIVE TELEMETRY`, `NETWORK & BINDING`), статус-бейджі з крапкою
(зелений/сірий/червоний), картки-секції, телеметрія-плитки. Мова інтерфейсу — англійська.

---

## 2. Екрани та всі кнопки/дії

### Екран 1 — Головна (MainActivity, `activity_main.xml`)

Список інстансів серверів + пошук/фільтри.
Порожній стан: текст «No servers yet, Tap + to create one».

| Елемент | Дія |
|---|---|
| Toolbar «PocketHost» | Тільки заголовок, дій нема |
| Alive-pill «N/M Alive» | Індикатор (біжи/стоп), не клікабельний |
| Поле `searchInput` («Filter by name, port, or type…») | Живий пошук по назві/порту/типу |
| Чипи `All / Running / Stopped / Minecraft / Ubuntu` | Фільтр списку (single-select) |
| Дропдаун `sortInput` | Сортування: By Status / By Name / By Port |
| Картка сервера (тап по картці) | Відкриває ServerDetail |
| `Start` на картці | Запуск сервера (через ServerService) |
| `Stop` на картці | Зупинка |
| `Restart` на картці | Рестарт (стоп → старт) |
| `Logs` на картці | Відкриває Console |
| `Files` на картці | Відкриває FileManager |
| Телеметрія на картці | PORT / UPTIME / LAST RUN — тільки показ |
| Статус-бейдж на картці | Stopped/Running/… — тільки показ |
| FAB `+` | Діалог «Create Server» (див. нижче) |
| Кнопка `Scan device` (тап) | Відкриває DeviceScan |
| Кнопка `Scan device` (довгий тап) | Автозапит дозволів (нотифікації + ігнор оптимізації батареї) |
| Кнопка `PC Console` | Відкриває RemoteConsole (консоль ПК) |
| Снекбар при старті | «Allow PocketHost to run in background?» → кнопка Allow (запит ігнору оптимізації батареї) |

**Діалог FAB «Create Server»:** список з 2 типів (`MINECRAFT`, `UBUNTU_SERVER`) →
перехід на CreateServer; нейтральна кнопка `Scan device` → DeviceScan.

### Екран 2 — Створення сервера (CreateServerActivity)

| Елемент | Дія |
|---|---|
| Toolbar «Create Server» + Back | Назад (скасувати) |
| Картка `Minecraft` («Paper / Purpur / Fabric») | Вибір рантайму, розкриває секцію Resource Quotas |
| Картка `Linux PRoot` («Debian • rootless sandbox») | Вибір рантайму |
| Поле `Server Identifier` | Назва сервера (обов'язкове) |
| Поле `Port` (дефолт 25565) | Порт прослуховування |
| Кнопка `Verify` | Перевірка: порт вільний/зайнятий (статус у `tvPortStatus`) |
| Поле `Working Directory` (не редагується, тап) | Відкриває вибір папки |
| Кнопка `Browse Directory` | Системний Storage-пікер (SAF) або внутрішня папка; потрібен «all files access», інакше снекбар-помилка |
| Поле `Start Command` | Кастомна старт-команда |
| Слайдер `Max RAM Limit` 128–3072 MB, крок 64 | Ліміт RAM (показ `tvRamHost` — скільки RAM у пристрою) |
| Дропдаун `Minecraft Version` | 1.8.9 … 1.21.4 (дефолт 1.20.4; 1.20.5+ чесно відмовляє — bundled JRE17 не тягне, треба Java 21) |
| Дропдаун `Server Loader` | Vanilla / Forge / Fabric / NeoForge / Paper / Spigot / Bukkit / Purpur |
| Поля `Max Memory / Min Memory (MB)` | Xmx/Xms для Java |
| Кнопка `Use Maximum Resources` | Виставляє RAM = частка пам'яті пристрою |
| Світч `Allow cracked players` | `online-mode=false` (без перевірки ліцензії Mojang) |
| Слайдер `View distance` 2–32 чанки | Дистанція прогрузки (застосовується після рестарту) |
| Кнопка `Cancel` | Закрити без створення |
| Кнопка `Create` | Створити сервер → назад у список |

### Екран 3 — Деталі сервера (ServerDetailActivity)

Хіро-картка: іконка-літера, назва, тип, статус-бейдж, чип порту, аптайм, IP-адреса
пристрою, шлях до папки (обидва — копіюються/виділяються), червоний текст помилки (якщо ERROR).

| Елемент | Дія |
|---|---|
| Таби `Overview / Console / Files / Telemetry / Config` | Overview — скрол догори; Console — відкрити консоль; Files — відкрити файли; Telemetry — скрол до телеметрії; Config — скрол до налаштувань |
| Телеметрія LIVE (полінг 2с) | UPTIME / APP HEAP / CPU CORES / DISK FREE — тільки показ |
| `Copy` біля bind-адреси | Копіює `IP:порт` у буфер |
| `Open Interactive Terminal` | Відкриває Console |
| Світч `Allow cracked` + слайдер `View distance` (тільки Minecraft) | Зміна на зупиненому — одразу, на запущеному — після рестарту |
| Кнопка `Download Ubuntu (~30MB)` (тільки Ubuntu, якщо rootfs нема) | Завантаження Ubuntu rootfs з прогресом |
| `Start / Stop / Restart` | Керування життєвим циклом |
| `Console / Files` | Переходи на екрани |
| `Delete` (червона, на всю ширину) | Діалог-підтвердження → видалення сервера |
| Меню (⋮): `Copy address`, `Delete` | Дублікати кнопок Copy/Delete |

### Екран 4 — Консоль (ConsoleActivity)

Живий лог (`server.log`) + поле вводу команд у stdin.

| Елемент | Дія |
|---|---|
| Статус-бейдж + ім'я сервера | Показ стану |
| Поле `Filter logs…` | Фільтр рядків лога |
| Кнопка `Follow: ON/OFF` | Автопрокрутка за новими рядками |
| Чипи `ALL / INFO / WARN / ERROR` | Фільтр за рівнем + лічильники |
| Кнопка `↓ Jump to Live` (з'являється при скролі вгору) | Стрибок до низу лога |
| Minecraft-пили (тільки MC): `/list /tps /save-all /say /stop` | Підставляють команду в поле вводу (`/stop` — червона) |
| Ubuntu-пили (тільки Linux): `ls ps df -h pwd` | Підставляють команду в поле вводу |
| Extra-клавіатура (2 ряди): `ESC / — HOME ↑ END PGUP / TAB CTRL ALT ← ↓ → PGDN` | Надсилають escape-коди в stdin; CTRL/ALT — перемикачі-модифікатори |
| Поле `Enter command or STDIN…` + Enter | Ввід команди |
| Кнопка `↻` (History) | Підставити попередню команду з історії |
| Кнопка `↵` (Send) | Надіслати команду в stdin сервера |
| Лонг-прес / контекстне меню по логу | Copy selection / Copy all / Share log / Clear console |
| Меню (⋮): Copy / Select all / Clear / Share | Копіювати весь лог; виділити все; очистити вид (файл лога НЕ чіпає); шаринг лога текстом |
| Футер `tvFooterStatus` | Ім'я лог-файла + кількість рядків — показ |

### Екран 5 — Файли (FileManagerActivity)

Браузер робочої папки сервера (локальні файли або SAF).

| Елемент | Дія |
|---|---|
| Хлібні крихти `tvCrumbs` | Поточний шлях |
| Плашка `Storage` | Вільно/зайнято (StatFs) — показ |
| Кнопки `New File / New Folder` | Діалог з полем імені → Create/Cancel |
| Поле `Search files…` | Пошук у поточній папці |
| Дропдаун сортування | Name (дефолт; + інші режими) |
| Тап по папці | Увійти всередину |
| Тап по текстовому файлу (<2MB) | Діалог-перегляд вмісту (до 20К символів): OK / Share |
| Тап по бінарнику/великому | Тост «Cannot read (binary or huge?)» |
| Лонг-прес по файлу/папці | Меню: Delete / Rename / New file / New folder / Share |
| `Delete` | Підтвердження Delete/Cancel → рекурсивне видалення |
| `Rename` | Діалог з полем (валід. імені) → OK/Cancel |
| `Share` | Шаринг вмісту як текст (ліміт ~200KB; папки не шаряться) |
| Кнопка `Select this folder` (видима тільки в режимі вибору папки з CreateServer) | Підтвердити папку як Working Directory → повернення результату |

Рядок файла (`item_file.xml`): іконка-літера (D — папка), ім'я, права `rwx`, розмір, дата.

### Екран 6 — PC Console / Remote (RemoteConsoleActivity)

Керування **ПК-версією** (`jvm-host` bridge, порт 8765) з телефона по Wi-Fi.

| Елемент | Дія |
|---|---|
| Статус-бейдж | Disconnected/Connected — показ |
| Поле `IP:PORT` (дефолт 192.168.0.200:8765) | Адреса PC-бриджа |
| `Connect` | GET /api/status — підключення |
| `Disconnect` | Розрив |
| `Ping` | GET /api/ping |
| Швидкі кнопки `uname -a / pwd / ls -la / server.log` | Виконати на ПК (POST /api/exec або /api/log) |
| Поле вводу + `Send` | Довільна команда на ПК |
| Вікно виводу | Результати `{ok, code, output}` — показ |

### Екран 7 — Діагностика пристрою (DeviceScanActivity, будується кодом)

8 рядків-перевірок, кожен: `✓/✗` + кнопка-дія (якщо ОК — кнопка неактивна «OK»):

1. Built-in terminal (sh) → `Details` (інфо-діалог)
2. Internal storage → `Open storage` (системні налаштування сховища)
3. Built-in Java → `Install JRE` (довантаження ~100MB)
4. Built-in shell for Ubuntu → `Details`
5. Battery ignore optimizations → `Allow background` (системний запит)
6. POST_NOTIFICATIONS → `Request` (системний дозвіл)
7. Free space >512MB → `Clear` (налаштування сховища)
8. Internet → `Check network` (налаштування Wi-Fi)

Плюс кнопки `Scan again` (перескан) і `Grant permissions automatically` (ланцюг автозапитів).

### Службовий екран — TermuxHardGateActivity (legacy)

Зараз **авторедиректить на Main** і не показується.
Fallback-кнопки (на випадок помилки редіректу): Install Termux (F-Droid),
Grant RUN_COMMAND permission, Open Termux settings, Check again,
Clear old servers (SAF), Open PocketHost without Termux, Scan device.

---

## 3. Що врахувати в новому дизайні

- Екрани: Головна (список) → Створення (форма) → Деталі (дашборд) → Консоль (термінал) →
  Файли (браузер) → PC Console (ремот) → Scan (діагностика).
  Плюс системні: діалог вибору типу, 5 файлових діалогів, меню консолі.
- Ключові патерни: картка сервера з 5 діями (Start/Stop/Restart/Logs/Files); таб-ряд у деталях;
  пили швидких команд у консолі (різні для MC vs Linux); extra-клавіатура терміналу;
  бейджі статусів; моноширинні технічні підписи.
- Світч cracked + слайдер view-distance існують у двох місцях (Create і Detail) — варто уніфікувати.
- Все англомовне; кольорова семантика: зелений = running/OK, червоний = destructive/error,
  моноширний = технічні дані (IP, порт, лог).

---

## 4. Карта файлів (де що лежить)

- Екрани: `app/src/main/java/com/pockethost/app/ui/ui/` —
  `MainActivity`, `CreateServerActivity`, `ServerDetailActivity`, `ConsoleActivity`,
  `FileManagerActivity`, `RemoteConsoleActivity`
- Службові: `app/src/main/java/com/pockethost/app/termux/` —
  `DeviceScanActivity`, `TermuxHardGateActivity`
- Розмітки: `app/src/main/res/layout/` — `activity_main`, `activity_create_server`,
  `activity_server_detail`, `activity_console`, `activity_file_manager`,
  `activity_remote_console`, `item_server`, `item_file`
- Меню: `app/src/main/res/menu/` — `menu_console`, `menu_server_detail`
- Адаптери списків: `app/src/main/java/com/pockethost/app/ui/adapter/` —
  `ServerAdapter`, `FileAdapter`
- Моделі: `core/src/main/java/com/pockethost/core/model/Server.kt` —
  `ServerType` (MINECRAFT, UBUNTU_SERVER), `ServerStatus`, `MinecraftLoader`
