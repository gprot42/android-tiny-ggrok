package com.tinyggrok.app.ui.screens

/**
 * Whether the chat list should still be pinned to the bottom after an update.
 *
 * Follow mode belongs to the user: it is switched on when they send something, and off
 * the moment they scroll up to read. The one rule worth stating separately is that a
 * send re-engages it only as it *starts*. Reported as "scrolling up/down gets stuck,
 * possibly whilst it's doing a web search": a streamed answer updates every few
 * milliseconds and, while re-engaging on every update, scrolling up was undone before
 * the finger had left the glass. Web-search answers are the longest, so they showed it
 * worst, but any long answer did it.
 */
internal fun followsBottomAfter(
    wasFollowing: Boolean,
    isSending: Boolean,
    wasSending: Boolean
): Boolean = wasFollowing || (isSending && !wasSending)
