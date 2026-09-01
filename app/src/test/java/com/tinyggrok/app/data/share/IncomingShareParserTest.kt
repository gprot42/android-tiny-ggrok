package com.tinyggrok.app.data.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingShareParserTest {

    @Test
    fun ignoresLauncherAndUnknownActions() {
        assertNull(
            IncomingShareParser.parse(
                action = "android.intent.action.MAIN",
                mimeType = null,
                extraText = "hello",
                extraSubject = null,
                extraProcessText = null
            )
        )
        assertNull(
            IncomingShareParser.parse(
                action = "android.intent.action.VIEW",
                mimeType = "text/plain",
                extraText = "hello",
                extraSubject = null,
                extraProcessText = null
            )
        )
    }

    @Test
    fun sendTextBecomesPrompt() {
        val share = IncomingShareParser.parse(
            action = IncomingShareParser.ACTION_SEND,
            mimeType = "text/plain",
            extraText = "  What is this URL? https://example.com  ",
            extraSubject = null,
            extraProcessText = null
        )
        assertEquals("What is this URL? https://example.com", share?.text)
        assertTrue(share!!.imageUris.isEmpty())
    }

    @Test
    fun prependsSubjectWhenMissingFromBody() {
        val share = IncomingShareParser.parse(
            action = IncomingShareParser.ACTION_SEND,
            mimeType = "text/plain",
            extraText = "The article body",
            extraSubject = "Headline",
            extraProcessText = null
        )
        assertEquals("Headline\n\nThe article body", share?.text)
    }

    @Test
    fun doesNotDuplicateSubjectAlreadyInBody() {
        val share = IncomingShareParser.parse(
            action = IncomingShareParser.ACTION_SEND,
            mimeType = "text/plain",
            extraText = "Headline\n\nThe article body",
            extraSubject = "Headline",
            extraProcessText = null
        )
        assertEquals("Headline\n\nThe article body", share?.text)
    }

    @Test
    fun subjectOnlyIsEnough() {
        val share = IncomingShareParser.parse(
            action = IncomingShareParser.ACTION_SEND,
            mimeType = "text/plain",
            extraText = null,
            extraSubject = "Just a title",
            extraProcessText = null
        )
        assertEquals("Just a title", share?.text)
    }

    @Test
    fun processTextBecomesPrompt() {
        val share = IncomingShareParser.parse(
            action = IncomingShareParser.ACTION_PROCESS_TEXT,
            mimeType = "text/plain",
            extraText = null,
            extraSubject = null,
            extraProcessText = "selected passage"
        )
        assertEquals("selected passage", share?.text)
        assertTrue(share!!.imageUris.isEmpty())
    }

    @Test
    fun processTextIgnoresStreams() {
        val share = IncomingShareParser.parse(
            action = IncomingShareParser.ACTION_PROCESS_TEXT,
            mimeType = "text/plain",
            extraText = null,
            extraSubject = null,
            extraProcessText = "hello",
            extraStreamUris = listOf("content://other/photo.jpg")
        )
        assertEquals("hello", share?.text)
        assertTrue(share!!.imageUris.isEmpty())
    }

    @Test
    fun sendImageAttachesUri() {
        val share = IncomingShareParser.parse(
            action = IncomingShareParser.ACTION_SEND,
            mimeType = "image/jpeg",
            extraText = null,
            extraSubject = null,
            extraProcessText = null,
            extraStreamUris = listOf("content://media/1")
        )
        assertNull(share?.text)
        assertEquals(listOf("content://media/1"), share?.imageUris)
    }

    @Test
    fun sendImageWithCaptionKeepsBoth() {
        val share = IncomingShareParser.parse(
            action = IncomingShareParser.ACTION_SEND,
            mimeType = "image/png",
            extraText = "what is in this photo?",
            extraSubject = null,
            extraProcessText = null,
            extraStreamUris = listOf("content://media/2")
        )
        assertEquals("what is in this photo?", share?.text)
        assertEquals(listOf("content://media/2"), share?.imageUris)
    }

    @Test
    fun sendMultipleImagesDedupesClipAndStream() {
        val share = IncomingShareParser.parse(
            action = IncomingShareParser.ACTION_SEND_MULTIPLE,
            mimeType = "image/*",
            extraText = null,
            extraSubject = null,
            extraProcessText = null,
            extraStreamUris = listOf("content://media/a", "content://media/b"),
            clipDataUris = listOf("content://media/b", "content://media/c")
        )
        assertEquals(
            listOf("content://media/a", "content://media/b", "content://media/c"),
            share?.imageUris
        )
    }

    @Test
    fun skipsNonImageStreamsForPlainTextShare() {
        val share = IncomingShareParser.parse(
            action = IncomingShareParser.ACTION_SEND,
            mimeType = "text/plain",
            extraText = "notes",
            extraSubject = null,
            extraProcessText = null,
            extraStreamUris = listOf("content://downloads/file.pdf")
        )
        assertEquals("notes", share?.text)
        assertTrue(share!!.imageUris.isEmpty())
    }

    @Test
    fun includesImageExtensionEvenWhenMimeIsText() {
        val share = IncomingShareParser.parse(
            action = IncomingShareParser.ACTION_SEND,
            mimeType = "text/plain",
            extraText = "look",
            extraSubject = null,
            extraProcessText = null,
            extraStreamUris = listOf("file:///sdcard/pic.JPEG")
        )
        assertEquals(listOf("file:///sdcard/pic.JPEG"), share?.imageUris)
    }

    @Test
    fun emptySendIsNull() {
        assertNull(
            IncomingShareParser.parse(
                action = IncomingShareParser.ACTION_SEND,
                mimeType = "text/plain",
                extraText = "   ",
                extraSubject = null,
                extraProcessText = null
            )
        )
    }

    @Test
    fun looksLikeImageUri() {
        assertTrue(IncomingShareParser.looksLikeImageUri("content://x/photo.webp"))
        assertTrue(IncomingShareParser.looksLikeImageUri("https://cdn.example/a.png?w=8"))
        assertFalse(IncomingShareParser.looksLikeImageUri("content://downloads/doc.pdf"))
    }

    @Test
    fun shouldIncludeImages() {
        assertTrue(IncomingShareParser.shouldIncludeImages(null))
        assertTrue(IncomingShareParser.shouldIncludeImages("*/*"))
        assertTrue(IncomingShareParser.shouldIncludeImages("image/jpeg"))
        assertFalse(IncomingShareParser.shouldIncludeImages("text/plain"))
        assertFalse(IncomingShareParser.shouldIncludeImages("application/pdf"))
    }

    @Test
    fun formatConversationForShare() {
        val text = formatConversationForShare(
            listOf(
                "user" to "hello",
                "assistant" to "hi there"
            )
        )
        assertEquals("You:\nhello\n\nGrok:\nhi there", text)
    }

    @Test
    fun formatConversationForShareEmpty() {
        assertEquals("", formatConversationForShare(emptyList()))
    }
}
