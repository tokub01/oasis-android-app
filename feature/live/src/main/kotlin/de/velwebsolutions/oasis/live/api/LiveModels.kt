package de.velwebsolutions.oasis.live.api

import de.velwebsolutions.oasis.core.network.OasisInstant
import kotlinx.serialization.Serializable

/**
 * One entry of the Hinweise list (Laravel `HintResource`): label with the actor's current name, the web link of the
 * target (mapped to an app screen with `DeepLink`), excerpt, "Neu" and the time with its group heading.
 */
@Serializable
data class Hint(
    val id: String,
    val label: String,
    val url: String? = null,
    val excerpt: String = "",
    /** Unread when the page was opened; the server marks it read at once, the app shows "Neu" this time. */
    val unread: Boolean = false,
    val createdAt: OasisInstant,
    /** "Heute", "Diese Woche" or "Früher" (German time, computed by the server like the web). */
    val group: String
)

/** One switch per hint kind (NoticeKind) with the web's label. */
@Serializable
data class HintKindSetting(val key: String, val label: String, val enabled: Boolean)

/** A browser (Web Push) or app installation (APNs, FCM) that gets push hints. */
@Serializable
data class PushDevice(
    val id: Int,
    /** `app` or `browser`. */
    val type: String,
    val device: String,
    val since: OasisInstant,
    /** This installation (registered with the API token of this app). */
    val current: Boolean = false
) {
    val listId: String get() = "$type-$id"
}

/** Push availability and devices in the hint settings. */
@Serializable
data class PushSettings(
    /** Whether the server sends pushes to this app (FCM configured; the settings are asked for as Android). */
    val available: Boolean = false,
    val devices: List<PushDevice> = emptyList()
)

/**
 * A chat switch of the privacy settings with the privacy centre's title and help (PrivacyOptions). Its home is
 * Hinweise einstellen; it is saved through `PUT privacy/settings/{key}`.
 */
@Serializable
data class ChatSwitch(val key: String, val title: String, val help: String = "", val enabled: Boolean)

/** One choice of the Ruhemodus: `1h`, `8h`, `morning` or `manual` with its German label. */
@Serializable
data class QuietModeChoice(val key: String, val label: String)

/**
 * The Ruhemodus (docs/oasis-notifications.md): on or off, until when (null while on: until switched off) and – in the
 * hint settings – the modes to choose from.
 */
@Serializable
data class QuietMode(val active: Boolean = false, val until: OasisInstant? = null, val modes: List<QuietModeChoice> = emptyList())

/** Hinweise einstellen (Laravel `HintSettingsResource`). */
@Serializable
data class HintSettings(
    val kinds: List<HintKindSetting> = emptyList(),
    val digest: Boolean = false,
    val quietEnabled: Boolean = false,
    val quietFrom: String = "22:00",
    val quietUntil: String = "07:00",
    val pausedUntil: OasisInstant? = null,
    val pauseDays: List<Int> = emptyList(),
    /** How new chat messages are announced: the privacy switches `chat_email` and, while push is set up, `chat_push`. */
    val chat: List<ChatSwitch> = emptyList(),
    val push: PushSettings = PushSettings(),
    val quietMode: QuietMode = QuietMode()
)

/** The numbers of the app shell (`GET live/counts`). */
@Serializable
data class LiveCountsData(val chats: Int = 0, val hints: Int = 0, val quietMode: QuietMode = QuietMode())

/** The part of `GET app-config` the Live module needs. */
@Serializable
data class LiveAppConfig(val push: Push = Push()) {
    @Serializable
    data class Push(val available: Boolean = false)
}
