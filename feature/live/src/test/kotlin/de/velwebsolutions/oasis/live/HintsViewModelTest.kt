package de.velwebsolutions.oasis.live

import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.live.ui.HintSettingsViewModel
import de.velwebsolutions.oasis.live.ui.HintsInboxViewModel
import de.velwebsolutions.oasis.live.ui.timeText
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import de.velwebsolutions.oasis.testing.StubTransport
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The Hinweise list and Hinweise einstellen against stubbed answers of the API (iOS OasisLiveTests). */
class HintsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private suspend fun HintsInboxViewModel.loaded() = ui.first { it.state !is Loadable.Loading && it.state !is Loadable.Idle }

    private suspend fun HintSettingsViewModel.loaded() = ui.first { it.state !is Loadable.Loading && it.state !is Loadable.Idle }

    @Test
    fun loadsGroupsAndPagesAndRefreshesTheBell() = runTest {
        val transport = StubTransport(
            StubTransport.Stub("GET", "hints", json = LiveJson.HINTS),
            StubTransport.Stub("GET", "live/counts", json = LiveJson.COUNTS)
        )
        val live = coordinator(transport, backgroundScope)
        live.start(5)
        val model = HintsInboxViewModel(repository(transport), live)
        val ui = model.loaded()
        assertEquals(listOf("Heute", "Diese Woche", "Früher"), ui.groups.map { it.title })
        assertTrue(ui.state.valueOrNull!!.first().unread)
        assertTrue(ui.hasMore)

        transport.add("GET", "hints", json = LiveJson.LAST_PAGE)
        model.loadMore()
        val more = model.ui.first { !it.isLoadingMore && it.state.valueOrNull?.size == 4 }
        assertFalse(more.hasMore)
        assertEquals("page=2", transport.requests.last { it.path == "hints" }.request.url.query)
        assertTrue("the bell is refreshed after reading", transport.requests.count { it.path == "live/counts" } >= 2)
        live.stop()
    }

    @Test
    fun marksAllReadWithTheWebsConfirmation() = runTest {
        val transport = StubTransport(
            StubTransport.Stub("GET", "hints", json = LiveJson.HINTS),
            StubTransport.Stub("POST", "hints/read-all", json = """{"message":"Alle Hinweise sind gelesen."}""")
        )
        val model = HintsInboxViewModel(repository(transport), coordinator(transport, backgroundScope))
        assertTrue(model.loaded().showsUnread)
        model.markAllRead()
        assertEquals("Alle Hinweise sind gelesen.", model.ui.value.message)
        assertFalse("the list drops its \"Neu\" marks", model.ui.value.showsUnread)
    }

    @Test
    fun showsTheErrorWhenTheListCannotLoad() = runTest {
        val transport = StubTransport(StubTransport.Stub("GET", "hints", status = 403, json = """{"message":"Dein Konto ist gesperrt."}"""))
        val model = HintsInboxViewModel(repository(transport), coordinator(transport, backgroundScope))
        assertEquals("Dein Konto ist gesperrt.", model.loaded().state.errorOrNull?.userMessage)
    }

    private fun settingsModel(transport: StubTransport) = HintSettingsViewModel(repository(transport), registrar())

    @Test
    fun savesEveryChangeWithTheWireNames() = runTest {
        val transport = StubTransport(
            StubTransport.Stub("GET", "hints/settings", json = LiveJson.SETTINGS),
            StubTransport.Stub("PUT", "hints/settings", json = LiveJson.saved("Deine Hinweis-Einstellungen sind gespeichert."))
        )
        val model = settingsModel(transport)
        val ui = model.loaded()
        assertEquals(listOf("follow", "reply", "friend_request"), ui.settings?.kinds?.map { it.key })
        assertTrue(ui.isOnForThisDevice)

        model.edit { settings ->
            settings.copy(
                kinds = settings.kinds.mapIndexed { index, kind -> if (index == 1) kind.copy(enabled = false) else kind },
                digest = true,
                quietEnabled = true,
                quietFrom = "21:30",
                quietUntil = "06:15"
            )
        }
        assertTrue(model.quietHoursChanged)
        model.save()

        assertEquals(
            JsonObject(
                mapOf(
                    "kinds" to
                        JsonObject(
                            mapOf(
                                "follow" to JsonPrimitive(true),
                                "reply" to JsonPrimitive(false),
                                "friend_request" to JsonPrimitive(true)
                            )
                        ),
                    "digest" to JsonPrimitive(true),
                    "quiet_enabled" to JsonPrimitive(true),
                    "quiet_from" to JsonPrimitive("21:30"),
                    "quiet_until" to JsonPrimitive("06:15")
                )
            ),
            transport.lastBody("hints/settings")
        )
        assertEquals("Deine Hinweis-Einstellungen sind gespeichert.", model.ui.value.message)
        assertFalse(model.quietHoursChanged)
        assertFalse(model.ui.value.isSaving)
    }

    @Test
    fun keepsRefusedQuietHoursWithTheirMessage() = runTest {
        val transport = StubTransport(
            StubTransport.Stub("GET", "hints/settings", json = LiveJson.SETTINGS),
            StubTransport.Stub(
                "PUT",
                "hints/settings",
                status = 422,
                json = """{"message":"x","errors":{"quiet_until":["Beginn und Ende der Ruhezeit müssen sich unterscheiden."]}}"""
            )
        )
        val model = settingsModel(transport)
        model.loaded()
        model.edit { it.copy(quietUntil = "22:00") }
        model.save()
        assertEquals("Beginn und Ende der Ruhezeit müssen sich unterscheiden.", model.ui.value.fieldErrors["quiet_until"])
        assertEquals("the refused time stays to be corrected", "22:00", model.ui.value.settings?.quietUntil)
        assertNull(model.ui.value.message)
    }

    @Test
    fun turnsASwitchBackWhenSavingFails() = runTest {
        val transport = StubTransport(
            StubTransport.Stub("GET", "hints/settings", json = LiveJson.SETTINGS),
            StubTransport.Stub("PUT", "hints/settings", status = 500, json = """{"message":"Server Error"}""")
        )
        val model = settingsModel(transport)
        model.loaded()
        model.edit { it.copy(digest = true) }
        model.save()
        assertEquals(false, model.ui.value.settings?.digest)
        assertNotNull(model.ui.value.error)
    }

    @Test
    fun pausesWithoutTouchingTheSwitchesAndRemovesDevices() = runTest {
        val transport = StubTransport(
            StubTransport.Stub("GET", "hints/settings", json = LiveJson.SETTINGS),
            StubTransport.Stub(
                "POST",
                "hints/pause",
                json = LiveJson.saved("Pause bis Sonntag, 25. Oktober, 18:00 Uhr.", paused = "\"2026-10-25T17:00:00.000000Z\"")
            ),
            StubTransport.Stub("DELETE", "push/browsers/9", json = """{"message":"Das Gerät bekommt keine Push-Hinweise mehr."}""")
        )
        val model = settingsModel(transport)
        model.loaded()
        model.pause(3)
        assertEquals(JsonObject(mapOf("days" to JsonPrimitive(3))), transport.lastBody("hints/pause"))
        assertEquals("Pause bis Sonntag, 25. Oktober, 18:00 Uhr.", model.ui.value.message)
        assertNotNull(model.ui.value.settings?.pausedUntil)
        assertEquals("the pause answer does not overwrite the switches", "22:00", model.ui.value.settings?.quietFrom)

        model.remove(model.ui.value.settings!!.push.devices.last())
        assertEquals("Das Gerät bekommt keine Push-Hinweise mehr.", model.ui.value.message)
        assertTrue(transport.requests.any { it.request.method == "DELETE" && it.path == "push/browsers/9" })
    }

    @Test
    fun savesChatPushThroughThePrivacySettingAndTurnsItBackOnFailure() = runTest {
        val transport = StubTransport(
            StubTransport.Stub("GET", "hints/settings", json = LiveJson.SETTINGS),
            StubTransport.Stub(
                "PUT",
                "privacy/settings/chat_push",
                json = """{"message":"Gespeichert.","data":{"key":"chat_push","value":true}}"""
            ),
            StubTransport.Stub("PUT", "privacy/settings/chat_email", status = 500, json = """{"message":"Server Error"}""")
        )
        val model = settingsModel(transport)
        model.loaded()
        assertEquals(
            listOf("E-Mail bei neuen Chat-Nachrichten", "Push-Hinweis bei neuen Chat-Nachrichten"),
            model.ui.value.settings?.chat?.map {
                it.title
            }
        )

        model.edit { settings -> settings.copy(chat = settings.chat.map { if (it.key == "chat_push") it.copy(enabled = true) else it }) }
        model.saveChat("chat_push")
        assertEquals(JsonObject(mapOf("value" to JsonPrimitive(true))), transport.lastBody("privacy/settings/chat_push"))
        assertEquals("Gespeichert.", model.ui.value.message)
        assertEquals(true, model.ui.value.settings?.chat?.get(1)?.enabled)

        model.edit { settings -> settings.copy(chat = settings.chat.map { if (it.key == "chat_email") it.copy(enabled = false) else it }) }
        model.saveChat("chat_email")
        assertEquals(true, model.ui.value.settings?.chat?.get(0)?.enabled)
        assertNotNull(model.ui.value.error)
    }

    @Test
    fun saysPushIsNotThereYetWhileTheServerTakesApnsOnly() = runTest {
        val transport = StubTransport(StubTransport.Stub("GET", "hints/settings", json = LiveJson.SETTINGS))
        val model = HintSettingsViewModel(repository(transport), registrar(backendAcceptsFcm = false))
        val ui = model.loaded()
        assertTrue(ui.settings!!.push.available)
        assertFalse(ui.pushAvailable)
    }

    @Test
    fun labelsThePauseButtons() {
        assertEquals(listOf("1 Tag", "3 Tage", "1 Woche"), listOf(1, 3, 7).map(HintSettingsViewModel::pauseLabel))
    }

    @Test
    fun writesQuietHoursWithLatinDigitsInEveryLanguage() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("fa-IR"))
            assertEquals("07:05", timeText(7, 5))
            assertEquals("22:00", timeText(22, 0))
        } finally {
            Locale.setDefault(before)
        }
    }
}
