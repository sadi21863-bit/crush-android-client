package com.crush.mobile.di

import com.crush.mobile.project.ProjectRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * The A11/A12 binding-token codec (2026-11-05). The persisted token is the
 * ONLY durable record of "which surface did the user last use", so decoding
 * every shape fail-safely is the whole point:
 *
 * - legacy and the chat pool are named constants;
 * - a project row stores its FULL uuid - the derived instance key
 *   (`p<first8-uuid>`) is lossy and cannot be reversed into a row id - and
 *   the container re-resolves the row from the store at restore time;
 * - malformed/unparseable tokens decode to NULL (= caller keeps the legacy
   * default), never throw.
 */
class EngineBindingTokenTest {

    @Test
    fun `legacy and chat have stable tokens`() {
        assertEquals("legacy", AppContainer.bindingToToken(AppContainer.EngineBinding.Legacy))
        assertEquals("chat", AppContainer.bindingToToken(AppContainer.EngineBinding.ChatPool))
    }

    @Test
    fun `a project token carries the FULL uuid not the lossy instance key`() {
        val id = UUID.fromString("536fa26e-0000-0000-0000-000000000000")
        val ref = ProjectRef(id, "Test project folder", File("/storage/emulated/0/Test project folder"))
        val token = AppContainer.bindingToToken(AppContainer.EngineBinding.Project(ref))

        assertEquals("536fa26e-0000-0000-0000-000000000000", token.removePrefix("proj:"))
        // The first8 instance key form would NOT round-trip; the token must.
        assertNotEquals("536fa26e", token.removePrefix("proj:"))
    }

    @Test
    fun `a project token decodes back to the same row id`() {
        val id = UUID.randomUUID()
        val ref = ProjectRef(id, "any", File("/tmp/any"))
        val token = AppContainer.bindingToToken(AppContainer.EngineBinding.Project(ref))

        assertEquals(id, AppContainer.tokenToProjectId(token))
    }

    @Test
    fun `non-project tokens decode to null`() {
        assertNull(AppContainer.tokenToProjectId("legacy"))
        assertNull(AppContainer.tokenToProjectId("chat"))
        assertNull(AppContainer.tokenToProjectId(""))
        assertNull(AppContainer.tokenToProjectId("p536fa26e"))
    }

    @Test
    fun `a malformed uuid inside a project token fails safe to null`() {
        assertNull(AppContainer.tokenToProjectId("proj:not-a-uuid"))
        assertNull(AppContainer.tokenToProjectId("proj:"))
    }
}
