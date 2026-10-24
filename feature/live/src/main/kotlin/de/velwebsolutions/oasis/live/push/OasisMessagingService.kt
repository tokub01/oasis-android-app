package de.velwebsolutions.oasis.live.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import de.velwebsolutions.oasis.core.di.AppScope
import de.velwebsolutions.oasis.core.model.PushPreview
import de.velwebsolutions.oasis.core.network.CommonApi
import de.velwebsolutions.oasis.live.LiveCoordinator
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives push hints over Firebase Cloud Messaging (PushNotificationDelegate on iOS). The push carries only a generic
 * line and a random `preview` reference; the named text ("Jonas Weber · Hat auf deinen Tropfen geantwortet.") comes
 * from the Oasis server with the member's token, so no name passes through Google. While the app runs, the counters
 * are refreshed as well. Without google-services.json Firebase never starts this service.
 */
@AndroidEntryPoint
class OasisMessagingService : FirebaseMessagingService() {
    @Inject lateinit var commonApi: CommonApi

    @Inject lateinit var live: LiveCoordinator

    @Inject lateinit var push: PushRegistrar

    @Inject @AppScope
    lateinit var scope: CoroutineScope

    override fun onNewToken(token: String) {
        scope.launch { push.tokenChanged() }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val generic = PushContent.from(message.data, message.notification?.title, message.notification?.body)
        // Called on a worker thread with time to finish; the generic line stays when the server is slow or forgot it.
        val preview = PushPreview.reference(message.data)?.let { reference ->
            runBlocking { withTimeoutOrNull(PREVIEW_TIMEOUT_MS) { commonApi.pushPreview(reference) } }
        }
        PushNotifications.show(this, generic.with(preview))
        scope.launch { live.refresh() }
    }

    private companion object {
        const val PREVIEW_TIMEOUT_MS = 8_000L
    }
}
