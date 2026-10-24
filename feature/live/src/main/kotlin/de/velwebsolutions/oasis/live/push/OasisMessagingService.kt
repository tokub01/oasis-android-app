package de.velwebsolutions.oasis.live.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives push hints over Firebase Cloud Messaging and hands them to [PushMessages]. Without google-services.json
 * Firebase never starts this service.
 */
@AndroidEntryPoint
class OasisMessagingService : FirebaseMessagingService() {
    /** Shows pushes and registers new tokens. */
    @Inject lateinit var messages: PushMessages

    override fun onNewToken(token: String) = messages.tokenChanged()

    override fun onMessageReceived(message: RemoteMessage) = messages.received(message)
}
