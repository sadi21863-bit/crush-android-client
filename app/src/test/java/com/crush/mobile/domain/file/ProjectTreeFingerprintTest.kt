package com.crush.mobile.domain.file

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P6 Batch F fingerprint determinism tests (plan §8 f2: "fingerprint
 * determinism/stability on fixture trees"), plain JVM tier - pure
 * java.io + coroutines; the TemporaryFolder rule the domain/file test
 * tier already keeps builds the fixture trees, "Nothing to clean;
 * TemporaryFolder handles it".
 */
class ProjectTreeFingerprintTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Writes a file (mkdirs first) and returns it, inside [root]. */
    private fun f(root: File, relative: String, content: String): File {
        val file = File(root, relative)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    @Test
    fun `same tree twice is the same digest`() = runBlocking {
        val root = tmp.newFolder("tree")
        f(root, "notes.md", "plan")
        f(root, "sub/todo.md", "items")
        f(root, "crushrc", "#!/bin/sh\necho hi")

        val a = ProjectTreeFingerprint.of(root)
        val b = ProjectTreeFingerprint.of(root)
        assertEquals("stat-only determinism: the walk IS the metric", a, b)
    }

    @Test
    fun `rewrite rename and mtime drift flip the digest`() = runBlocking {
        val root = tmp.newFolder("drift")
        val file = f(root, "plan.md", "one")
        assertEquals(
            "the baseline is stable before anything moves",
            ProjectTreeFingerprint.of(root),
            ProjectTreeFingerprint.of(root),
        )
        val baseline = ProjectTreeFingerprint.of(root)

        // The rewrite's deterministic flip rides SIZE, not mtime: a
        // same-size rewrite can land inside the filesystem's mtime
        // resolution and then the digest HOLDS - the metric's own
        // documented false-negative family (ProjectTreeFingerprint:
        // "a same-size rewrite landing inside the filesystem's mtime
        // resolution is DOCUMENTED, not discriminated"). First CI
        // specimen 2026-11-10, 04:56 Z (run 42758736412): a 3->3 rewrite
        // on the runner landed in the same millisecond bucket as the
        // original write, lastModified() returned one value for both
        // walks, size was equal, and the unconditional "usually mtime
        // together" assert - which was the flake, the metric behaved
        // exactly as documented - failed (local Windows runs never
        // reproduced it; the ms-bucket collision is runner-speed luck).
        // So: the unconditional flip asserts ride the stat changes a
        // stat-only walk CAN always see (size and path), and the pure
        // mtime component is pinned by an explicit setLastModified.
        file.writeText("two-three")
        assertNotEquals("a rewrite flips (size changes)", baseline, ProjectTreeFingerprint.of(root))
        val baseline2 = ProjectTreeFingerprint.of(root)

        assertTrue(file.renameTo(File(root, "moved.md")))
        assertNotEquals("a path change flips (rename)", baseline2, ProjectTreeFingerprint.of(root))
        // Re-write with the SAME length on purpose: from here on the flip
        // under test is the mtime component alone, exactly the metric's
        // "visible identity" row.
        val file2 = f(root, "moved.md", "two-three")
        val baseline3 = ProjectTreeFingerprint.of(root)

        assertTrue(file2.setLastModified(System.currentTimeMillis() + 60_000))
        assertNotEquals("a pure mtime change flips (the visible identity metric)", baseline3, ProjectTreeFingerprint.of(root))
    }

    @Test
    fun `empty tree is stable - and a real state, not an error`() = runBlocking {
        val empty = tmp.newFolder("empty")
        val d1 = ProjectTreeFingerprint.of(empty)
        assertEquals("the empty tree walks to the same digest twice", d1, ProjectTreeFingerprint.of(empty))

        val one = tmp.newFolder("one")
        f(one, "a.md", "x")
        assertNotEquals("one file is a different state from none", d1, ProjectTreeFingerprint.of(one))
    }

    @Test
    fun `a missing or non-directory root is a deterministic empty digest`() = runBlocking {
        val ghost = File(tmp.root, "never-existed")
        assertEquals(
            "absent root = empty record set, deterministic",
            ProjectTreeFingerprint.of(ghost),
            ProjectTreeFingerprint.of(ghost),
        )
        val regular = tmp.newFile("barely-a-file.txt")
        assertEquals(
            "a FILE where a folder belongs is the same empty shape",
            ProjectTreeFingerprint.of(regular),
            ProjectTreeFingerprint.of(regular),
        )
    }

    @Test
    fun `noise dirs skip and the trust-relevant files stay counted`() = runBlocking {
        val root = tmp.newFolder("mixed")
        f(root, "src/App.kt", "fun x()")
        f(root, "build/out.o", "noise")
        f(root, ".git/HEAD", "git noise")
        f(root, "crushrc", "probe")

        val records = ProjectTreeFingerprint.recordsOf(root)
        val joined = records.joinToString("")
        assertTrue("crushrc rides - it is the trust-relevant surface", joined.contains("crushrc\t"))
        assertTrue("source trees ride", joined.contains("src/App.kt\t"))
        assertTrue(".git content does not", !joined.contains(".git"))
        assertTrue("build content does not", !joined.contains("build"))
        assertTrue("the stream is sorted (path dominates)", records == records.sorted())
    }

    @Test
    fun `unreadable file and dir take deterministic marker rows`() {
        val root = tmp.newFolder("locked")
        f(root, "a.md", "x")
        val secret = f(root, "secret", "y")
        f(root, "vault/s.md", "z")

        val rows = ProjectTreeFingerprint.recordsOf(root, canRead = { it != secret && it.name != "vault" })
        assertTrue(
            "the refused file is an EXPLICIT marker, never silent or invented",
            rows.contains("secret\t?\t?\n"),
        )
        assertTrue(
            "readable files carry real stats",
            rows.any { it.startsWith("a.md\t") && !it.contains("?") },
        )
        assertTrue(
            "the refused directory is its own explicit marker row",
            rows.any { it.startsWith("vault/") && it.endsWith("\t?\t?\n") },
        )
        // Determinism across two runs of the SAME state (plan f2), and the
        // digest over marker rows is stable.
        val again = ProjectTreeFingerprint.recordsOf(root, canRead = { it != secret && it.name != "vault" })
        assertEquals("same state, same records", rows, again)
        assertEquals(
            "same records, same digest",
            ProjectTreeFingerprint.digestOf(rows),
            ProjectTreeFingerprint.digestOf(again),
        )
    }

    @Test
    fun `a zero-byte file never looks like the unreadable marker`() {
        assertNotEquals(
            "0 is a real size, ? is the refusal - different rows, different digests",
            ProjectTreeFingerprint.digestOf(listOf("empty.md\t0\t123\n")),
            ProjectTreeFingerprint.digestOf(listOf("empty.md\t?\t?\n")),
        )
    }
}
