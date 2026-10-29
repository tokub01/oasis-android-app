package de.velwebsolutions.oasis.network

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import de.velwebsolutions.oasis.design.components.LocalApiClient
import de.velwebsolutions.oasis.design.components.LocalScreenChrome
import de.velwebsolutions.oasis.design.components.ScreenChrome
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.navigation.MembershipRoute
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.network.ui.components.DateTimeField
import de.velwebsolutions.oasis.network.ui.components.pickedDay
import de.velwebsolutions.oasis.network.ui.oases.OasisStatisticsScreen
import de.velwebsolutions.oasis.network.ui.oases.OasisStatisticsViewModel
import de.velwebsolutions.oasis.network.ui.profiles.ProfileScreen
import de.velwebsolutions.oasis.network.ui.profiles.ProfileViewModel
import de.velwebsolutions.oasis.network.ui.profiles.StatisticsScreen
import de.velwebsolutions.oasis.network.ui.profiles.StatisticsViewModel
import de.velwebsolutions.oasis.network.ui.profiles.TimeCapsulesScreen
import de.velwebsolutions.oasis.network.ui.profiles.TimeCapsulesViewModel
import de.velwebsolutions.oasis.network.ui.profiles.YearbookScreen
import de.velwebsolutions.oasis.network.ui.profiles.YearbookViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Zeitkapseln, the Jahrbuch, the statistics below Oase and the supporter mark on screen, with their real view models. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h4000dp")
class TierScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val web = mutableListOf<String>()
    private val navigator = Navigator().apply {
        selectedTab = MainTab.Profile
        openWeb = { web += it }
    }
    private val models = ViewModelKeeper()
    private var screen by mutableStateOf<@Composable () -> Unit>({})
    private val client = StubbedNetwork().transport.client

    @After
    fun clear() = models.clear()

    private fun show(content: @Composable () -> Unit) {
        screen = content
        compose.setContent {
            OasisTheme {
                CompositionLocalProvider(
                    LocalNavigator provides navigator,
                    LocalScreenChrome provides ScreenChrome(onBack = { navigator.back() }),
                    LocalApiClient provides client
                ) { screen() }
            }
        }
    }

    private fun switchTo(content: @Composable () -> Unit) {
        screen = content
        compose.waitForIdle()
    }

    private fun text(text: String, substring: Boolean = false): SemanticsNodeInteraction {
        compose.settle { compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNodeWithText(text, substring = substring)
    }

    @Test
    fun `Zeitkapseln seal a draft, open and delete waiting ones and link the opened ones`() {
        val network = StubbedNetwork(
            stub("GET", "network/time-capsules", TierFixtures.capsules),
            stub("POST", "network/time-capsules", """{"message":"Deine Zeitkapsel ist versiegelt.","data":{}}""", 201),
            stub("POST", "network/time-capsules/5/open", """{"message":"Deine Zeitkapsel ist aufgegangen.","data":{}}"""),
            stub("DELETE", "network/time-capsules/6", """{"message":"Die Zeitkapsel ist gelöscht."}""")
        )
        val model = models.keep(TimeCapsulesViewModel(network.repository))
        show { TimeCapsulesScreen(model) }
        text("Warten noch")
        text("in deiner Quelle", substring = true)
        text("in Stadtgarten", substring = true)
        text("Nicht aufgegangen: Deine Mitgliedschaft lief nicht.")
        text("Bis zu 50 Zeitkapseln können gleichzeitig warten.")

        compose.onNode(hasSetTextAction()).performTextInput("Frohe Weihnachten")
        text("17 / 1000")
        compose.onNodeWithTag("datetime.field").performClick()
        compose.onNodeWithTag("datetime.next").performClick()
        compose.onNodeWithTag("datetime.done").performClick()
        compose.onNodeWithTag("capsule.place").performClick()
        text("in der Oase Stadtgarten").performClick()
        compose.settle { model.draft.value.oasisId == 2 }
        compose.onNodeWithTag("capsule.seal").performClick()
        text("Deine Zeitkapsel ist versiegelt.")

        text("Jetzt öffnen", substring = false)
        compose.onAllNodes(hasText("Jetzt öffnen"))[0].performClick()
        text("Die Zeitkapsel jetzt schon öffnen?")
        compose.onNodeWithTag("dialog.confirm").performClick()
        text("Deine Zeitkapsel ist aufgegangen.")
        compose.onAllNodes(hasText("Löschen"))[1].performClick()
        text("Das lässt sich nicht rückgängig machen.")
        compose.onNodeWithTag("dialog.confirm").performClick()
        text("Die Zeitkapsel ist gelöscht.")

        text("Aufgegangen am", substring = true).performClick()
        assertEquals(NetworkRoute.Post(44), navigator.stack(MainTab.Profile).last())
    }

    @Test
    fun `Zeitkapseln below Oase, without capsules and with a field error`() {
        val locked = StubbedNetwork(stub("GET", "network/time-capsules", TierFixtures.lockedCapsules))
        val lockedModel = models.keep(TimeCapsulesViewModel(locked.repository))
        show { TimeCapsulesScreen(lockedModel) }
        text("Das gibt es ab der Mitgliedschaft Oase.")
        text("Mitgliedschaft ansehen").performClick()
        assertEquals(MembershipRoute.Overview, navigator.stack(MainTab.Profile).last())

        val old = StubbedNetwork(stub("GET", "network/time-capsules", """{"data":{"available":false}}"""))
        val oldModel = models.keep(TimeCapsulesViewModel(old.repository))
        switchTo { TimeCapsulesScreen(oldModel) }
        text("Zeitkapseln")

        val empty = StubbedNetwork(
            stub("GET", "network/time-capsules", """{"data":{"available":true,"max_length":500}}"""),
            stub("POST", "network/time-capsules", """{"message":"Zu kurz.","errors":{"body":["Schreib etwas."]}}""", 422)
        )
        val model = models.keep(TimeCapsulesViewModel(empty.repository))
        switchTo { TimeCapsulesScreen(model) }
        text("Noch keine Zeitkapsel versiegelt.")
        compose.onNode(hasSetTextAction()).performTextInput(" x")
        compose.onNodeWithTag("capsule.seal").performClick()
        text("Schreib etwas.")
    }

    @Test
    fun `a day typed in by keyboard needs a whole date before Weiter`() {
        assertNull(pickedDay(null))
        assertEquals(LocalDate.of(2026, 12, 31), pickedDay(Instant.parse("2026-12-31T00:00:00Z").toEpochMilli()))
        var chosen: Instant? = null
        show { DateTimeField("Geht auf am", Instant.parse("2026-12-24T17:00:00Z"), { chosen = it }, zone = ZoneId.of("Europe/Berlin")) }
        compose.onNodeWithTag("datetime.field").performClick()
        // The pencil of Material's date picker: the day as text, which can be empty or half typed.
        compose.onNodeWithContentDescription("Switch to text input mode").performClick()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNodeWithTag("datetime.next").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("datetime.next").assertExists()

        compose.onNode(hasSetTextAction()).performTextInput("12312026")
        compose.onNodeWithTag("datetime.next").assertIsEnabled().performClick()
        compose.onNodeWithTag("datetime.done").performClick()
        assertEquals(Instant.parse("2026-12-31T17:00:00Z"), chosen)
    }

    @Test
    fun `the Jahrbuch switches years, prints on the website and shows an empty year`() {
        val network = StubbedNetwork(
            stub("GET", "network/yearbook", TierFixtures.yearbook),
            stub("GET", "network/yearbook/2025", TierFixtures.emptyYearbook)
        )
        val model = models.keep(YearbookViewModel(null, network.repository))
        show { YearbookScreen(null, model) }
        text("2 Tropfen · 1 Foto · in 1 Oase")
        text("Oktober")
        text("Rad & Werkstatt", substring = true)
        text("1 Reaktion · 2 Antworten")
        text("Stiller Tag")
        compose.onNodeWithTag("yearbook.print").performClick()
        assertEquals(listOf("http://localhost/app/jahrbuch/2026"), web)

        compose.onNodeWithTag("yearbook.year.2025").performClick()
        text("In 2025 hast du noch keine Tropfen geteilt. Was du schreibst, landet hier.")
    }

    @Test
    fun `below Oase the Jahrbuch and the numbers show the server's message`() {
        val network = StubbedNetwork(
            stub("GET", "network/yearbook/2024", TierFixtures.LOCKED, 403),
            stub("GET", "network/statistics", TierFixtures.LOCKED, 403),
            stub("GET", "network/oases/stadtgarten/statistics", TierFixtures.LOCKED, 403)
        )
        val book = models.keep(YearbookViewModel(2024, network.repository))
        val numbers = models.keep(StatisticsViewModel(network.repository))
        val host = models.keep(OasisStatisticsViewModel("stadtgarten", network.repository))
        show { YearbookScreen(2024, book) }
        text("Dein Jahrbuch")
        text("Das gibt es ab der Mitgliedschaft Oase.")

        switchTo { StatisticsScreen(numbers) }
        text("Mitgliedschaft ansehen")
        text("Deine Zahlen")

        switchTo { OasisStatisticsScreen("stadtgarten", host) }
        text("Mitgliedschaft ansehen").performClick()
        assertTrue(MembershipRoute.Overview in navigator.stack(MainTab.Profile))
    }

    @Test
    fun `a supporter's profile says that they carry Oasis`() {
        val supporter = Fixtures.profile.replace(""""has_restricted": false}}""", """"has_restricted": false, "supporter": true}}""")
        val network = StubbedNetwork(
            stub("GET", "network/profiles/jonas", supporter),
            stub("GET", "network/profiles/jonas/posts", pageJson())
        )
        val model = models.keep(ProfileViewModel("jonas", network.repository))
        show { ProfileScreen("jonas", model) }
        text("Trägt Oasis mit")
    }
}
