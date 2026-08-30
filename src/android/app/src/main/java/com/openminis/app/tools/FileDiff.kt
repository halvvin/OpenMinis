package com.openminis.app.tools

/**
 * [T-file-diff] Pure-text diff (LCS-based) showing what changed in a file edit.
 * Mirrors Vega-Agent Diff.kt logic. Returns +/- lines.
 */
object FileDiff {

    fun diff(before: String, after: String): String {
        val oldLines = before.lines()
        val newLines = after.lines()
        val rows = computeRows(oldLines, newLines)
        val sb = StringBuilder()
        val ctx = 2 // context lines around changes
        var lastChange = -1
        for (i in rows.indices) {
            val r = rows[i]
            if (r.op != 0) {
                if (lastChange >= 0 && i - lastChange > ctx * 2) {
                    sb.append("...\n")
                }
                val start = (i - ctx).coerceAtLeast(0)
                if (start > lastChange + 1) sb.append("...\n")
                for (c in start until i) {
                    if (c >= 0 && c < rows.size && rows[c].op == 0) {
                        sb.append("  ${rows[c].oldLine}\n")
                    }
                }
                sb.append("${if (r.op < 0) "-" else "+"} ${r.line}\n")
                lastChange = i
            }
        }
        return sb.toString().trimEnd()
    }

    private fun computeRows(oldLines: List<String>, newLines: List<String>): List<Row> {
        val m = oldLines.size
        val n = newLines.size
        if (m.toLong() * n.toLong() > 500_000L) {
            return oldLines.map { Row(-1, it) } + newLines.map { Row(1, it) }
        }
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) for (j in 1..n) {
            dp[i][j] = if (oldLines[i - 1] == newLines[j - 1]) dp[i - 1][j - 1] + 1
            else maxOf(dp[i - 1][j], dp[i][j - 1])
        }
        val rows = mutableListOf<Row>()
        var i = m; var j = n
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && oldLines[i - 1] == newLines[j - 1]) {
                rows.add(Row(0, oldLines[i - 1]))
                i--; j--
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                rows.add(Row(1, newLines[j - 1]))
                j--
            } else {
                rows.add(Row(-1, oldLines[i - 1]))
                i--
            }
        }
        rows.reverse()
        return rows
    }

    private data class Row(val op: Int, val line: String) {
        val oldLine: String get() = line
    }
}