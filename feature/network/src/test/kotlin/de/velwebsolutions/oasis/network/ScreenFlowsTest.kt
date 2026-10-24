package de.velwebsolutions.oasis.network

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import de.velwebsolutions.oasis.design.components.LocalScreenChrome
import de.velwebsolutions.oasis.design.components.ScreenChrome
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.network.ui.compose.ComposeViewModel
import de.velwebsolutions.oasis.network.ui.feeds.BookmarksScreen
import de.velwebsolutions.oasis.network.ui.feeds.BookmarksViewModel
import de.velwebsolutions.oasis.network.ui.feeds.DiscoverScreen
import de.velwebsolutions.oasis.network.ui.feeds.DiscoverViewModel
import de.velwebsolutions.oasis.network.ui.feeds.SearchScreen
import de.velwebsolutions.oasis.network.ui.feeds.SearchViewModel
import de.velwebsolutions.oasis.network.ui.feeds.SourceScreen
import de.velwebsolutions.oasis.network.ui.feeds.SourceViewModel
import de.velwebsolutions.oasis.network.ui.feeds.SwipeScreen
import de.velwebsolutions.oasis.network.ui.feeds.SwipeViewModel
import de.velwebsolutions.oasis.network.ui.feeds.TagScreen
import de.velwebsolutions.oasis.network.ui.feeds.TagViewModel
import de.velwebsolutions.oasis.network.ui.oases.CloseOasisScreen
import de.velwebsolutions.oasis.network.ui.oases.CloseOasisViewModel
import de.velwebsolutions.oasis.network.ui.oases.OasesScreen
import de.velwebsolutions.oasis.network.ui.oases.OasesViewModel
import de.velwebsolutions.oasis.network.ui.oases.OasisDetailScreen
import de.velwebsolutions.oasis.network.ui.oases.OasisFormScreen
import de.velwebsolutions.oasis.network.ui.oases.OasisFormViewModel
import de.velwebsolutions.oasis.network.ui.oases.OasisStatisticsScreen
import de.velwebsolutions.oasis.network.ui.oases.OasisStatisticsViewModel
import de.velwebsolutions.oasis.network.ui.oases.OasisViewModel
import de.velwebsolutions.oasis.network.ui.posts.CarryScreen
import de.velwebsolutions.oasis.network.ui.posts.CarryViewModel
import de.velwebsolutions.oasis.network.ui.posts.ComposeScreen
import de.velwebsolutions.oasis.network.ui.posts.EditPostScreen
import de.velwebsolutions.oasis.network.ui.posts.EditPostViewModel
import de.velwebsolutions.oasis.network.ui.posts.PostDetailScreen
import de.velwebsolutions.oasis.network.ui.posts.PostDetailViewModel
import de.velwebsolutions.oasis.network.ui.profiles.EditProfileScreen
import de.velwebsolutions.oasis.network.ui.profiles.EditProfileViewModel
import de.velwebsolutions.oasis.network.ui.profiles.FriendsScreen
import de.velwebsolutions.oasis.network.ui.profiles.FriendsViewModel
import de.velwebsolutions.oasis.network.ui.profiles.InvitationScreen
import de.velwebsolutions.oasis.network.ui.profiles.InvitationViewModel
import de.velwebsolutions.oasis.network.ui.profiles.InvitationsScreen
import de.velwebsolutions.oasis.network.ui.profiles.InvitationsViewModel
import de.velwebsolutions.oasis.network.ui.profiles.ProfileScreen
import de.velwebsolutions.oasis.network.ui.profiles.ProfileViewModel
import de.velwebsolutions.oasis.network.ui.profiles.StatisticsScreen
import de.velwebsolutions.oasis.network.ui.profiles.StatisticsViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Whole screens with their view models over canned answers: loading, content, the main taps and where they lead. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h3000dp")
class ScreenFlowsTest {
    @get:Rule
    val compose = createComposeRule()

    private val navigator = Navigator()
    private val models = ViewModelKeeper()

    @After
    fun clear() {
        models.clear()
        NetworkSignals.confirmation.value = null
    }

    private var screen by mutableStateOf<@Composable () -> Unit>({})

    private fun show(chrome: ScreenChrome = ScreenChrome(onBack = { navigator.back() }), content: @Composable () -> Unit) {
        screen = content
        compose.setContent {
            OasisTheme {
                CompositionLocalProvider(LocalNavigator provides navigator, LocalScreenChrome provides chrome) { screen() }
            }
        }
    }

    /** Shows the next screen of a test (a compose rule takes its content only once). */
    private fun switchTo(content: @Composable () -> Unit) {
        screen = content
        compose.waitForIdle()
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/network-screen-$name.png")

    /** Waits until the text is on screen (answers arrive on OkHttp's threads). */
    private fun text(text: String, substring: Boolean = false): SemanticsNodeInteraction {
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNodeWithText(text, substring = substring)
    }

    private val summary =
        """{"id": 2, "slug": "stadtgarten", "name": "Stadtgarten", "description": "Gärtnern.", "color": "spring", "visibility": "public",
        |"banner_url": null, "members_count": 4, "role": "host"}
        """.trimMargin()

    @Test
    fun `the Quelle with its floating button`() {
        val network = StubbedNetwork(
            stub(
                "GET",
                "network/source",
                pageJson(
                    postJson(3),
                    context = """{"show_all":false,"follows_anyone":false,"can_post":true,"week":{"followers":1,"reactions":2,"karawane":3},
                    |"oases":[],"open_vote":{"id":1,"title":"Neue Regeln","ends_at":"2026-11-03T12:00:00Z"}}
                    """.trimMargin()
                )
            )
        )
        val model = models.keep(SourceViewModel(network.repository, network.session))
        show(ScreenChrome()) { SourceScreen(model) }
        text("Nächste Schritte").assertIsDisplayed()
        text("Neue Regeln").assertIsDisplayed()
        capture("source")
        compose.onNodeWithTag("source.compose").performClick()
        assertEquals(NetworkRoute.Compose(), navigator.sheet)
    }

    @Test
    fun `a read-only Quelle offers the membership instead of writing`() {
        val network = StubbedNetwork(
            stub("GET", "network/source", pageJson(context = """{"show_all":true,"follows_anyone":true,"can_post":false}"""))
        )
        val model = models.keep(SourceViewModel(network.repository, network.session))
        show(ScreenChrome()) { SourceScreen(model) }
        text("Dein Konto ist gerade nur zum Lesen.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("source.compose").assertDoesNotExist()
        text("Noch hat niemand etwas geteilt. Schreib den ersten Tropfen!").assertIsDisplayed()
    }

    @Test
    fun `Entdecken, Gemerkt and a topic`() {
        val network = StubbedNetwork(
            stub("GET", "network/discover", Fixtures.discover),
            stub("GET", "network/bookmarks", pageJson()),
            stub("GET", "network/tags/garten", pageJson(context = """{"tag":"garten","can_post":true}""")),
            stub("GET", "network/tags/stille", pageJson(context = """{"tag":"stille","can_post":false}"""))
        )
        val model1 = models.keep(DiscoverViewModel(network.repository))
        show { DiscoverScreen(model1) }
        text("Wer schon da ist").assertIsDisplayed()

        val bookmarks = StubbedNetwork(stub("GET", "network/bookmarks", pageJson()))
        val model2 = models.keep(BookmarksViewModel(bookmarks.repository))
        switchTo { BookmarksScreen(model2) }
        text("Du hast dir noch keine Tropfen gemerkt.", substring = true).assertIsDisplayed()
        text("Konten verwalten").performClick()
        capture("bookmarks")
    }

    @Test
    fun `an empty topic asks for the first Tropfen or another search`() {
        val network = StubbedNetwork(
            stub("GET", "network/tags/garten", pageJson(context = """{"tag":"garten","can_post":true}""")),
            stub("GET", "network/tags/stille", pageJson(context = """{"tag":"stille","can_post":false}"""))
        )
        val model3 = models.keep(TagViewModel("garten", network.repository))
        show { TagScreen("Garten", model3) }
        text("Den ersten Tropfen schreiben").performClick()
        assertEquals(NetworkRoute.Compose(), navigator.sheet)
        capture("tag")
    }

    @Test
    fun `a topic nobody may write in offers another search`() {
        val network = StubbedNetwork(stub("GET", "network/tags/stille", pageJson(context = """{"tag":"stille","can_post":false}""")))
        val model4 = models.keep(TagViewModel("stille", network.repository))
        show { TagScreen("stille", model4) }
        text("Etwas anderes suchen").performClick()
        assertEquals(NetworkRoute.Search(), navigator.stack(MainTab.Source).last())
    }

    @Test
    fun `the search updates while typing`() {
        val network = StubbedNetwork(stub("GET", "network/search", Fixtures.search))
        val model5 = models.keep(SearchViewModel("", network.repository))
        show { SearchScreen("", model5) }
        text("Wonach suchst du?").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("ga")
        text("Ergebnisse für „ga“").assertIsDisplayed()
        capture("search")
    }

    @Test
    fun `Durchblättern loads its deck`() {
        val network = StubbedNetwork(stub("GET", "network/swipe", Fixtures.swipe))
        val model6 = models.keep(SwipeViewModel(network.repository))
        show { SwipeScreen(model6) }
        text("Was es Neues gibt").assertIsDisplayed()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Wischen oder tippen")).fetchSemanticsNodes().isNotEmpty() }
        capture("swipe")
    }

    @Test
    fun `the oases overview and an oasis with its floating button`() {
        val network = StubbedNetwork(
            stub("GET", "network/oases", Fixtures.oases),
            stub(
                "GET",
                "network/oases/stadtgarten",
                """{"data": {"oasis": $summary, "url": "http://localhost/oasen/stadtgarten", "house_rules": [], "hosts": [],
                |"is_member": true, "is_host": true, "can_follow": false, "can_post": true}}
                """.trimMargin()
            ),
            stub("GET", "network/oases/stadtgarten/posts", pageJson(postJson(4))),
            stub("GET", "network/oases/stadtgarten/members", """{"data":{"members":[],"hidden_members":0,"own_list_audience":"nobody"}}""")
        )
        val model7 = models.keep(OasesViewModel(network.repository))
        show { OasesScreen(model7) }
        text("Räume für das, was dich interessiert").assertIsDisplayed()

        val model8 = models.keep(OasisViewModel("stadtgarten", network.repository))
        switchTo { OasisDetailScreen("stadtgarten", model8) }
        text("Du bist in Mitgliederlisten für niemanden sichtbar.").assertIsDisplayed()
        capture("oasis")
        compose.onNodeWithContentDescription("Tropfen in Stadtgarten schreiben").performClick()
        assertEquals(NetworkRoute.Compose(oasisSlug = "stadtgarten"), navigator.sheet)
        navigator.dismissSheet()
        text("Gefolgt ✓").performClick()
        text("Der Oase „Stadtgarten“ nicht mehr folgen?").assertIsDisplayed()
        compose.onNodeWithTag("dialog.cancel").performClick()
    }

    @Test
    fun `founding an oasis and editing one`() {
        val editForm =
            """{"data": {"oasis": $summary, "house_rules": "Freundlich bleiben.", "members": [{"id": 3, "username": "aylin",
            |"display_name": "Aylin Demir", "role": "member", "role_label": "Mitglied"}], "options": {"visibilities":
            |[{"value": "public", "label": "Alle"}], "colors": [{"value": "spring", "label": "Quelle"}, {"value": "sun", "label": "Sonne"}]}}}
            """.trimMargin()
        val network = StubbedNetwork(stub("GET", "network/oases", Fixtures.oases), stub("GET", "network/oases/stadtgarten/edit", editForm))
        val model9 = models.keep(OasisFormViewModel(null, network.repository))
        show { OasisFormScreen(null, model9) }
        text("Du wirst Gastgeber:in", substring = true).assertIsDisplayed()
        capture("oasis-create")

        val model10 = models.keep(OasisFormViewModel("stadtgarten", network.repository))
        switchTo { OasisFormScreen("stadtgarten", model10) }
        text("Stadtgarten bearbeiten").assertIsDisplayed()
        text("Zu Gastgeber:in machen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Sonne").performClick()
        compose.onNodeWithTag("oasisForm.close").performClick()
        assertEquals(NetworkRoute.CloseOasis("stadtgarten"), navigator.stack(MainTab.Source).last())
        capture("oasis-edit")
    }

    @Test
    fun `closing an oasis and its statistics`() {
        val network = StubbedNetwork(
            stub("GET", "network/oases/stadtgarten/close", """{"data":{"name":"Stadtgarten","members_count":1,"posts_count":9}}"""),
            stub(
                "GET",
                "network/oases/stadtgarten/statistics",
                """{"data":{"name":"Stadtgarten","totals":{"members":4},"weekly":[{"week":"2026-10-19T00:00:00.000000Z","joined":1,"posts":0}]}}"""
            )
        )
        val model11 = models.keep(CloseOasisViewModel("stadtgarten", network.repository))
        show { CloseOasisScreen("stadtgarten", model11) }
        text("„Stadtgarten“ schließen?").assertIsDisplayed()
        text("Die Oase verschwindet für alle 1 Mitglied.").assertIsDisplayed()
        compose.onNodeWithTag("closeOasis.confirm").performClick()
        capture("oasis-close")

        val model12 = models.keep(OasisStatisticsViewModel("stadtgarten", network.repository))
        switchTo { OasisStatisticsScreen("stadtgarten", model12) }
        text("Statistik: Stadtgarten").assertIsDisplayed()
        capture("oasis-statistics")
    }

    @Test
    fun `the Tropfen page, editing and carrying`() {
        val network = StubbedNetwork(
            stub("GET", "network/posts/12", Fixtures.post),
            stub("GET", "network/composer", Fixtures.composer),
            stub("GET", "network/posts/12/edit", """{"data":{"body":"Hallo","until":"2026-10-24T12:00:00.000000Z"}}"""),
            stub(
                "GET",
                "network/posts/12/carry",
                """{"data":{"id":12,"author":{"id":4,"username":"lukas","display_name":"Lukas Brandt","initials":"LB","avatar_color":"dune",
                |"photo_url":null},"body":"Samstag Werkstatt.","segments":[{"type":"text","text":"Samstag Werkstatt."}],"links":[]}}
                """.trimMargin()
            )
        )
        val reply = models.keep(ComposeViewModel(null, 12, null, network.repository, network.common, network.session))
        val model13 = models.keep(PostDetailViewModel(12, network.repository))
        show { PostDetailScreen(12, model13, reply) }
        text("Geschrieben von").assertIsDisplayed()
        capture("post")

        val edit = models.keep(EditPostViewModel(12, network.repository))
        switchTo { EditPostScreen(12, edit) }
        text("Tropfen bearbeiten").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput(" Welt")
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Zurück").performClick()
        text("Änderung verwerfen?").assertIsDisplayed()
        capture("post-edit-discard")
        compose.onNodeWithTag("dialog.cancel").performClick()

        val carry = models.keep(ComposeViewModel(null, null, 12, network.repository, network.common, network.session))
        val model14 = models.keep(CarryViewModel(12, network.repository))
        switchTo { CarryScreen(12, model14, carry) }
        text("Samstag Werkstatt.").assertIsDisplayed()
        capture("post-carry")
    }

    @Test
    fun `the composer sheet asks before a draft is lost`() {
        val network = StubbedNetwork(stub("GET", "network/composer", Fixtures.composer))
        val model = models.keep(ComposeViewModel("sheet-test", null, null, network.repository, network.common, network.session))
        navigator.present(NetworkRoute.Compose(oasisSlug = "sheet-test"))
        show(ScreenChrome(onClose = { navigator.dismissSheet() })) { ComposeScreen("sheet-test", null, model) }
        text("Tropfen schreiben").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("Entwurf")
        compose.waitForIdle()
        compose.onNodeWithTag("compose.close").performClick()
        text("Entwurf verwerfen?").assertIsDisplayed()
        capture("compose-discard")
        compose.onNodeWithTag("dialog.confirm").performClick()
        compose.waitForIdle()
        assertTrue(model.state.value.body.isEmpty())
        assertEquals(null, navigator.sheet)
    }

    @Test
    fun `a profile and the own profile form`() {
        val network = StubbedNetwork(
            stub("GET", "network/profiles/jonas", Fixtures.profile),
            stub("GET", "network/profiles/jonas/posts", pageJson(postJson(5))),
            stub("GET", "network/profile", Fixtures.ownProfile),
            stub("POST", "network/tone", """{"data":{"hint":null,"nudge":false}}""")
        )
        val model15 = models.keep(ProfileViewModel("jonas", network.repository))
        show { ProfileScreen("jonas", model15) }
        text("Karawane: 3").assertIsDisplayed()
        capture("profile")
        compose.onNodeWithTag("profile.unfollow").performClick()
        text("Jonas Weber nicht mehr folgen?").assertIsDisplayed()
        compose.onNodeWithTag("dialog.cancel").performClick()

        val model16 = models.keep(EditProfileViewModel(network.repository, network.common))
        switchTo { EditProfileScreen(model16) }
        text("So sehen dich andere: dein Profil ansehen.").assertIsDisplayed()
        text("Farbe deiner Initialen").assertIsDisplayed()
        capture("profile-edit")
    }

    @Test
    fun `friendships, the own numbers and invitations`() {
        val person = """{"id": 3, "username": "aylin", "display_name": "Aylin Demir", "initials": "AD", "avatar_color": "night"}"""
        val network = StubbedNetwork(
            stub(
                "GET",
                "network/friends",
                """{"data":{"incoming":[$person],"friends":[$person],"outgoing":[$person],"accepts_requests":false}}"""
            ),
            stub("GET", "network/statistics", Fixtures.statistics),
            stub(
                "GET",
                "network/invitations",
                """{"data":{"invitations":[{"id":1,"url":"http://localhost/einladung/abc","expires_at":"2026-11-20T12:00:00Z"},
                |{"id":2,"expires_at":"2026-10-01T12:00:00Z","expired":true},{"id":3,"expires_at":"2026-10-01T12:00:00Z",
                |"accepted_at":"2026-09-20T12:00:00Z","reward":{"applied_at":null}},{"id":4,"expires_at":"2026-10-01T12:00:00Z",
                |"accepted_at":"2026-09-20T12:00:00Z","reward":{"applied_at":"2026-10-20T12:00:00Z"}}],"remaining":1,"per_month":3,
                |"valid_days":30,"free_months_this_year":2,"free_months_per_year":12,"free_months_waiting":1,"trial_days":30,
                |"can_create":true}}
                """.trimMargin()
            ),
            stub("GET", "network/invitations/abc", """{"data":{"inviter":$person}}"""),
            stub("GET", "network/invitations/alt", """{"data":{"inviter":null}}""")
        )
        val model17 = models.keep(FriendsViewModel(network.repository))
        show { FriendsScreen(model17) }
        text("Du nimmst gerade keine Freundschaftsanfragen an. Wieder einschalten").assertIsDisplayed()
        text("Ablehnen").performClick()
        text("Anfrage von Aylin Demir ablehnen?").assertIsDisplayed()
        compose.onNodeWithTag("dialog.cancel").performClick()
        text("Freundschaft beenden").performClick()
        text("Freundschaft mit Aylin Demir beenden?").assertIsDisplayed()
        compose.onNodeWithTag("dialog.cancel").performClick()
        capture("friends")

        val model18 = models.keep(StatisticsViewModel(network.repository))
        switchTo { StatisticsScreen(model18) }
        text("Deine Zahlen").assertIsDisplayed()
        capture("statistics")

        val model19 = models.keep(InvitationsViewModel(network.repository))
        switchTo { InvitationsScreen(model19) }
        text("Link kopieren").performClick()
        text("Kopiert").assertIsDisplayed()
        text("Abgelaufen").assertIsDisplayed()
        text("Freimonat verdient – wir rechnen ihn auf deine laufende Mitgliedschaft an.").assertIsDisplayed()
        text("Freimonat gutgeschrieben am", substring = true).assertIsDisplayed()
        text("1 wartet auf eine laufende Mitgliedschaft.").assertIsDisplayed()
        capture("invitations")

        val model20 = models.keep(InvitationViewModel("abc", network.repository))
        switchTo { InvitationScreen("abc", model20) }
        text("Aylin Demir lädt dich zu Oasis ein.").assertIsDisplayed()
        text("Zum Profil von Aylin Demir").performClick()
        assertEquals(NetworkRoute.Profile("aylin"), navigator.stack(MainTab.Source).last())
        capture("invitation")
    }

    @Test
    fun `an invitation link that no longer works`() {
        val network = StubbedNetwork(stub("GET", "network/invitations/alt", """{"data":{"inviter":null}}"""))
        val model21 = models.keep(InvitationViewModel("alt", network.repository))
        show { InvitationScreen("alt", model21) }
        text("Dieser Einladungslink gilt nicht mehr.").assertIsDisplayed()
    }
}
