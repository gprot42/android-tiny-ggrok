package com.tinyggrok.app.data.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingShareRepositoryTest {

    @Test
    fun offerThenConsume() {
        val repo = IncomingShareRepository()
        val share = IncomingShare(text = "hello")
        repo.offer(share)
        assertEquals(share, repo.pending.value)
        assertEquals(share, repo.consume())
        assertNull(repo.pending.value)
        assertNull(repo.consume())
    }

    @Test
    fun emptyOfferIgnored() {
        val repo = IncomingShareRepository()
        repo.offer(IncomingShare())
        repo.offer(IncomingShare(text = "   ", imageUris = emptyList()))
        assertNull(repo.pending.value)
    }

    @Test
    fun latestOfferWins() {
        val repo = IncomingShareRepository()
        repo.offer(IncomingShare(text = "first"))
        repo.offer(IncomingShare(text = "second"))
        assertEquals("second", repo.pending.value?.text)
    }
}
