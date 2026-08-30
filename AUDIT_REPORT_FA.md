# گزارش عمیق بررسی OpenMinis Android

## وضعیت بررسی

این گزارش بر اساس سورس ZIP ارسالی و APK قبلی تهیه شده است.

- Kotlin: 476 فایل
- Android XML: 70 فایل
- ماژول‌های اصلی: UI، Data/Room، Provider/Model، Agent/Tools، Browser، Sandbox/PRoot، Offload، Auth، Debug، Automation، Speech، Services، Scheduled Tasks و ...
- تعداد importهای داخلی بین بخش‌ها در یک اسکن ایستا: حدود 1533 اتصال
- بررسی Build واقعی: در این محیط به علت نبود دسترسی شبکه، Gradle wrapper نتوانست Gradle 8.11.1 را دریافت کند؛ بنابراین ادعای «Build موفق» داده نمی‌شود.

## نتیجه بسیار مهم

ZIP ارسالی از نظر فایل‌های پروژه Android کامل به نظر می‌رسد، اما **برای Build کامل، submodule مربوط به `deps/proot` داخل ZIP موجود نیست**. خود `BUILDING.md` پروژه نیز صراحتاً می‌گوید PRoot و iSH به صورت Git submodule هستند و باید با `git submodule update --init --recursive` دریافت شوند.

در APK قبلی نیز `libproot.so` این dependencyها را اعلام می‌کرد:

- `libtalloc.so.2`
- `libandroid-shmem.so`
- `libc.so`

ولی APK شامل `libandroid-shmem.so` نبود. بنابراین Native Sandbox از نظر dependency closure ناقص است.

## اصلاحات اعمال‌شده در این نسخه

### 1. یکپارچه‌سازی Native Library Path

قبلاً `RootfsManager`، `libtalloc.so.2` را در:

`filesDir/native_libs`

قرار می‌داد، اما مسیر `LD_LIBRARY_PATH` در مسیرهای اجرای PRoot فقط `applicationInfo.nativeLibraryDir` بود.

اصلاح شد تا همه مسیرهای اجرای PRoot از:

`PRootKernel.runtimeLibraryPath(context)`

استفاده کنند.

این اصلاح روی:

- ShellExecutor
- PersistentShell
- TerminalSession

اعمال شده است.

### 2. جلوگیری از Reset در حالی که PRoot هنوز زنده است

Reset قبلی مستقیماً rootfs را حذف می‌کرد. اکنون قبل از حذف:

1. Shellهای Agent متوقف می‌شوند.
2. PTYهای Terminal متوقف می‌شوند.
3. Native Offload Server متوقف می‌شود.
4. وضعیت PRoot به خاموش برمی‌گردد.
5. سپس rootfs حذف و دوباره نصب می‌شود.

این lifecycle با Mutex مرکزی محافظت شده است تا Boot و Shutdown هم‌زمان روی هم نیفتند.

### 3. جلوگیری از اجرای هم‌زمان ناامن در ShellExecutor

`ShellExecutor` فقط یک `currentProcess` مشترک داشت. اگر دو اجرای one-shot هم‌زمان بودند، اجرای دوم می‌توانست reference اجرای اول را overwrite کند و timeout اجرای اول، process اشتباه را destroy کند.

اکنون اجرای one-shot توسط یک Mutex سراسری serialize می‌شود.

### 4. تشخیص واقعی‌تر Rootfs

قبلاً وجود `.arch` تقریباً برای Installed بودن کافی بود.

اکنون حداقل این‌ها بررسی می‌شوند:

- `/bin/sh`
- `/bin/busybox`
- `/etc/passwd`
- `/etc/profile`
- `/usr/bin`
- `/var`
- `/root`
- `/tmp`

وجود marker به تنهایی دیگر به معنی سالم بودن rootfs نیست.

### 5. تشخیص libandroid-shmem

در `RootfsManager` اگر `libandroid-shmem.so` واقعاً در APK وجود داشته باشد، آن نیز در runtime library directory stage می‌شود.

اما این بخش عمداً فایل جعلی تولید نمی‌کند. اگر library واقعاً وجود نداشته باشد، برنامه باید خرابی واقعی را گزارش کند، نه اینکه با یک فایل تقلبی وانمود کند سالم است.

### 6. کنترل Build Native Dependency

`deps/build_proot.sh` اکنون dependencyهای `DT_NEEDED` مربوط به `libproot.so` را نیز بررسی می‌کند.

در نتیجه اگر PRoot بگوید:

`libproot.so -> libandroid-shmem.so`

ولی library در `jniLibs` نباشد، Build باید Fail شود.

### 7. ابزار Audit

فایل زیر اضافه شده است:

`scripts/audit_android_sandbox.sh`

این ابزار موارد مهم Sandbox را قبل از Build بررسی می‌کند.

## معماری فعلی و نقاط حساس تعامل

```text
                         MinisApp
                            |
             +--------------+--------------+
             |              |              |
          Data/Room      Provider        Services
             |              |              |
             +-------+------+-------+------+
                     |              |
                   Agent          Browser
                     |              |
                   Tools          Offload
                     |              |
                     +------+-------+
                            |
                       Sandbox API
                            |
                  +---------+---------+
                  |                   |
             Execution           Terminal
             Coordinator         Session
                  |                   |
            PersistentShell        PTY
                  |                   |
                  +---------+---------+
                            |
                          PRoot
                            |
                          Rootfs
                            |
                    Alpine Linux userland
```

## مهم‌ترین ریسک‌های معماری

### 🔴 P0: Native dependency ناقص

APK قبلی `libandroid-shmem.so` نداشت، در حالی که `libproot.so` به آن نیاز داشت.

### 🔴 P0: Rootfs reset بدون shutdown کامل

این مورد مستقیماً می‌تواند rootfs را هنگام استفاده حذف کند.

### 🔴 P0: مسیر اشتباه LD_LIBRARY_PATH

`libtalloc.so.2` در مسیر writable stage می‌شد ولی آن مسیر در execution path قرار نمی‌گرفت.

### 🔴 P1: چند مسیر اجرای PRoot

- PersistentShell
- ShellExecutor
- TerminalSession

این سه مسیر باید lifecycle مشترک داشته باشند. در این نسخه Boot/Shutdown و native library path مرکزی‌تر شده‌اند، اما هنوز PRoot یک process manager واحد کامل نیست.

### 🔴 P1: Bind Mountهای session و global

برای sessionها mountهای اختصاصی وجود دارد و برای global data نیز mountهای مشترک وجود دارد. این دو باید از نظر ownership جدا بمانند. کد فعلی در بخش‌هایی این جداسازی را انجام داده، اما هر مسیر File I/O باید session-aware باقی بماند.

### 🟠 P1: Native Offload

Offload بین:

`PRoot extension -> abstract socket -> NativeOffloadServer -> Handler -> Android API`

کار می‌کند. بنابراین خرابی socket یا lifecycle آن می‌تواند به صورت خرابی command در Sandbox دیده شود.

### 🟠 P1: PTY lifecycle

PTY، child PID و reader coroutine مستقل هستند. shutdown باید همیشه هر سه را پوشش دهد.

### 🟠 P1: Environment synchronization

PersistentShell محیط را نگه می‌دارد و ShellExecutor محیط جدید می‌سازد. بنابراین تفاوت رفتار این دو باید در Agent لحاظ شود.

### 🟡 P2: Global coroutine scopes

چند بخش برنامه از `GlobalScope` یا `CoroutineScope(...)` بدون مالک lifecycle استفاده می‌کنند. این می‌تواند در restart/Activity recreation باعث کارهای orphan شود.

موارد مهم برای بازبینی بعدی:

- OAuth
- MinisApp background initialization
- PersistentShell restart
- DebugServer
- بعضی Offload handlers

### 🟡 P2: runBlocking

در چند handler از `runBlocking` استفاده شده است. اگر handler در thread نامناسب اجرا شود، احتمال blocking وجود دارد. بعضی فایل‌ها صراحتاً توضیح داده‌اند که این کار عمدی است، اما باید مسیر thread اجرای NativeOffload ثابت و مستند باشد.

### 🟡 P2: lateinitهای زیاد

بخش زیادی از state مرکزی در `MinisApp` با `lateinit` نگه‌داری می‌شود. کد فعلی تلاش کرده initialization را زودتر انجام دهد، اما هر مسیر جدیدی که قبل از `subsystemsInitialized` به repository دسترسی بزند می‌تواند crash بدهد.

### 🟡 P2: قابلیت‌های ناقص UI

اسکن سورس چند TODO واقعی پیدا کرد، از جمله export session و چند گزینه WebApp که موقتاً مخفی شده‌اند. این‌ها لزوماً علت Sandbox crash نیستند ولی نشان می‌دهند همه گزینه‌های UI هنوز یکپارچه و کامل نیستند.

## نتیجه

Sandbox فعلی فقط یک مشکل ندارد. زنجیره خرابی اصلی این است:

```text
Native dependency
      ↓
PRoot startup
      ↓
Rootfs integrity
      ↓
Process lifecycle
      ↓
PTY / PersistentShell
      ↓
Bind mounts
      ↓
Native Offload
      ↓
Agent / Tools
```

اگر یکی از لایه‌های پایین خراب باشد، بخش‌های بالاتر ممکن است فقط یک خطای عمومی مثل `Shell not running` نشان دهند.

## محدودیت مهم

این نسخه **Build نهایی را ادعا نمی‌کند** چون ZIP فاقد `deps/proot` submodule است و محیط بررسی نیز نتوانست Gradle distribution را از اینترنت دریافت کند. قبل از انتشار APK باید submoduleها دریافت شوند، dependency closure کامل شود و تست روی دستگاه ARM64 واقعی انجام شود.

### منابع فنی خارجی مورد استفاده

- مستندات Build رسمی OpenMinis درباره submoduleها و Build Android.
- مستندات Termux درباره dependencyهای PRoot و `libandroid-shmem`.
- مستندات `libandroid-shmem` درباره نقش System V shared memory روی Android.
