package de.velwebsolutions.oasis.live.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.DataEnvelope
import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.core.network.Page
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.live.ChatToast
import de.velwebsolutions.oasis.live.ChatToastContent
import de.velwebsolutions.oasis.live.HintsBell
import de.velwebsolutions.oasis.live.LiveJson
import de.velwebsolutions.oasis.live.api.Hint
import de.velwebsolutions.oasis.live.api.HintSettings
import de.velwebsolutions.oasis.live.push.PushAuthorization
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Hinweise screens, the bell and the chat notice on the JVM (iOS LiveUITests). */
@RunWith(RobolectricTestRunner::class)
class LiveUiTest {
    @get:Rule val compose = createComposeRule()

    private val hints: List<Hint> = OasisJson.snake.decodeFromString<Page<Hint>>(LiveJson.HINTS).data
    private val settings: HintSettings = OasisJson.snake.decodeFromString<DataEnvelope<HintSettings>>(LiveJson.SETTINGS).data

    @Test
    fun showsTheHintsUnderTheirHeadingsWithNeuAndOpensOne() {
        val opened = mutableListOf<String>()
        var readAll = 0
        compose.setContent {
            HintsInboxContent(
                ui = HintsInboxUiState(state = Loadable.Loaded(hints)),
                unreadAtTheBell = 0,
                onRefresh = {},
                onMarkAllRead = { readAll += 1 },
                onLoadMore = {},
                onOpen = { opened += it },
                onSettings = {}
            )
        }
        compose.onNodeWithText("Heute").assertIsDisplayed()
        compose.onNodeWithText("Neu").assertIsDisplayed()
        compose.onNodeWithText("Neue Person in deiner Karawane · Emil Novak").performClick()
        assertEquals("http://localhost/profil/emil", opened.single())
        compose.onNodeWithTag("hints.readAll").performClick()
        compose.waitUntil { readAll == 1 }
    }

    @Test
    fun showsTheEmptyListWithoutReadAll() {
        compose.setContent {
            HintsInboxContent(HintsInboxUiState(state = Loadable.Loaded(emptyList())), 0, {}, {}, {}, {}, {})
        }
        compose.onNodeWithTag("hints.empty").assertIsDisplayed()
        compose.onNodeWithText("Hier gibt es gerade keine Hinweise für dich.").assertIsDisplayed()
        compose.onNodeWithTag("hints.readAll").assertDoesNotExist()
    }

    private fun actions(toggled: MutableList<HintSettings>, base: HintSettings) = HintSettingsActions(
        toggle = { change -> toggled += change(base) },
        editQuietHours = {},
        toggleChat = { _, _ -> },
        pause = {},
        resume = {},
        remove = {},
        togglePush = {},
        openSystemSettings = {},
        back = {}
    )

    @Test
    fun switchesAKindAndSaysPushIsNotThereYetOnAndroid() {
        val toggled = mutableListOf<HintSettings>()
        compose.setContent {
            CompositionLocalProvider(LocalNavigator provides Navigator()) {
                HintSettingsContent(
                    HintSettingsUiState(state = Loadable.Loaded(settings), pushSupported = false),
                    actions(toggled, settings)
                )
            }
        }
        compose.onNodeWithContentDescription("Antwort auf deinen Tropfen").performClick()
        assertEquals(false, toggled.single().kinds.first { it.key == "reply" }.enabled)
        compose.onNodeWithContentDescription("Eine tägliche Zusammenfassung am Abend").assertIsOff()
        compose.onNodeWithTag("push.unavailable").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Chrome auf Android", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pause.3").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("3 Tage").assertIsDisplayed()
    }

    @Test
    fun offersPushOnThisDeviceOnceSupported() {
        compose.setContent {
            HintSettingsContent(
                HintSettingsUiState(state = Loadable.Loaded(settings), pushSupported = true, authorization = PushAuthorization.Authorized),
                actions(mutableListOf(), settings)
            )
        }
        compose.onNodeWithTag("push.state").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Auf diesem Gerät sind Push-Hinweise eingeschaltet.").assertIsDisplayed()
        compose.onNodeWithText("Auf diesem Gerät ausschalten").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theBellSaysHowManyAreNew() {
        var taps = 0
        compose.setContent { HintsBell(3) { taps += 1 } }
        compose.onNodeWithContentDescription("Hinweise, 3 neu").performClick()
        assertEquals(1, taps)
    }

    @Test
    fun theChatNoticeOpensTheChatAndCloses() {
        val opened = mutableListOf<ChatToast>()
        var closed = 0
        val toast = ChatToast("Neue Nachricht von Jonas", 8)
        compose.setContent { ChatToastContent(toast, onOpen = { opened += it }, onClose = { closed += 1 }) }
        compose.onNodeWithText("Neue Nachricht von Jonas").assertIsDisplayed()
        compose.onNodeWithTag("chatToast.open").performClick()
        assertEquals(8, opened.single().conversationId)
        compose.onNodeWithContentDescription("Schließen").performClick()
        assertEquals(1, closed)
    }
}
