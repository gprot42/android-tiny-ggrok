package com.tinyggrok.app.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScrollFollowTest {

    @Test
    fun `sending re-engages follow mode as it starts`() {
        assertTrue(followsBottomAfter(wasFollowing = false, isSending = true, wasSending = false))
    }

    @Test
    fun `scrolling up during a long answer stays scrolled up`() {
        // The reported fault: every streamed token is an update with sending still true,
        // and each one used to drag the list back down.
        var following = false
        repeat(200) {
            following = followsBottomAfter(following, isSending = true, wasSending = true)
        }
        assertFalse(following)
    }

    @Test
    fun `a reader at the bottom is still carried along`() {
        var following = true
        repeat(200) {
            following = followsBottomAfter(following, isSending = true, wasSending = true)
        }
        assertTrue(following)
    }

    @Test
    fun `the next send brings a scrolled-up reader back down`() {
        var following = followsBottomAfter(wasFollowing = false, isSending = false, wasSending = true)
        assertFalse(following)
        following = followsBottomAfter(following, isSending = true, wasSending = false)
        assertTrue(following)
    }
}
