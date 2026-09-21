package com.tinyggrok.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDefaultsTest {

    @Test
    fun defaultIsGrok47WithGrok46AsBackup() {
        assertEquals("grok-4.7", AppDefaults.DEFAULT_MODEL)
        assertEquals("grok-4.6", AppDefaults.BACKUP_MODEL)
        // The backup has to be something the API will actually accept.
        assertTrue(AppDefaults.isKnownChatModel(AppDefaults.BACKUP_MODEL))
    }

    @Test
    fun theOfferedModelsAre47And46And45() {
        val ids = AppDefaults.CHAT_MODELS.map { it.second }
        assertEquals(listOf("grok-4.7", "grok-4.6", "grok-4.5"), ids)
        assertTrue(AppDefaults.isKnownChatModel("grok-4.7"))
        assertTrue(AppDefaults.isKnownChatModel("grok-4.6"))
        assertTrue(AppDefaults.isKnownChatModel("grok-4.5"))
        assertFalse(AppDefaults.isKnownChatModel("grok-4.3"))
    }

    /**
     * Grok 4.7 Fast is the same model on faster hardware at twice the rates, served only
     * through Cursor and Grok Build. Sending its name to the public API buys a rejection
     * and a silent drop to the backup model, so it must not be offered.
     */
    @Test
    fun noFastVariantIsOffered() {
        assertTrue(AppDefaults.CHAT_MODELS.none { (label, id) ->
            "fast" in label.lowercase() || "fast" in id.lowercase()
        })
        assertFalse(AppDefaults.isKnownChatModel("grok-4.7-fast"))
        assertEquals("grok-4.7", AppDefaults.normalizeChatModel("grok-4.7-fast"))
    }

    @Test
    fun unknownAndLegacyModelsNormalizeToDefault() {
        assertEquals("grok-4.7", AppDefaults.normalizeChatModel(null))
        assertEquals("grok-4.7", AppDefaults.normalizeChatModel("grok-4.3"))
        assertEquals("grok-4.7", AppDefaults.normalizeChatModel("grok-4"))
    }

    @Test
    fun aModelAlreadyChosenIsKept() {
        // Upgrading the app must not move anyone off the model they picked.
        assertEquals("grok-4.6", AppDefaults.normalizeChatModel("grok-4.6"))
        assertEquals("grok-4.5", AppDefaults.normalizeChatModel("grok-4.5"))
        assertEquals("grok-4.7", AppDefaults.normalizeChatModel("grok-4.7"))
    }
}
