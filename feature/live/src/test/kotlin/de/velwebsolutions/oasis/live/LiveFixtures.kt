package de.velwebsolutions.oasis.live

import de.velwebsolutions.oasis.core.realtime.RealtimeEvent
import de.velwebsolutions.oasis.core.realtime.RealtimeService
import de.velwebsolutions.oasis.live.api.HintSettings
import de.velwebsolutions.oasis.live.data.DefaultLiveRepository
import de.velwebsolutions.oasis.live.data.LiveRepository
import de.velwebsolutions.oasis.live.push.PushAuthorization
import de.velwebsolutions.oasis.live.push.PushRegistrar
import de.velwebsolutions.oasis.live.push.PushStore
import de.velwebsolutions.oasis.live.push.PushSystem
import de.velwebsolutions.oasis.testing.StubTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Real answers of the Laravel API (copied from GET /api/v1/… against the demo data, like the iOS LiveJSON). */
object LiveJson {
    const val HINTS =
        """{"data":[{"id":"eab9c863-d27a-5296-a62f-d55d1cdf8beb","kind":"follow","label":"Neue Person in deiner Karawane · Emil Novak","url":"http:\/\/localhost\/profil\/emil","excerpt":"","unread":true,"created_at":"2026-10-22T13:32:17.000000Z","group":"Heute"},{"id":"cc1d9f79-8e2e-51b3-aea0-e9ad195089cb","kind":"reply","label":"Antwort auf deinen Tropfen · Ben Hoffmann","url":"http:\/\/localhost\/tropfen\/12","excerpt":"Schöne Antwort","unread":false,"created_at":"2026-10-20T08:00:00.000000Z","group":"Diese Woche"},{"id":"151087f2-6b99-5cc9-9ec4-547cf2f4a79f","kind":"account","label":"Beitrag eingegangen","url":null,"excerpt":"Danke!","unread":false,"created_at":"2026-10-01T08:00:00.000000Z","group":"Früher"}],"meta":{"current_page":1,"last_page":2,"per_page":20,"total":23}}"""
    const val LAST_PAGE =
        """{"data":[{"id":"x1","kind":"follow","label":"Neue Person in deiner Karawane · Lea","url":"http:\/\/localhost\/profil\/lea","excerpt":"","unread":false,"created_at":"2026-09-01T08:00:00.000000Z","group":"Früher"}],"meta":{"current_page":2,"last_page":2,"per_page":20,"total":23}}"""
    const val SETTINGS =
        """{"data":{"kinds":[{"key":"follow","label":"Neue Person in deiner Karawane","enabled":true},{"key":"reply","label":"Antwort auf deinen Tropfen","enabled":true},{"key":"friend_request","label":"Freundschaftsanfrage","enabled":true}],"digest":false,"quiet_enabled":false,"quiet_from":"22:00","quiet_until":"07:00","paused_until":null,"pause_days":[1,3,7],"chat":[{"key":"chat_email","title":"E-Mail bei neuen Chat-Nachrichten","help":"Standardmäßig an.","enabled":true},{"key":"chat_push","title":"Push-Hinweis bei neuen Chat-Nachrichten","help":"Standardmäßig aus.","enabled":false}],"push":{"available":true,"devices":[{"id":4,"type":"app","device":"Oasis-App auf iOS","since":"2026-10-22T15:00:00.000000Z","current":true},{"id":9,"type":"browser","device":"Chrome auf Android","since":"2026-10-01T15:00:00.000000Z","current":false}]}}}"""

    fun saved(message: String, paused: String = "null") =
        """{"data":{"kinds":[{"key":"follow","label":"Neue Person in deiner Karawane","enabled":true},{"key":"reply","label":"Antwort auf deinen Tropfen","enabled":false},{"key":"friend_request","label":"Freundschaftsanfrage","enabled":true}],"digest":true,"quiet_enabled":true,"quiet_from":"21:30","quiet_until":"06:15","paused_until":$paused,"pause_days":[1,3,7],"chat":[{"key":"chat_email","title":"E-Mail bei neuen Chat-Nachrichten","help":"Standardmäßig an.","enabled":true},{"key":"chat_push","title":"Push-Hinweis bei neuen Chat-Nachrichten","help":"Standardmäßig aus.","enabled":false}],"push":{"available":true,"devices":[]}},"message":"$message"}"""

    const val COUNTS =
        """{"data":{"chats":2,"chat_ids":[4,7],"requests":1,"hints":3}}"""
    const val CONFIG =
        """{"data":{"reverb":{"key":"oasis-local-key","host":"localhost","port":8080,"scheme":"http"},"push":{"available":true}}}"""
    const val DEVICE =
        """{"data":{"id":5,"type":"app","device":"Oasis-App auf Android","since":"2026-10-22T15:00:00.000000Z","current":true},"message":"ok"}"""
}

fun repository(transport: StubTransport) = DefaultLiveRepository(transport.client)

/** The real repository over the stubbed API, with a hook that runs while settings are being saved. */
class HookedRepository(inner: LiveRepository, var whileSaving: suspend () -> Unit = {}) : LiveRepository by inner {
    private val real = inner

    override suspend fun save(settings: HintSettings) = real.save(settings).also { whileSaving() }
}

/** A push system that records what the registrar asks Android to do. */
class FakePushSystem(
    var status: PushAuthorization = PushAuthorization.Authorized,
    override var isConfigured: Boolean = true,
    var token: String? = "fcm-token"
) : PushSystem {
    var answers = mutableListOf<Boolean>()
    var tokenRequests = 0
    var deletedTokens = 0

    override fun authorization() = status

    override fun permissionAnswered(granted: Boolean) {
        answers += granted
        status = if (granted) PushAuthorization.Authorized else PushAuthorization.Denied
    }

    override suspend fun token(): String? {
        tokenRequests += 1
        return token
    }

    override suspend fun deleteToken() {
        deletedTokens += 1
    }

    var openedSettings = 0

    override fun openSettings() {
        openedSettings += 1
    }
}

class MemoryPushStore(override var deviceId: Int? = null, override var optedOut: Boolean = false) : PushStore

fun registrar(system: FakePushSystem = FakePushSystem(), store: PushStore = MemoryPushStore(), backendAcceptsFcm: Boolean = true) =
    PushRegistrar(system, store, backendAcceptsFcm)

/** A realtime service whose events the test sends. */
class FakeRealtime : RealtimeService {
    val channels = mutableListOf<String>()
    val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 16)
    override val isConnected: StateFlow<Boolean> = MutableStateFlow(true)

    override fun subscribe(channel: String): Flow<RealtimeEvent> {
        channels += channel
        return events
    }
}

/** A coordinator over the stubbed API without realtime or push. */
fun coordinator(transport: StubTransport, scope: CoroutineScope) =
    LiveCoordinator(repository(transport), FakeRealtime(), registrar(), LiveCounts(scope))
