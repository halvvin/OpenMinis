package com.openminis.app.util

import java.io.File
import java.io.IOException

/**
 * [F-A2 security] Shared ZIP-extraction safety helpers.
 *
 * Closes the ZIP-Slip class (FINAL audit P2-34 / SKILL-ZIPSLIP-01):
 * `File(target, entry.name)` happily accepts entry names carrying `..`,
 * absolute paths, or Windows drive letters — a malicious archive restores
 * outside its destination directory. Every extractor must resolve the entry
 * through [safeChild], which:
 *  1. strips drive letters + leading slashes,
 *  2. builds a canonical containment proof (canonicalParent starts with the
 *     canonical target + separator, or IS the target),
 *  3. rejects symlink-style escapes, empty names, and NUL bytes.
 *
 * [verifyTotalBudget] closes the resource-exhaustion class (P2-33/P2-80):
 * unzip loops call it per entry with the running totals and abort when the
 * archive expands beyond the declared caps.
 */
object ZipSafety {

    /** Default expansion caps for restore flows. */
    const val MAX_TOTAL_UNCOMPRESSED_BYTES: Long = 256L * 1024 * 1024  // 256 MB
    const val MAX_ENTRY_COUNT: Int = 20_000

    /**
     * Resolve [entryName] to a File that is GUARANTEED to stay inside [target].
     * @throws IOException when the entry would escape the target directory.
     */
    fun safeChild(target: File, entryName: String): File {
        if (entryName.isEmpty()) throw IOException("ZIP entry with empty name")
        if (entryName.indexOf('\u0000') >= 0) throw IOException("ZIP entry name contains NUL: $entryName")

        // Normalize: strip drive letters (C:\), leading slashes, and treat
        // backslashes as separators (Windows-produced archives).
        var name = entryName.replace('\\', '/')
        name = name.replace(Regex("^[A-Za-z]:"), "")
        while (name.startsWith("/")) name = name.substring(1)
        if (name.isEmpty()) throw IOException("ZIP entry normalizes to empty path: $entryName")

        val candidate = File(target, name)

        // Canonical containment proof. canonicalPath resolves `..` segments
        // lexically (no filesystem access needed) and is exactly what we want:
        // an entry like "a/../../evil" collapses to a path outside target and
        // fails the prefix check below.
        val targetCanon = target.canonicalPath + File.separator
        val candidateCanon = candidate.canonicalPath
        if (candidateCanon != targetCanon.removeSuffix(File.separator) &&
            !candidateCanon.startsWith(targetCanon)
        ) {
            throw IOException("Blocked ZIP entry escaping target: $entryName")
        }
        return candidate
    }

    /** Running-budget state for [verifyTotalBudget]. */
    class Budget(var totalBytes: Long = 0L, var entries: Int = 0)

    /**
     * Abort extraction when the archive expands beyond [maxBytes] or
     * [maxEntries]. Call once per entry BEFORE copying.
     */
    fun verifyTotalBudget(
        b: Budget,
        entryBytes: Long,
        maxBytes: Long = MAX_TOTAL_UNCOMPRESSED_BYTES,
        maxEntries: Int = MAX_ENTRY_COUNT,
    ) {
        b.entries++
        b.totalBytes += entryBytes
        if (b.entries > maxEntries) {
            throw IOException("ZIP exceeds $maxEntries entries — possible extraction bomb")
        }
        if (b.totalBytes > maxBytes) {
            throw IOException("ZIP exceeds ${maxBytes / (1024 * 1024)} MB uncompressed — possible extraction bomb")
        }
    }
}
