package de.velwebsolutions.oasis.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.velwebsolutions.oasis.chat.core.ChatContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

/**
 * The base of the chat view models. Actions are `suspend` (the screens show a spinner on the button meanwhile, like
 * iOS' AsyncButton), but run in [viewModelScope], so leaving the screen does not cut a key upload in half.
 */
abstract class ChatViewModel(val context: ChatContext) : ViewModel() {
    protected val vault get() = context.vault

    protected suspend fun <T> inScope(block: suspend CoroutineScope.() -> T): T = viewModelScope.async(block = block).await()
}
