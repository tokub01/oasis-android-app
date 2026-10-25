package de.velwebsolutions.oasis.live.ui

import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.DataEnvelope
import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.core.network.Page
import de.velwebsolutions.oasis.design.navigation.ChatRoute
import de.velwebsolutions.oasis.design.navigation.LiveRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.live.ChatToast
import de.velwebsolutions.oasis.live.ChatToastContent
import de.velwebsolutions.oasis.live.ChatToastOverlay
import de.velwebsolutions.oasis.live.FakePushSystem
import de.velwebsolutions.oasis.live.HintsBell
import de.velwebsolutions.oasis.live.HintsButton
import de.velwebsolutions.oasis.live.LiveJson
import de.velwebsolutions.oasis.live.api.Hint
import de.velwebsolutions.oasis.live.api.HintSettings
import de.velwebsolutions.oasis.live.coordinator
import de.velwebsolutions.oasis.live.push.PushAuthorization
import de.velwebsolutions.oasis.live.push.PushRegistrar
import de.velwebsolutions.oasis.live.registrar
import de.velwebsolutions.oasis.live.repository
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import de.velwebsolutions.oasis.testing.StubTransport
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Live screens with their real view models over the stubbed API, one screenshot per state (Roborazzi). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveScreensTest {
    @get:Rule val main = MainDispatcherRule()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val navigator = Navigator()
    private val hints: List<Hint> = OasisJson.snake.decodeFromString<Page<Hint>>(LiveJson.HINTS).data
    private val settings: HintSettings = OasisJson.snake.decodeFromString<DataEnvelope<HintSettings>>(LiveJson.SETTINGS).data

    @After
    fun tearDown() = scope.cancel()

    private fun show(content: @Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalNavigator provides navigator) { content() }
    }

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/live-$name.png")

    /** Clicks through the semantics, so a snackbar over the node cannot catch the tap. */
    private fun SemanticsNodeInteraction.tap() = performScrollTo().performSemanticsAction(SemanticsActions.OnClick)

    private fun opened() = navigator.stack(navigator.selectedTab).toList()

    @Test
    fun theInboxOpensHintsAndTheSettingsAndLoadsTheNextPage() {
        val transport = StubTransport(
            StubTransport.Stub("GET", "hints", json = LiveJson.HINTS),
            StubTransport.Stub("POST", "hints/read-all", json = """{"message":"Alle Hinweise sind gelesen."}"""),
            StubTransport.Stub("GET", "live/counts", json = LiveJson.COUNTS)
        )
        val model = HintsInboxViewModel(repository(transport), coordinator(transport, scope))
        show { HintsInboxScreen(model) }
        compose.waitUntil(5_000) { model.ui.value.state is Loadable.Loaded }
        compose.onNodeWithText("Heute").assertIsDisplayed()
        shot("inbox")
        compose.onNodeWithText("Neue Person in deiner Karawane · Emil Novak").performClick()
        assertEquals(1, opened().size)
        compose.onNodeWithTag("hints.list").performScrollToNode(hasText("Beitrag eingegangen"))
        compose.onNodeWithText("Beitrag eingegangen").assertIsDisplayed()
        compose.onNodeWithTag("hints.list").performScrollToNode(hasTestTag("hints.settings"))
        compose.onNodeWithTag("hints.settings").performClick()
        assertEquals(LiveRoute.Settings, opened().last())
        compose.waitUntil(5_000) { transport.requests.count { it.path == "hints" } >= 2 }

        compose.onNodeWithTag("hints.list").performScrollToIndex(0)
        compose.onNodeWithTag("hints.list").performTouchInput { swipeDown() }
        compose.waitUntil(5_000) { transport.requests.count { it.path == "hints" } >= 3 }
        compose.onNodeWithTag("hints.readAll").performClick()
        compose.waitUntil(5_000) { model.ui.value.message != null }
        compose.onNodeWithText("Alle Hinweise sind gelesen.").assertIsDisplayed()
        shot("inbox-read")
    }

    @Test
    fun theInboxShowsLoadingFailureAndTheNextPage() {
        var state by mutableIntStateOf(0)
        show {
            val ui = when (state) {
                0 -> HintsInboxUiState(state = Loadable.Idle)

                1 -> HintsInboxUiState(state = Loadable.Failed(ApiError.Forbidden("Dein Konto ist gesperrt.")))

                else -> HintsInboxUiState(
                    state = Loadable.Loaded(
                        hints.map {
                            it.copy(unread = false)
                        }
                    ),
                    hasMore = true,
                    isLoadingMore = true
                )
            }
            HintsInboxContent(ui, unreadAtTheBell = if (state == 2) 2 else 0, {}, {}, {}, {}, {})
        }
        shot("inbox-loading")
        state = 1
        compose.onNodeWithText("Dein Konto ist gesperrt.", substring = true).assertIsDisplayed()
        shot("inbox-error")
        state = 2
        compose.onNodeWithTag("hints.readAll").assertIsDisplayed()
        shot("inbox-more")
    }

    @Test
    fun theSettingsSaveSwitchesTimesPauseAndChatAndGoBack() {
        val transport = StubTransport(
            StubTransport.Stub(
                "GET",
                "hints/settings",
                json = LiveJson.SETTINGS.replace("\"quiet_enabled\":false", "\"quiet_enabled\":true")
            ),
            StubTransport.Stub("PUT", "hints/settings", json = LiveJson.saved("Gespeichert.")),
            StubTransport.Stub(
                "POST",
                "hints/pause",
                json = LiveJson.saved("Pause gestartet.", paused = "\"2026-10-25T17:00:00.000000Z\"")
            ),
            StubTransport.Stub("DELETE", "hints/pause", json = LiveJson.saved("Pause beendet.")),
            StubTransport.Stub(
                "PUT",
                "privacy/settings/chat_push",
                json = """{"message":"Chat-Push ist an.","data":{"key":"chat_push","value":true}}"""
            ),
            StubTransport.Stub("DELETE", "push/browsers/9", json = """{"message":"Entfernt."}""")
        )
        navigator.stack(MainTab.Source).add(LiveRoute.Settings)
        val model = HintSettingsViewModel(repository(transport), registrar())
        show { HintSettingsScreen(model) }
        compose.waitUntil(5_000) { model.ui.value.settings != null }
        shot("settings")
        compose.onNodeWithTag("push.remove.browser-9").tap()
        compose.waitUntil(5_000) { transport.requests.any { it.path == "push/browsers/9" } }
        compose.waitUntil(5_000) { model.ui.value.message == "Entfernt." }
        model.dismissMessage()

        compose.onNodeWithTag("quiet.from").performScrollTo().performClick()
        compose.onNodeWithText("Abbrechen").performClick()
        compose.onNodeWithTag("quiet.until").performScrollTo().performClick()
        captureScreenRoboImage("build/outputs/roborazzi/live-settings-time.png")
        compose.onNodeWithText("Übernehmen").performClick()
        compose.onNodeWithTag("quiet.from").performScrollTo().performClick()
        compose.onNodeWithText("Übernehmen").performClick()

        // A changed time is saved after a short pause, not at once.
        compose.runOnIdle { model.edit { it.copy(quietFrom = "21:00") } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitUntil(5_000) { transport.requests.count { it.request.method == "PUT" && it.path == "hints/settings" } == 1 }
        compose.waitUntil(5_000) { !model.ui.value.isSaving }
        compose.onNodeWithContentDescription("Jeden Tag eine Ruhezeit einhalten").tap()
        compose.waitUntil(5_000) { transport.requests.count { it.request.method == "PUT" && it.path == "hints/settings" } == 2 }
        compose.waitUntil(5_000) { !model.ui.value.isSaving }
        model.dismissMessage()
        compose.onNodeWithContentDescription("Eine tägliche Zusammenfassung am Abend").tap()
        compose.waitUntil(5_000) { transport.requests.count { it.request.method == "PUT" && it.path == "hints/settings" } == 3 }
        // The snackbar of the confirmation would cover the buttons below.
        compose.waitUntil(5_000) { model.ui.value.message != null }
        model.dismissMessage()

        compose.onNodeWithTag("pause.3").tap()
        compose.waitUntil(5_000) { model.ui.value.settings?.pausedUntil != null }
        shot("settings-paused")
        model.dismissMessage()
        compose.onNodeWithTag("pause.end").tap()
        compose.waitUntil(5_000) { model.ui.value.settings?.pausedUntil == null }

        compose.onNodeWithContentDescription("Push-Hinweis bei neuen Chat-Nachrichten").tap()
        compose.waitUntil(5_000) { model.ui.value.message == "Chat-Push ist an." }
        model.dismissMessage()
        compose.onNodeWithTag("push.toggle").tap()

        compose.onNodeWithText("Zurück zu deinen Hinweisen").tap()
        assertTrue(opened().isEmpty())
    }

    @Test
    fun theSettingsShowRefusedTimesAndTheAndroidSettingsWhenDenied() {
        val refused = settings.copy(quietEnabled = true, chat = emptyList(), push = settings.push.copy(devices = emptyList()))
        show {
            HintSettingsContent(
                HintSettingsUiState(
                    state = Loadable.Loaded(refused),
                    fieldErrors = mapOf("quiet_from" to "Bitte eine Uhrzeit wählen."),
                    pushSupported = true,
                    authorization = PushAuthorization.Denied
                ),
                HintSettingsActions({}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {})
            )
        }
        compose.onNodeWithText("Bitte eine Uhrzeit wählen.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Android-Einstellungen öffnen").performScrollTo().assertIsDisplayed()
        shot("settings-denied")
    }

    @Test
    fun theSettingsAskForThePermissionAndShowNothingWithoutSettings() {
        val system = FakePushSystem(status = PushAuthorization.NotDetermined)
        val transport = StubTransport(
            StubTransport.Stub("GET", "hints/settings", json = LiveJson.SETTINGS.replace("\"current\":true", "\"current\":false"))
        )
        val model = HintSettingsViewModel(repository(transport), registrar(system))
        show { HintSettingsScreen(model) }
        compose.waitUntil(5_000) { model.ui.value.settings != null }
        compose.onNodeWithTag("push.toggle").tap()
        compose.waitForIdle()
        val request = shadowOf(compose.activity).lastRequestedPermission
        assertEquals(listOf(android.Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions.toList())
        @Suppress("DEPRECATION")
        compose.activity.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            intArrayOf(PackageManager.PERMISSION_DENIED)
        )
        compose.waitUntil(5_000) { system.answers == listOf(false) }
        compose.waitUntil(5_000) { model.ui.value.error == PushRegistrar.DENIED_TEXT }
    }

    @Test
    fun theSettingsFormIsEmptyWithoutSettings() {
        show { HintSettingsContent(HintSettingsUiState(), HintSettingsActions({}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {})) }
        compose.onNodeWithText("Hinweise einstellen").assertDoesNotExist()
    }

    @Test
    fun theBellRingsForNewHintsAndOpensTheInbox() {
        var unread by mutableIntStateOf(0)
        show { HintsBell(unread) { navigator.open(LiveRoute.Inbox) } }
        compose.onNodeWithContentDescription("Hinweise").performClick()
        unread = 2
        compose.onNodeWithContentDescription("Hinweise, 2 neu").assertIsDisplayed()
        unread = 1
        compose.onNodeWithContentDescription("Hinweise, 1 neu").assertIsDisplayed()
        shot("bell")
        assertEquals(LiveRoute.Inbox, opened().single())
    }

    @Test
    fun theShellShowsTheBellAndTheChatNoticeOfTheCoordinator() {
        val transport = StubTransport(StubTransport.Stub("GET", "live/counts", json = LiveJson.COUNTS))
        val live = coordinator(transport, scope)
        show {
            HintsButton(live)
            ChatToastOverlay(live)
        }
        compose.onNodeWithTag("hints.bell").performClick()
        assertEquals(LiveRoute.Inbox, opened().single())

        live.counts.showToast(ChatToast("Neue Nachricht von Jonas", 8))
        compose.onNodeWithTag("chatToast.open").performClick()
        assertEquals(ChatRoute.Conversation(8), navigator.stack(navigator.selectedTab).last())

        live.counts.showToast(ChatToast("Neue Chat-Nachricht", null))
        shot("chat-notice")
        compose.onNodeWithTag("chatToast.open").performClick()
        compose.waitForIdle()
        live.counts.showToast(ChatToast("Neue Nachricht von Lea", 2))
        compose.onNodeWithTag("chatToast.close").performClick()
        compose.waitUntil(5_000) { live.counts.toast.value == null }
    }

    @Test
    fun theChatNoticeStaysHiddenWithoutAMessage() {
        show { ChatToastContent(null, {}, {}) }
        compose.onNodeWithTag("chatToast").assertDoesNotExist()
        assertTrue(Instant.now().isAfter(Instant.EPOCH))
    }

    @Test
    fun theSettingsShowAServerWithoutPushAndADeviceThatIsOnDespiteTheSystem() {
        val actions = HintSettingsActions({}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {})
        var variant by mutableIntStateOf(0)
        show {
            val ui = if (variant == 0) {
                HintSettingsUiState(
                    state = Loadable.Loaded(settings.copy(push = settings.push.copy(available = false))),
                    pushSupported = true
                )
            } else {
                HintSettingsUiState(state = Loadable.Loaded(settings), pushSupported = true, authorization = PushAuthorization.Denied)
            }
            HintSettingsContent(ui, actions)
        }
        compose.onNodeWithText(
            "Push-Hinweise sind auf Oasis noch nicht eingerichtet.",
            substring = true
        ).performScrollTo().assertIsDisplayed()
        shot("settings-no-push")
        variant = 1
        compose.onNodeWithText("Auf diesem Gerät ausschalten").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theSettingsSwitchPushOffOnThisDevice() {
        val transport = StubTransport(
            StubTransport.Stub("GET", "hints/settings", json = LiveJson.SETTINGS),
            StubTransport.Stub("GET", "app-config", json = LiveJson.CONFIG)
        )
        val model = HintSettingsViewModel(repository(transport), registrar())
        show { HintSettingsScreen(model) }
        compose.waitUntil(5_000) { model.ui.value.settings != null }
        compose.onNodeWithTag("push.toggle").tap()
        compose.waitUntil(5_000) { model.ui.value.message == "Push-Hinweise sind auf diesem Gerät ausgeschaltet." }
    }

    @Test
    fun aHintWithoutAppScreenOpensInTheBrowser() {
        val web = mutableListOf<String>()
        navigator.openWeb = { web += it }
        val transport = StubTransport(
            StubTransport.Stub(
                "GET",
                "hints",
                json = LiveJson.HINTS.replace("""http:\/\/localhost\/profil\/emil""", """https:\/\/example.com\/x""")
            ),
            StubTransport.Stub("GET", "live/counts", json = LiveJson.COUNTS)
        )
        val model = HintsInboxViewModel(repository(transport), coordinator(transport, scope))
        show { HintsInboxScreen(model) }
        compose.waitUntil(5_000) { model.ui.value.state is Loadable.Loaded }
        compose.onNodeWithText("Neue Person in deiner Karawane · Emil Novak").performClick()
        assertEquals(listOf("https://example.com/x"), web)
    }

    @Test
    fun theScreensFollowANewViewModelAndStayWhenTheirParentRedraws() {
        val transport = StubTransport(
            StubTransport.Stub("GET", "hints", json = LiveJson.HINTS),
            StubTransport.Stub("GET", "hints/settings", json = LiveJson.SETTINGS),
            StubTransport.Stub("GET", "live/counts", json = LiveJson.COUNTS)
        )
        val inboxes = List(2) { HintsInboxViewModel(repository(transport), coordinator(transport, scope)) }
        val settingsModels = List(2) { HintSettingsViewModel(repository(transport), registrar()) }
        val lives = List(2) { coordinator(transport, scope) }
        var tick by mutableIntStateOf(0)
        var current by mutableIntStateOf(0)
        show {
            Column {
                Text("Durchgang $tick")
                HintsButton(lives[current])
                ChatToastOverlay(lives[current])
                Column(Modifier.height(200.dp)) { HintsInboxScreen(inboxes[current]) }
                Column(Modifier.height(200.dp)) { HintSettingsScreen(settingsModels[current]) }
            }
        }
        compose.waitUntil(5_000) {
            inboxes.all { it.ui.value.state is Loadable.Loaded } &&
                settingsModels.all { it.ui.value.settings != null }
        }
        tick = 1
        compose.onNodeWithText("Durchgang 1").assertIsDisplayed()
        current = 1
        compose.waitForIdle()
        tick = 2
        compose.onNodeWithText("Durchgang 2").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [32])
    fun beforeAndroid13PushIsSwitchedWithoutAPermissionPrompt() {
        val transport = StubTransport(
            StubTransport.Stub("GET", "hints/settings", json = LiveJson.SETTINGS.replace("\"current\":true", "\"current\":false"))
        )
        val model = HintSettingsViewModel(repository(transport), registrar(FakePushSystem(status = PushAuthorization.NotDetermined)))
        show { HintSettingsScreen(model) }
        compose.waitUntil(5_000) { model.ui.value.settings != null }
        compose.onNodeWithTag("push.toggle").tap()
        compose.waitUntil(5_000) { model.ui.value.error == PushRegistrar.DENIED_TEXT }
    }
}
