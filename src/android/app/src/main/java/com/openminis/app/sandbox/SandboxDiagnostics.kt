package com.openminis.app.sandbox

import android.content.Context
import java.io.File

/**
 * Deterministic, user-readable sandbox health checks.  This intentionally
 * avoids executing arbitrary guest commands: it diagnoses the host-side
 * prerequisites first, then optionally performs a tiny smoke command through
 * the existing execution path.
 */
object SandboxDiagnostics {
    data class Check(val name: String, val ok: Boolean, val detail: String)
    data class Report(val checks: List<Check>) {
        val healthy: Boolean get() = checks.all { it.ok }
        fun text(): String = buildString {
            appendLine("SANDBOX DIAGNOSTIC")
            checks.forEach { c ->
                append(if (c.ok) "[PASS] " else "[FAIL] ")
                append(c.name).append(": ").appendLine(c.detail)
            }
        }
    }

    fun inspect(context: Context): Report {
        val root = RootfsManager.getInstance(context)
        val native = File(context.applicationInfo.nativeLibraryDir)
        val runtime = File(context.filesDir, "native_libs")
        val checks = mutableListOf<Check>()
        checks += Check("rootfs", root.rootfsDir.isDirectory, root.rootfsDir.absolutePath)
        checks += Check("rootfs marker", File(root.rootfsDir, ".arch").isFile, "ARCH marker")
        checks += Check("guest shell", File(root.rootfsDir, "bin/sh").exists(), "/bin/sh")
        checks += Check("guest busybox", File(root.rootfsDir, "bin/busybox").exists(), "/bin/busybox")
        checks += Check("proot binary", root.prootBinary.isFile && root.prootBinary.canExecute(), root.prootBinary.absolutePath)
        checks += Check("native library dir", native.isDirectory, native.absolutePath)
        val talloc = File(native, "libtalloc.so")
        checks += Check("libtalloc", talloc.isFile, talloc.absolutePath)
        val shmemNative = File(native, "libandroid-shmem.so")
        val shmemRuntime = File(runtime, "libandroid-shmem.so")
        checks += Check(
            "libandroid-shmem",
            shmemNative.isFile || shmemRuntime.isFile,
            if (shmemNative.isFile) shmemNative.absolutePath else shmemRuntime.absolutePath,
        )
        checks += Check("staged native dir", runtime.isDirectory, runtime.absolutePath)
        checks += Check("proot boot state", PRootKernel.isBooted, "isBooted=${PRootKernel.isBooted}")
        return Report(checks)
    }
}
