package de.velwebsolutions.oasis.live

import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.live.api.ChatSwitch
import de.velwebsolutions.oasis.live.api.HintSettings
import de.velwebsolutions.oasis.live.api.PushSettings
import de.velwebsolutions.oasis.live.data.LiveRepository
import de.velwebsolutions.oasis.live.push.PushAuthorization
import de.velwebsolutions.oasis.live.push.PushRegistrar
import de.velwebsolutions.oasis.live.ui.HintSettingsUiState
import de.velwebsolutions.oasis.live.ui.HintSettingsViewModel
import de.velwebsolutions.oasis.live.ui.HintsInboxUiState
import de.velwebsolutions.oasis.live.ui.HintsInboxViewModel
import de.velwebsolutions.oasis.live.ui.hourAndMinute
import de.velwebsolutions.oasis.live.ui.timeText
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import de.velwebsolutions.oasis.testing.StubTransport
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
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
        assertFalse(ui.pushSupported)
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
            assertEquals(7 to 5, hourAndMinute("07:05"))
            assertEquals(0 to 0, hourAndMinute(""))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun showsALoadErrorOnlyAsToastOnceSomethingIsShown() = runTest {
        val transport = StubTransport(StubTransport.Stub("GET", "hints/settings", status = 500, json = """{"message":"Server Error"}"""))
        val model = settingsModel(transport)
        assertTrue(model.loaded().state is Loadable.Failed)
        assertNull("the failure is the screen, not a toast", model.ui.value.error)
        assertFalse(model.quietHoursChanged)
        model.edit { it.copy(digest = true) }
        model.save()
        model.saveChat("chat_push")
        assertTrue("nothing to change or save without settings", model.ui.value.settings == null)

        transport.add("GET", "hints/settings", json = LiveJson.SETTINGS)
        model.load()
        transport.add("GET", "hints/settings", status = 500, json = """{"message":"Server Error"}""")
        model.load()
        assertNotNull(model.ui.value.settings)
        assertNotNull(model.ui.value.error)
        model.dismissError()
        assertNull(model.ui.value.error)
    }

    @Test
    fun savesQuietHoursChangedDuringTheRequestRightAfter() = runTest {
        val transport = StubTransport(
            StubTransport.Stub("GET", "hints/settings", json = LiveJson.SETTINGS),
            StubTransport.Stub("PUT", "hints/settings", json = LiveJson.saved("Gespeichert."))
        )
        val hooked = HookedRepository(repository(transport))
        val model = HintSettingsViewModel(hooked, registrar())
        model.loaded()
        var changes = 0
        hooked.whileSaving = {
            if (changes++ == 0) model.edit { it.copy(quietFrom = "23:45") }
        }
        model.edit { it.copy(quietEnabled = true) }
        model.save()
        assertEquals("the change made meanwhile is saved, too", 2, transport.requests.count { it.request.method == "PUT" })
        assertEquals("23:45", (transport.lastBody("hints/settings") as JsonObject)["quiet_from"]?.let { (it as JsonPrimitive).content })
        assertFalse(model.ui.value.isSaving)
        model.dismissMessage()
        assertNull(model.ui.value.message)
    }

    @Test
    fun waitsForTheRunningSaveAndStopsSavingWhenCancelled() = runTest {
        val transport = StubTransport(
            StubTransport.Stub("GET", "hints/settings", json = LiveJson.SETTINGS),
            StubTransport.Stub("PUT", "hints/settings", json = LiveJson.saved("Gespeichert.")),
            StubTransport.Stub(
                "PUT",
                "privacy/settings/chat_push",
                json = """{"message":"Gespeichert.","data":{"key":"chat_push","value":true}}"""
            )
        )
        val hooked = HookedRepository(repository(transport))
        val model = HintSettingsViewModel(hooked, registrar())
        model.loaded()
        hooked.whileSaving = {
            model.save()
            model.saveChat("chat_push")
            throw CancellationException("left the screen")
        }
        val cancelled = runCatching { model.save() }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)
        assertEquals("nothing else is saved while a save runs", 1, transport.requests.count { it.request.method == "PUT" })
        assertFalse(model.ui.value.isSaving)
    }

    @Test
    fun aSwitchShowsAtOnceAndIsSaved() = runTest {
        val transport = StubTransport(
            StubTransport.Stub("GET", "hints/settings", json = LiveJson.SETTINGS),
            StubTransport.Stub("PUT", "hints/settings", json = LiveJson.saved("Gespeichert.")),
            StubTransport.Stub(
                "PUT",
                "privacy/settings/chat_push",
                json = """{"message":"Chat-Push ist an.","data":{"key":"chat_push","value":true}}"""
            )
        )
        val model = settingsModel(transport)
        model.loaded()
        model.toggle { it.copy(digest = true) }
        model.ui.first { it.message == "Gespeichert." }
        model.toggleChat("chat_push", true)
        model.ui.first { it.message == "Chat-Push ist an." }
        assertEquals(true, model.ui.value.settings?.chat?.last()?.enabled)
        model.saveChat("unknown")
        assertEquals(2, transport.requests.count { it.request.method == "PUT" })
    }

    @Test
    fun endsThePauseAndReportsFailedPausesAndRemovals() = runTest {
        val transport = StubTransport(
            StubTransport.Stub("GET", "hints/settings", json = LiveJson.SETTINGS),
            StubTransport.Stub("DELETE", "hints/pause", json = LiveJson.saved("Die Pause ist beendet.")),
            StubTransport.Stub("POST", "hints/pause", status = 500, json = """{"message":"Server Error"}"""),
            StubTransport.Stub("DELETE", "push/devices/4", status = 500, json = """{"message":"Server Error"}""")
        )
        val model = settingsModel(transport)
        model.loaded()
        model.resume()
        assertEquals("Die Pause ist beendet.", model.ui.value.message)
        assertNull(model.ui.value.settings?.pausedUntil)
        model.pause(1)
        assertNotNull(model.ui.value.error)
        model.dismissError()
        model.remove(model.ui.value.settings!!.push.devices.first())
        assertNotNull(model.ui.value.error)
    }

    @Test
    fun forgetsThisInstallationWhenItsAppDeviceIsRemoved() = runTest {
        val transport = StubTransport(
            StubTransport.Stub("GET", "hints/settings", json = LiveJson.SETTINGS),
            StubTransport.Stub("DELETE", "push/devices/4", json = """{"message":"Entfernt."}""")
        )
        val store = MemoryPushStore(deviceId = 4)
        val model = HintSettingsViewModel(repository(transport), registrar(store = store))
        model.loaded()
        // The list cannot be read again afterwards: it keeps what it had.
        transport.add("GET", "hints/settings", status = 500, json = """{"message":"Server Error"}""")
        model.remove(model.ui.value.settings!!.push.devices.first())
        assertNull(store.deviceId)
        assertEquals("Entfernt.", model.ui.value.message)
        assertEquals(2, model.ui.value.settings?.push?.devices?.size)
    }

    @Test
    fun switchesPushOnThisDeviceAndOpensTheAndroidSettings() = runTest {
        val transport = StubTransport(
            StubTransport.Stub("GET", "hints/settings", json = LiveJson.SETTINGS),
            StubTransport.Stub("GET", "app-config", json = LiveJson.CONFIG),
            StubTransport.Stub("POST", "push/devices", json = LiveJson.DEVICE),
            StubTransport.Stub("DELETE", "push/devices/5", json = """{"message":"weg"}""")
        )
        val system = FakePushSystem(status = PushAuthorization.NotDetermined)
        val push = registrar(system)
        push.signedIn(repository(transport))
        val model = HintSettingsViewModel(repository(transport), push)
        model.loaded()
        assertTrue("this installation is in the list", model.ui.value.isOnForThisDevice)
        model.togglePushOnThisDevice()
        assertEquals("Push-Hinweise sind auf diesem Gerät ausgeschaltet.", model.ui.value.message)

        transport.add(
            "GET",
            "hints/settings",
            json = LiveJson.SETTINGS.replace("\"current\":true", "\"current\":false")
        )
        model.load()
        assertFalse(model.ui.value.isOnForThisDevice)
        model.togglePushOnThisDevice()
        assertEquals("without the permission it stays off", PushRegistrar.DENIED_TEXT, model.ui.value.error)

        model.permissionAnswered(true)
        assertEquals(PushAuthorization.Authorized, model.ui.value.authorization)
        model.togglePushOnThisDevice()
        assertEquals("Push-Hinweise sind auf diesem Gerät eingeschaltet.", model.ui.value.message)

        model.openSystemSettings()
        assertEquals(1, system.openedSettings)
    }

    @Test
    fun loadsNoMorePagesWhileOneIsLoadingOrNoneIsLeft() = runTest {
        val transport = StubTransport(StubTransport.Stub("GET", "hints", json = LiveJson.LAST_PAGE))
        val model = HintsInboxViewModel(repository(transport), coordinator(transport, backgroundScope))
        model.loadMore()
        assertFalse(model.loaded().hasMore)
        model.loadMore()
        assertEquals("the last page needs no next one", 1, transport.requests.count { it.path == "hints" })

        transport.add("GET", "hints", json = LiveJson.HINTS)
        model.load()
        transport.add("GET", "hints", json = LiveJson.HINTS)
        model.loadMore()
        model.loadMore()
        val merged = model.ui.first { !it.isLoadingMore }
        assertEquals("hints already shown are not repeated", 3, merged.state.valueOrNull?.size)
        assertTrue(merged.hasMore)
    }

    @Test
    fun reportsFailedPagesAndMarksWithoutAList() = runTest {
        val transport = StubTransport(
            StubTransport.Stub("GET", "hints", json = LiveJson.HINTS),
            StubTransport.Stub("POST", "hints/read-all", status = 500, json = """{"message":"Server Error"}""")
        )
        val model = HintsInboxViewModel(repository(transport), coordinator(transport, backgroundScope))
        model.loaded()
        transport.add("GET", "hints", status = 500, json = """{"message":"Server Error"}""")
        model.loadMore()
        model.ui.first { !it.isLoadingMore && it.error != null }
        model.dismissError()
        model.load()
        assertNotNull("a failed reload keeps the list and says so", model.ui.value.state.valueOrNull)
        assertNotNull(model.ui.value.error)
        model.dismissError()
        model.markAllRead()
        assertNotNull(model.ui.value.error)
        model.dismissMessage()
        assertNull(model.ui.value.message)

        val empty = StubTransport(StubTransport.Stub("POST", "hints/read-all", json = """{"message":"Alle Hinweise sind gelesen."}"""))
        val withoutList = HintsInboxViewModel(repository(empty), coordinator(empty, backgroundScope))
        withoutList.loaded()
        withoutList.markAllRead()
        assertEquals("Alle Hinweise sind gelesen.", withoutList.ui.value.message)
        assertTrue(withoutList.ui.value.groups.isEmpty())
    }

    @Test
    fun saysNothingAboutPushOrUnreadHintsBeforeLoading() {
        assertFalse(HintSettingsUiState().isOnForThisDevice)
        assertFalse(HintSettingsUiState(pushSupported = true).pushAvailable)
        val offServer = HintSettingsUiState(state = Loadable.Loaded(HintSettings()), pushSupported = true)
        assertFalse("the server sends no app pushes", offServer.pushAvailable)
        val on = HintSettingsUiState(state = Loadable.Loaded(HintSettings(push = PushSettings(available = true))), pushSupported = true)
        assertTrue(on.pushAvailable)
        assertFalse(HintsInboxUiState().showsUnread)
        assertTrue(HintsInboxUiState().groups.isEmpty())
    }

    @Test
    fun answersWithoutWaitingAndSavesChatSwitchesBothWays() = runTest {
        val immediate = ImmediateLiveRepository(
            settings = HintSettings(
                chat = listOf(ChatSwitch("chat_push", "Push", enabled = false), ChatSwitch("chat_email", "E-Mail", enabled = true))
            )
        )
        val model = HintSettingsViewModel(immediate, registrar())
        model.loaded()
        model.pause(1)
        assertEquals("Pause.", model.ui.value.message)
        model.resume()
        assertEquals("Weiter.", model.ui.value.message)
        model.saveChat("chat_push")
        model.saveChat("chat_email")
        val failing = object : LiveRepository by immediate {
            override suspend fun setChatSwitch(key: String, on: Boolean): String = error("offline")
        }
        val refused = HintSettingsViewModel(failing, registrar())
        refused.loaded()
        refused.saveChat("chat_push")
        refused.saveChat("chat_email")
        assertEquals(listOf(true, false), refused.ui.value.settings?.chat?.map { it.enabled })

        val live = LiveCoordinator(immediate, EndedRealtime(), registrar(), LiveCounts(backgroundScope))
        live.start(1)
        runCurrent()
        live.foreground()
        assertEquals(2, live.hints.value)
        live.stop()
    }
}
