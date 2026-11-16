package com.crush.mobile.ui.screens.chat

import com.crush.mobile.data.api.LiveZenModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2A-04 / T2A-04a (V2 plan §5.3.1): model identity and the two badge rules.
 *
 * T2A-04a is the LEGACY SUFFIX RULE. It deliberately pins today's pixels,
 * including the defect: `isFree` is not the badge source until Phase 6's
 * T6-01 replaces this test. Phase 6 deletes the legacy assertions in the same
 * commit that adds the catalogue rule.
 */
class ChatModelMetadataTest {

    @Test
    fun `an absent selected model is identified by id and never throws`() {
        val selected = ChatUiState(
            selectedModel = "not-in-catalogue",
            models = emptyList(),
        ).toViewState(ChatHostInputs()).model.selected!!

        assertEquals("not-in-catalogue", selected.id)
        assertEquals("not-in-catalogue", selected.displayName)
        assertFalse(selected.presentInCatalogue)
        assertEquals(FreeMarker.Hidden, selected.freeMarker)
        assertNull(selected.supportsImages)
    }

    @Test
    fun `a present model keeps its catalogue name and image capability`() {
        val selected = ChatUiState(
            selectedModel = "vision",
            models = listOf(LiveZenModel("vision", "Vision Model", isFree = false, supportsImages = true)),
        ).toViewState(ChatHostInputs()).model.selected!!

        assertTrue(selected.presentInCatalogue)
        assertEquals("Vision Model", selected.displayName)
        assertTrue(selected.supportsImages == true)
    }

    // ---------------------------------------------------- T2A-04a: legacy rule

    @Test
    fun `T2A-04a a free model without the suffix shows no marker today`() {
        val selected = ChatUiState(
            // Deliberately does NOT end in "-free": only the catalogue isFree
            // flag says this one is free (Phase 6's future trigger).
            selectedModel = "catalogue-zero",
            models = listOf(LiveZenModel("catalogue-zero", "Catalogue Zero", isFree = true)),
        ).toViewState(ChatHostInputs()).model.selected!!

        // Current pixels: the suffix rule, not isFree. Phase 6 inverts this.
        assertEquals(FreeMarker.Hidden, selected.freeMarker)
    }

    @Test
    fun `T2A-04a a paid model with the suffix shows the marker today`() {
        val selected = ChatUiState(
            selectedModel = "legacy-free",
            models = listOf(LiveZenModel("legacy-free", "Legacy Free", isFree = false)),
        ).toViewState(ChatHostInputs()).model.selected!!

        // The known defect, pinned so extraction cannot quietly change it.
        assertEquals(FreeMarker.Shown, selected.freeMarker)
    }

    @Test
    fun `a blank selection projects no selected model`() {
        val view = ChatUiState(selectedModel = "").toViewState(ChatHostInputs())

        assertNull(view.model.selected)
        assertTrue(view.model.catalogue.items.isEmpty())
    }

    @Test
    fun `no price or cost is synthesized on any model view`() {
        // CORR-002: price detail is deferred, so no accessor may exist. A
        // missing price means "not known free", never $0 and never a dash
        // that reads as zero.
        listOf(ModelItem::class.java, SelectedModelView::class.java).forEach { type ->
            val names = type.declaredFields.map { it.name.lowercase() }
            assertTrue("$type declares $names", names.none { "price" in it || "cost" in it })
        }
    }
}
