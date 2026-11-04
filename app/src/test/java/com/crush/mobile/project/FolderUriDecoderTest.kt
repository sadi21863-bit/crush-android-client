package com.crush.mobile.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the SAF-tree -> engine-workspace contract of [FolderUriDecoder]
 * (feature: import a phone folder as a project, 2026-11-04). The user's
 * first request named a folder with SPACES in it - that case is pinned
 * first so URI-y detours can never quietly percent-encode the path the
 * engine is handed.
 */
class FolderUriDecoderTest {

    @Test
    fun `folder with a space decodes verbatim`() {
        assertEquals(
            "/storage/emulated/0/Test project folder",
            FolderUriDecoder.primaryPathFromTreeDocumentId("primary:Test project folder")
        )
    }

    @Test
    fun `nested path keeps its slashes`() {
        assertEquals(
            "/storage/emulated/0/Documents/work/code",
            FolderUriDecoder.primaryPathFromTreeDocumentId("primary:Documents/work/code")
        )
    }

    @Test
    fun `the primary root itself maps to the volume root`() {
        assertEquals(
            "/storage/emulated/0",
            FolderUriDecoder.primaryPathFromTreeDocumentId("primary:")
        )
    }

    @Test
    fun `non-primary volumes are refused`() {
        assertNull(FolderUriDecoder.primaryPathFromTreeDocumentId("downloads:Test project folder"))
        assertNull(FolderUriDecoder.primaryPathFromTreeDocumentId("XXXX-usbstorage:code"))
    }

    @Test
    fun `null and foreign shapes are refused`() {
        assertNull(FolderUriDecoder.primaryPathFromTreeDocumentId(null))
        assertNull(FolderUriDecoder.primaryPathFromTreeDocumentId("home:"))
    }

    @Test
    fun `traversal is refused outright`() {
        assertNull(FolderUriDecoder.primaryPathFromTreeDocumentId("primary:../secrets"))
        assertNull(FolderUriDecoder.primaryPathFromTreeDocumentId("primary:a/../../etc"))
    }

    @Test
    fun `display name is the last segment`() {
        assertEquals(
            "Test project folder",
            FolderUriDecoder.displayNameFor("/storage/emulated/0/Test project folder")
        )
        assertEquals(
            "code",
            FolderUriDecoder.displayNameFor("/storage/emulated/0/Documents/work/code")
        )
    }
}
