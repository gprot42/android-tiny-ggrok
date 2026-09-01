package com.tinyggrok.app.data.share

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot inbox for share-sheet / process-text payloads.
 *
 * [MainActivity] offers; [com.tinyggrok.app.ui.viewmodel.ChatViewModel] consumes
 * into the prompt/attachments. A separate event asks navigation to pop back to chat
 * so a share received while the user is on Settings still lands on the composer.
 */
@Singleton
class IncomingShareRepository @Inject constructor() {
    private val _pending = MutableStateFlow<IncomingShare?>(null)
    val pending: StateFlow<IncomingShare?> = _pending.asStateFlow()

    private val _navigateToChat = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val navigateToChat: SharedFlow<Unit> = _navigateToChat.asSharedFlow()

    fun offer(share: IncomingShare) {
        if (share.isEmpty) return
        _pending.value = share
        _navigateToChat.tryEmit(Unit)
    }

    fun consume(): IncomingShare? = _pending.getAndUpdate { null }
}
