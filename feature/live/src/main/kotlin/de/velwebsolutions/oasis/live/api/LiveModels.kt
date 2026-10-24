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
    /** `follow`, `reply`, … (NoticeKind) or `account` for Konto-Hinweise. */
    val kind: String,
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

/** A browser (Web Push) or app installation (APNs) that gets push hints. */
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
    /** Whether the server sends app pushes (APNs configured). */
    val available: Boolean = false,
    val devices: List<PushDevice> = emptyList()
)

/**
 * A chat switch of the privacy settings with the privacy centre's title and help (PrivacyOptions). Its home is
 * Hinweise einstellen; it is saved through `PUT privacy/settings/{key}`.
 */
@Serializable
data class ChatSwitch(val key: String, val title: String, val help: String = "", val enabled: Boolean)

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
    val push: PushSettings = PushSettings()
)

/** The numbers of the app shell (`GET live/counts`). */
@Serializable
data class LiveCountsData(val chats: Int = 0, val hints: Int = 0)

/** The part of `GET app-config` the Live module needs. */
@Serializable
data class LiveAppConfig(val push: Push = Push()) {
    @Serializable
    data class Push(val available: Boolean = false)
}
