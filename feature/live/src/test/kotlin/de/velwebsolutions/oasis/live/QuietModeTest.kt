package de.velwebsolutions.oasis.live

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.live.api.HintSettings
import de.velwebsolutions.oasis.live.api.LiveCountsData
import de.velwebsolutions.oasis.live.api.QuietMode
import de.velwebsolutions.oasis.live.api.QuietModeChoice
import de.velwebsolutions.oasis.live.ui.HintSettingsActions
import de.velwebsolutions.oasis.live.ui.HintSettingsContent
import de.velwebsolutions.oasis.live.ui.HintSettingsUiState
import de.velwebsolutions.oasis.live.ui.HintSettingsViewModel
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import de.velwebsolutions.oasis.testing.StubTransport
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Ruhemodus (docs/mildcrowd-notifications.md): its status texts, the banner on every screen with „Beenden“, and the
 * section in Hinweise einstellen – one state for both.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp")
class QuietModeTest {
    @get:Rule val main = MainDispatcherRule()

    @get:Rule val compose = createComposeRule()

    private val modes = """[{"key":"1h","label":"1 Stunde"},{"key":"8h","label":"8 Stunden"},""" +
        """{"key":"morning","label":"Bis morgen früh"},{"key":"manual","label":"Bis ich ihn ausschalte"}]"""

    private fun settings(active: Boolean, until: String = "null", message: String? = null): String {
        val data = LiveJson.SETTINGS.removePrefix("""{"data":""").removeSuffix("}}") +
            ""","quiet_mode":{"active":$active,"until":$until,"modes":$modes}}"""
        return if (message == null) """{"data":$data}""" else """{"data":$data,"message":"$message"}"""
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val roots = compose.onAllNodes(isRoot())
        roots[roots.fetchSemanticsNodes().size - 1].captureRoboImage("build/outputs/roborazzi/quiet-mode-$name.png")
    }

    @Test
    fun theStatusSaysUntilWhenInGermanTime() {
        val now = Instant.parse("2026-11-03T20:00:00Z")
        assertEquals("Ruhemodus bis 22:00 Uhr.", QuietModeState.status(Instant.parse("2026-11-03T21:00:00Z"), now))
        assertEquals("Ruhemodus bis Mittwoch, 4. November, 07:00 Uhr.", QuietModeState.status(Instant.parse("2026-11-04T06:00:00Z"), now))
        assertEquals("Ruhemodus an, bis du ihn ausschaltest.", QuietModeState.status(null, now))
        assertTrue(QuietModeState.status(Instant.now().plusSeconds(60)).startsWith("Ruhemodus bis "))
    }

    @Test
    fun theCountsCarryTheModeAndTheBannerEndsIt() = runTest {
        val counts = OasisJson.snake.decodeFromString(
            LiveCountsData.serializer(),
            """{"chats":0,"hints":1,"quiet_mode":{"active":true,"until":"2026-11-04T06:00:00.000000Z"}}"""
        )
        assertTrue(counts.quietMode.active)
        val transport = StubTransport(
            StubTransport.Stub("GET", "live/counts", json = """{"data":{"chats":0,"hints":1,"quiet_mode":{"active":true,"until":null}}}"""),
            StubTransport.Stub("DELETE", "hints/quiet-mode", json = settings(active = false, message = "Der Ruhemodus ist beendet."))
        )
        val live = coordinator(transport, backgroundScope)
        live.counts.start(load = { repository(transport).counts() }, realtime = FakeRealtime(), userId = 7)
        live.refresh()
        assertEquals(QuietMode(active = true), live.quietMode.value)
        assertEquals("Der Ruhemodus ist beendet.", live.endQuietMode())
        assertFalse(live.quietMode.value.active)
        assertEquals("the choices stay known", 4, live.quietMode.value.modes.size)
        live.counts.apply(LiveCountsData(quietMode = QuietMode(active = true)))
        assertEquals(4, live.quietMode.value.modes.size)
        live.counts.stop()
        assertEquals(QuietMode(), live.quietMode.value)
    }

    @Test
    fun theSettingsStartAndEndItForEveryScreen() = runTest {
        val transport = StubTransport(
            StubTransport.Stub("GET", "hints/settings", json = settings(active = false)),
            StubTransport.Stub(
                "POST",
                "hints/quiet-mode",
                json = settings(
                    active = true,
                    until = "\"2026-11-04T06:00:00.000000Z\"",
                    message = "Ruhemodus bis Mittwoch, 4. November, 07:00 Uhr."
                )
            ),
            StubTransport.Stub("DELETE", "hints/quiet-mode", status = 500, json = """{"message":"Server Error"}""")
        )
        val quiet = QuietModeState()
        val model = HintSettingsViewModel(repository(transport), registrar(), quiet)
        model.ui.first { it.state is Loadable.Loaded }
        assertEquals(listOf("1h", "8h", "morning", "manual"), model.ui.value.settings?.quietMode?.modes?.map { it.key })

        model.startQuietMode("morning")
        assertEquals(JsonObject(mapOf("mode" to JsonPrimitive("morning"))), transport.lastBody("hints/quiet-mode"))
        assertEquals("Ruhemodus bis Mittwoch, 4. November, 07:00 Uhr.", model.ui.value.message)
        assertTrue("the banner knows at once", quiet.mode.value.active)
        model.ui.first { it.quietMode.active }

        model.endQuietMode()
        assertTrue("a failed end keeps it on", quiet.mode.value.active)
        assertTrue(model.ui.value.error != null)
    }

    @Test
    fun theBannerFollowsTheAppsState() = runTest {
        val live = coordinator(StubTransport(), backgroundScope)
        compose.setContent { OasisTheme { QuietModeBanner(live) } }
        compose.onNodeWithTag("quietMode.banner").assertDoesNotExist()
        live.counts.apply(LiveCountsData(quietMode = QuietMode(active = true)))
        compose.onNodeWithText("Ruhemodus an, bis du ihn ausschaltest.").assertExists()
    }

    @Test
    fun theBannerWithBeendenAndWhatFollows() {
        var mode by mutableStateOf(QuietMode(active = true, until = null))
        var answer: () -> String? = { "Der Ruhemodus ist beendet." }
        compose.setContent { OasisTheme { QuietModeBannerContent(mode) { answer() } } }
        compose.onNodeWithText("Ruhemodus an, bis du ihn ausschaltest.").assertExists()
        capture("banner")
        compose.onNodeWithTag("quietMode.banner.end").performClick()
        mode = QuietMode()
        compose.waitForIdle()
        compose.onNodeWithText("Der Ruhemodus ist beendet.").assertExists()
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithTag("quietMode.banner").assertDoesNotExist()

        mode = QuietMode(active = true)
        answer = { throw IllegalStateException("kaputt") }
        compose.onNodeWithTag("quietMode.banner.end").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("quietMode.banner").assertExists()
    }

    @Test
    fun theSectionInHinweiseEinstellen() {
        val calls = mutableListOf<String>()
        val choices = listOf(QuietModeChoice("1h", "1 Stunde"), QuietModeChoice("manual", "Bis ich ihn ausschalte"))
        var ui by mutableStateOf(
            HintSettingsUiState(state = Loadable.Loaded(HintSettings(quietMode = QuietMode(modes = choices))))
        )
        val actions = HintSettingsActions(
            {},
            {},
            { _, _ -> },
            {},
            {},
            { calls += "start $it" },
            { calls += "end" },
            {},
            {},
            {},
            {}
        )
        compose.setContent { OasisTheme { HintSettingsContent(ui, actions) } }
        compose.onNodeWithTag("quietMode.1h").performScrollTo().performClick()
        capture("settings-off")
        ui = ui.copy(quietMode = QuietMode(active = true, until = Instant.parse("2099-01-01T06:00:00Z")))
        compose.onNodeWithTag("quietMode.status").performScrollTo()
        compose.onNodeWithText("Ruhemodus bis Donnerstag, 1. Januar, 07:00 Uhr. Hinweise sammeln sich still in der App.").assertExists()
        capture("settings-on")
        compose.onNodeWithTag("quietMode.end").performClick()
        compose.waitForIdle()
        assertEquals(listOf("start 1h", "end"), calls)
        assertNull(ui.settings?.pausedUntil)
    }
}
