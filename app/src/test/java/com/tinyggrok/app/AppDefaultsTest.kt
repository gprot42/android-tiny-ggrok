package com.tinyggrok.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDefaultsTest {

    @Test
    fun defaultIsGrok46() {
        assertEquals("grok-4.6", AppDefaults.DEFAULT_MODEL)
        assertEquals("grok-4.5", AppDefaults.BACKUP_MODEL)
    }

    @Test
    fun only46And45AreOffered() {
        val ids = AppDefaults.CHAT_MODELS.map { it.second }
        assertEquals(listOf("grok-4.6", "grok-4.5"), ids)
        assertFalse(AppDefaults.isKnownChatModel("grok-4.3"))
        assertTrue(AppDefaults.isKnownChatModel("grok-4.6"))
        assertTrue(AppDefaults.isKnownChatModel("grok-4.5"))
    }

    @Test
    fun unknownAndLegacyModelsNormalizeToDefault() {
        assertEquals("grok-4.6", AppDefaults.normalizeChatModel(null))
        assertEquals("grok-4.6", AppDefaults.normalizeChatModel("grok-4.3"))
        assertEquals("grok-4.6", AppDefaults.normalizeChatModel("grok-4"))
        assertEquals("grok-4.5", AppDefaults.normalizeChatModel("grok-4.5"))
        assertEquals("grok-4.6", AppDefaults.normalizeChatModel("grok-4.6"))
    }
}
