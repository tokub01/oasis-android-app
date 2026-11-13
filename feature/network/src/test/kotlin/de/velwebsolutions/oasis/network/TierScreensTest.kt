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
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import de.velwebsolutions.oasis.network.ui.components.clampTime
import de.velwebsolutions.oasis.network.ui.components.pickedDay
import de.velwebsolutions.oasis.network.ui.components.selectableDay
import de.velwebsolutions.oasis.network.ui.oases.OasisStatisticsScreen
import de.velwebsolutions.oasis.network.ui.oases.OasisStatisticsViewModel
import de.velwebsolutions.oasis.network.ui.profiles.CapsuleDraft
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Zeitkapseln, the Jahrbuch, the statistics below Oase and the supporter mark on screen, with their real view models.
 *
 * The v2 compose rule runs the screens' effects on a StandardTestDispatcher, so an answer from OkHttp's thread resumes
 * on the main thread as in the app. The v1 rule's UnconfinedTestDispatcher resumed it on OkHttp's thread; after an
 * earlier test in the same JVM, the state written there was sometimes never recomposed and the Jahrbuch stayed at
 * "Lädt …" although its view model had loaded.
 */
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
        // Datenschutzerklärung Abschnitt 15: the check runs while sealing (ManageTimeCapsules) and again when opening.
        text(
            "Beim Versiegeln und beim Aufgehen prüfen wir sie wie jeden Tropfen. " +
                "Läuft deine Mitgliedschaft beim Aufgehen nicht, bleibt sie zu."
        )

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
    fun `Zeitkapseln say when one is opening right now, and only a not opened one can be deleted`() {
        val network = StubbedNetwork(
            stub(
                "GET",
                "network/time-capsules",
                """{"data":{"available":true,"max_length":500,"past":[
                {"id":3,"body":"Ein Jahr Mildcrowd","opens_at":"2026-10-01T08:00:00.000000Z","opened_at":"2026-10-01T08:00:00.000000Z","post_id":44},
                {"id":4,"body":"Ohne Mitgliedschaft","opens_at":"2026-09-01T08:00:00.000000Z","failed_reason":"Deine Mitgliedschaft lief nicht."},
                {"id":8,"body":"Gleich da.","opens_at":"2026-10-02T08:00:00.000000Z","opened_at":"2026-10-02T08:00:01.000000Z"}]}}"""
            ),
            stub("DELETE", "network/time-capsules/4", """{"message":"Die Zeitkapsel ist gelöscht."}""")
        )
        val model = models.keep(TimeCapsulesViewModel(network.repository))
        show { TimeCapsulesScreen(model) }
        text("Geht gerade auf …")
        text("Nicht aufgegangen: Deine Mitgliedschaft lief nicht.")
        assertTrue(compose.onAllNodes(hasTestTag("capsule.delete.3")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasTestTag("capsule.delete.8")).fetchSemanticsNodes().isEmpty())

        compose.onNodeWithTag("capsule.delete.4").performClick()
        text("Die Zeitkapsel löschen?")
        compose.onNodeWithTag("dialog.cancel").performClick()
        compose.onNodeWithTag("capsule.delete.4").performClick()
        text("Das lässt sich nicht rückgängig machen.")
        compose.onNodeWithTag("dialog.confirm").performClick()
        text("Die Zeitkapsel ist gelöscht.")
    }

    @Test
    fun `Zeitkapseln below Oase, without capsules and with a field error`() {
        val locked = StubbedNetwork(stub("GET", "network/time-capsules", TierFixtures.lockedCapsules))
        val lockedModel = models.keep(TimeCapsulesViewModel(locked.repository))
        show { TimeCapsulesScreen(lockedModel) }
        text("AB OASE")
        text("Schreib heute einen Tropfen, der erst an einem Tag deiner Wahl erscheint", substring = true)
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
    fun `the picker keeps the opening between now and max_years`() {
        val zone = ZoneId.of("Europe/Berlin")
        val earliest = Instant.parse("2026-11-02T10:30:00Z")
        val latest = CapsuleDraft.latestOpening(earliest, 10, zone)
        assertEquals(Instant.parse("2036-11-02T10:30:00Z"), latest)
        assertEquals(earliest, clampTime(Instant.parse("2026-11-01T10:00:00Z"), earliest, latest))
        assertEquals(latest, clampTime(Instant.parse("2040-01-01T00:00:00Z"), earliest, latest))
        val inside = Instant.parse("2030-01-01T00:00:00Z")
        assertEquals(inside, clampTime(inside, earliest, latest))
        assertEquals(inside, clampTime(inside, null, null))
        fun day(text: String) = Instant.parse("${text}T00:00:00Z").toEpochMilli()
        assertTrue(selectableDay(day("2026-11-02"), earliest, latest, zone))
        assertFalse(selectableDay(day("2026-11-01"), earliest, latest, zone))
        assertTrue(selectableDay(day("2036-11-02"), earliest, latest, zone))
        assertFalse(selectableDay(day("2036-11-03"), earliest, latest, zone))
        assertTrue(selectableDay(day("1990-01-01"), null, null, zone))

        // A time before the earliest on its first day moves to the earliest.
        var chosen: Instant? = null
        show {
            DateTimeField("Geht auf am", Instant.parse("2026-11-02T08:00:00Z"), {
                chosen = it
            }, zone = zone, earliest = earliest, latest = latest)
        }
        compose.onNodeWithTag("datetime.field").performClick()
        compose.onNodeWithTag("datetime.next").performClick()
        compose.onNodeWithTag("datetime.done").performClick()
        assertEquals(earliest, chosen)
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
        text("AB OASE")
        text("Deine Tropfen und Fotos eines Jahres", substring = true)

        switchTo { StatisticsScreen(numbers) }
        text("Mitgliedschaft ansehen")
        text("Deine Zahlen")

        switchTo { OasisStatisticsScreen("stadtgarten", host) }
        text("Statistik deiner Oase")
        text("Mitgliedschaft ansehen").performClick()
        assertTrue(MembershipRoute.Overview in navigator.stack(MainTab.Profile))
    }

    @Test
    fun `a supporter's profile says that they carry Oasis`() {
        val supporter = Fixtures.profile.replace(""""founding_badge": false}}""", """"founding_badge": false, "supporter": true}}""")
        val network = StubbedNetwork(
            stub("GET", "network/profiles/jonas", supporter),
            stub("GET", "network/profiles/jonas/posts", pageJson())
        )
        val model = models.keep(ProfileViewModel("jonas", network.repository))
        show { ProfileScreen("jonas", model) }
        text("Trägt Mildcrowd mit")
        compose.onNodeWithTag("profile.founding").assertDoesNotExist()
    }

    @Test
    fun `a founding member who shows the mark has it next to the supporter mark`() {
        val founder = Fixtures.profile.replace(""""founding_badge": false}}""", """"founding_badge": true, "supporter": true}}""")
        val network = StubbedNetwork(
            stub("GET", "network/profiles/jonas", founder),
            stub("GET", "network/profiles/jonas/posts", pageJson())
        )
        val model = models.keep(ProfileViewModel("jonas", network.repository))
        show { ProfileScreen("jonas", model) }
        text("Trägt Mildcrowd mit")
        text("Gründungsmitglied")
        compose.onNodeWithTag("profile.founding").assertExists()
    }
}
