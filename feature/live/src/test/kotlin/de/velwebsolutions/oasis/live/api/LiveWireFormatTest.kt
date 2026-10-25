package de.velwebsolutions.oasis.live.api

import de.velwebsolutions.oasis.live.assertWireFormat
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every model and request body of the Live API reads back what it writes, with and without defaults. */
class LiveWireFormatTest {
    private val since: Instant = Instant.parse("2026-10-22T15:00:00Z")
    private val device = PushDevice(4, "app", "Oasis-App auf Android", since, current = true)

    @Test
    fun hintsAndTheirSettings() {
        assertWireFormat(
            Hint.serializer(),
            Hint("a", "Neu", createdAt = since, group = "Heute"),
            Hint(
                "b",
                "Antwort",
                url = "https://get-oasis.com/tropfen/1",
                excerpt = "Toll",
                unread = true,
                createdAt = since,
                group = "Früher"
            )
        )
        assertWireFormat(HintKindSetting.serializer(), HintKindSetting("follow", "Neue Person", true))
        assertWireFormat(PushDevice.serializer(), PushDevice(9, "browser", "Chrome", since), device)
        assertWireFormat(
            PushSettings.serializer(),
            PushSettings(),
            PushSettings(available = true, devices = listOf(device)),
            required = false
        )
        assertWireFormat(
            ChatSwitch.serializer(),
            ChatSwitch("chat_push", "Push", enabled = false),
            ChatSwitch("chat_email", "E-Mail", "Hilfe", true)
        )
        assertWireFormat(
            HintSettings.serializer(),
            HintSettings(),
            HintSettings(
                kinds = listOf(HintKindSetting("follow", "Neue Person", false)),
                digest = true,
                quietEnabled = true,
                quietFrom = "21:00",
                quietUntil = "06:00",
                pausedUntil = since,
                pauseDays = listOf(1, 3, 7),
                chat = listOf(ChatSwitch("chat_push", "Push", enabled = true)),
                push = PushSettings(available = true)
            ),
            required = false
        )
    }

    @Test
    fun countsConfigAndRequestBodies() {
        assertWireFormat(LiveCountsData.serializer(), LiveCountsData(), LiveCountsData(chats = 2, hints = 3), required = false)
        assertWireFormat(LiveAppConfig.serializer(), LiveAppConfig(), LiveAppConfig(LiveAppConfig.Push(available = true)), required = false)
        assertWireFormat(LiveAppConfig.Push.serializer(), LiveAppConfig.Push(), LiveAppConfig.Push(true), required = false)
        assertWireFormat(HintSettingsBody.serializer(), HintSettingsBody(mapOf("follow" to true), true, false, "22:00", "07:00"))
        assertWireFormat(PauseBody.serializer(), PauseBody(3))
        assertWireFormat(SwitchBody.serializer(), SwitchBody(true))
        assertWireFormat(FcmRegistrationBody.serializer(), FcmRegistrationBody("token", "android"))
    }

    @Test
    fun requestBodiesCarryWhatTheScreensSet() {
        val body = HintSettingsBody(mapOf("follow" to true), digest = true, quietEnabled = false, quietFrom = "22:00", quietUntil = "07:00")
        assertEquals(
            listOf(mapOf("follow" to true), true, false, "22:00", "07:00"),
            listOf(body.kinds, body.digest, body.quietEnabled, body.quietFrom, body.quietUntil)
        )
        assertEquals(3, PauseBody(3).days)
        assertEquals(true, SwitchBody(true).value)
        val registration = FcmRegistrationBody("token", "android")
        assertEquals("token" to "android", registration.token to registration.platform)
    }
}
