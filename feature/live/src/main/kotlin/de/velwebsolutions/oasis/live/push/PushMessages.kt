package de.velwebsolutions.oasis.live.push

import android.content.Context
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * What a push does (PushNotificationDelegate on iOS): it carries only a generic line and a random `preview` reference;
 * the named text ("Jonas Weber · Hat auf deinen Tropfen geantwortet.") comes from the Oasis server with the member's
 * token, so no name passes through Google. While the app runs, the counters are refreshed as well.
 */
class PushMessages @Inject constructor(
    @ApplicationContext private val context: Context,
    private val commonApi: CommonApi,
    private val live: LiveCoordinator,
    private val push: PushRegistrar,
    @AppScope private val scope: CoroutineScope
) {
    /** Firebase handed out a new token: register it when allowed. */
    fun tokenChanged() {
        scope.launch { push.tokenChanged() }
    }

    /**
     * Shows the push. Called on Firebase's worker thread, which has time to finish: the named text is waited for, and
     * the generic line stays when the server is slow or forgot the reference.
     */
    fun received(message: RemoteMessage) {
        val notification = message.notification
        val generic = PushContent.from(message.data, notification?.title, notification?.body)
        val preview = PushPreview.reference(message.data)?.let { reference ->
            runBlocking { withTimeoutOrNull(PREVIEW_TIMEOUT_MS) { commonApi.pushPreview(reference) } }
        }
        PushNotifications.show(context, generic.with(preview))
        scope.launch { live.refresh() }
    }

    private companion object {
        const val PREVIEW_TIMEOUT_MS = 8_000L
    }
}
