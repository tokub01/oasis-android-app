package de.velwebsolutions.oasis.live.data

import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.core.network.Page
import de.velwebsolutions.oasis.live.api.FcmRegistrationBody
import de.velwebsolutions.oasis.live.api.Hint
import de.velwebsolutions.oasis.live.api.HintSettings
import de.velwebsolutions.oasis.live.api.HintSettingsBody
import de.velwebsolutions.oasis.live.api.LiveAppConfig
import de.velwebsolutions.oasis.live.api.LiveCountsData
import de.velwebsolutions.oasis.live.api.LiveService
import de.velwebsolutions.oasis.live.api.PauseBody
import de.velwebsolutions.oasis.live.api.PushDevice
import de.velwebsolutions.oasis.live.api.SwitchBody
import javax.inject.Inject

/** Hinweise, their settings, push devices and the counters of the app shell (LiveAPI on iOS). */
interface LiveRepository {
    /** One page of hints (20 per page); opening it marks its hints read on the server. */
    suspend fun hints(page: Int): Page<Hint>

    /** "Alle als gelesen markieren"; returns the confirmation. */
    suspend fun markAllRead(): String

    suspend fun settings(): HintSettings

    /** Saves the switches, the daily e-mail and the quiet hours at once (the screen sends them after every change). */
    suspend fun save(settings: HintSettings): Confirmed<HintSettings>

    suspend fun pause(days: Int): Confirmed<HintSettings>

    suspend fun resume(): Confirmed<HintSettings>

    /** A chat switch of the privacy settings (`chat_push`, `chat_email`); returns the status text. */
    suspend fun setChatSwitch(key: String, on: Boolean): String

    /** Removes an app installation or a browser from the push devices; returns the confirmation. */
    suspend fun removeDevice(device: PushDevice): String

    suspend fun removeAppDevice(id: Int)

    /** Registers this installation's FCM token (not accepted by the server yet, see PushRegistrar). */
    suspend fun registerDevice(token: String): PushDevice

    suspend fun counts(): LiveCountsData

    suspend fun appConfig(): LiveAppConfig
}

/** [LiveRepository] over the Oasis API. */
class DefaultLiveRepository @Inject constructor(client: ApiClient) : LiveRepository {
    private val service = client.create<LiveService>()

    override suspend fun hints(page: Int) = service.hints(page)

    override suspend fun markAllRead() = service.markAllRead().message

    override suspend fun settings() = service.settings().data

    override suspend fun save(settings: HintSettings) = service.save(
        HintSettingsBody(
            kinds = settings.kinds.associate { it.key to it.enabled },
            digest = settings.digest,
            quietEnabled = settings.quietEnabled,
            quietFrom = settings.quietFrom,
            quietUntil = settings.quietUntil
        )
    )

    override suspend fun pause(days: Int) = service.pause(PauseBody(days))

    override suspend fun resume() = service.resume()

    override suspend fun setChatSwitch(key: String, on: Boolean) = service.setChatSwitch(key, SwitchBody(on)).message

    override suspend fun removeDevice(device: PushDevice) =
        if (device.type == "browser") service.removeBrowser(device.id).message else service.removeAppDevice(device.id).message

    override suspend fun removeAppDevice(id: Int) {
        service.removeAppDevice(id)
    }

    override suspend fun registerDevice(token: String) = service.registerDevice(FcmRegistrationBody(token, platform = "android")).data

    override suspend fun counts() = service.counts().data

    override suspend fun appConfig() = service.appConfig().data
}
