package com.tinyggrok.app.ui.screens

/**
 * Tallest an answer's view may be, in pixels.
 *
 * Compose cannot lay out anything taller than 262,143 px: asked to, it throws, and the
 * app dies. That is what "app sometimes quits and I lose the prompt output" turned out to
 * be. Android recorded two crashes on the reporting phone, both
 * `IllegalArgumentException: Can't represent a width of 0 and height of 316076 (then
 * 434309) in Constraints`, thrown while laying out the chat. An answer is rendered in a
 * WebView whose height is read back from the page and handed to Compose as is, so one
 * reading past that limit ended the process, and the answer went with it.
 *
 * What produced such a reading is not settled. 434,309 px is over 6,000 lines at the
 * chat's type size, far beyond an ordinary answer: tried on an emulator, a 25,000
 * character answer measured 42,903 px and behaved, and neither a page laid out before it
 * had a width nor CSS sized to the screen made heights run away. So whatever the page
 * reports, the view is capped here with room to spare, and a reading over the cap is
 * logged with enough detail (see ChatScreen) to identify the cause if it recurs.
 * Two hundred thousand pixels is still 3,000 lines or so; an answer longer than that
 * can be copied or shared whole.
 */
internal const val MAX_ANSWER_HEIGHT_PX = 200_000

/** The height an answer's view may be given for a measured content height. */
internal fun answerViewHeight(measuredPx: Int): Int = measuredPx.coerceIn(0, MAX_ANSWER_HEIGHT_PX)
