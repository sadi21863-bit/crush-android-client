package com.crush.mobile.data.crush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the provenance chain a fresh clone walks. The engine binary is
 * gitignored on every branch (docs/engine-acceptance-kit.md §2), so
 * `tools/fetch-crush.ps1` run with no arguments IS where a clean checkout
 * gets its engine. If the script's default lags
 * [EngineCatalogues.PINNED_VERSION], the app silently builds against the
 * previous engine — the version-aware adapter speaks BOTH families, so no
 * other test and no runtime check fails. The v0.100.0 merge exposed exactly
 * that: the app pinned 0.100.0 while the fetch default still said 0.99.0.
 *
 * Row 2 pins the checksum half: advancing the default without recording the
 * release tarball's SHA-256 must fail HERE, not download unverified bytes.
 */
class FetchScriptPinTest {

    private val repoRoot: File = findRepoRoot()
    private val script: String = File(repoRoot, "tools/fetch-crush.ps1").readText()

    private fun findRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "tools/fetch-crush.ps1").isFile) return dir
            dir = dir.parentFile
        }
        error("tools/fetch-crush.ps1 not found above ${System.getProperty("user.dir")}")
    }

    @Test
    fun `the fetch default IS the pinned engine`() {
        val line = script.lineSequence()
            .firstOrNull { it.trimStart().startsWith("[string]\$Version") }
        assertNotNull("default \$Version not found in tools/fetch-crush.ps1", line)
        // `[string]$Version = "0.100.0",` -> 0.100.0
        val declared = line!!.substringAfter('"').substringBefore('"')
        assertEquals(EngineCatalogues.PINNED_VERSION, declared)
    }

    @Test
    fun `the pinned engine has a recorded release checksum`() {
        val pinned = EngineCatalogues.PINNED_VERSION
        val marker = "\$Version -eq \"$pinned\")"
        val branchWithRest = script.substringAfter(marker, missingDelimiterValue = "")
        assertTrue(
            "tools/fetch-crush.ps1 has no checksum branch for $pinned",
            branchWithRest.isNotEmpty(),
        )
        // The branch body runs to the next version test; it must carry a
        // 64-hex SHA-256 (the release tarball's, recorded from the SIGNED
        // checksums.txt), not the empty else default.
        val body = branchWithRest.substringBefore("} elseif")
        assertTrue(
            "no 64-hex release checksum recorded for $pinned",
            Regex("""\b[0-9a-f]{64}\b""").containsMatchIn(body),
        )
    }
}
