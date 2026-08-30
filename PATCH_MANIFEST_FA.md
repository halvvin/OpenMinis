# فهرست اصلاحات اعمال‌شده

## تغییر فایل‌ها

- `src/android/app/src/main/java/com/openminis/app/sandbox/PRootKernel.kt`
  - Mutex برای Boot/Shutdown
  - مسیر مرکزی Native runtime libraries
  - Shutdown امن Sandbox

- `src/android/app/src/main/java/com/openminis/app/sandbox/RootfsManager.kt`
  - Reset امن با توقف Sandbox قبل از حذف rootfs
  - بررسی integrity اولیه rootfs
  - stage کردن dependencyهای Native موجود

- `src/android/app/src/main/java/com/openminis/app/sandbox/ShellExecutor.kt`
  - serialization اجرای one-shot
  - استفاده از runtime library path مرکزی

- `src/android/app/src/main/java/com/openminis/app/sandbox/PersistentShell.kt`
  - استفاده از runtime library path مرکزی

- `src/android/app/src/main/java/com/openminis/app/sandbox/TerminalSession.kt`
  - توقف گروهی PTYها
  - استفاده از runtime library path مرکزی

- `src/android/app/src/main/java/com/openminis/app/sandbox/SandboxDiagnostics.kt`
  - Health check جدید

- `deps/build_proot.sh`
  - بررسی dependencyهای ELF و Fail کردن Build در صورت ناقص بودن Native runtime

- `scripts/audit_android_sandbox.sh`
  - Audit قبل از Build

## مواردی که عمداً دستکاری نشده‌اند

- کد third-party مثل PRoot و libandroid-shmem در ZIP موجود نیست و فایل جعلی جایگزین آن‌ها نشده است.
- قابلیت‌های UI ناقص به صورت حدسی پیاده‌سازی نشده‌اند.
- Build موفق ادعا نشده است چون submodule و شبکه Build در محیط بررسی موجود نبوده است.
