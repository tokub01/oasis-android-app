package de.velwebsolutions.oasis.network

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ApplicationProvider
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.model.Me
import de.velwebsolutions.oasis.core.network.DataEnvelope
import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.design.components.LocalApiClient
import de.velwebsolutions.oasis.design.components.LocalScreenChrome
import de.velwebsolutions.oasis.design.components.ScreenChrome
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.network.api.ComposerContext
import de.velwebsolutions.oasis.network.api.DiscoverContext
import de.velwebsolutions.oasis.network.api.FeedPage
import de.velwebsolutions.oasis.network.api.NoContext
import de.velwebsolutions.oasis.network.api.OasisSummary
import de.velwebsolutions.oasis.network.api.Post
import de.velwebsolutions.oasis.network.api.SearchContext
import de.velwebsolutions.oasis.network.api.SourceContext
import de.velwebsolutions.oasis.network.api.SwipeDeck
import de.velwebsolutions.oasis.network.api.TagContext
import de.velwebsolutions.oasis.network.api.TextSegment
import de.velwebsolutions.oasis.network.ui.PostAction
import de.velwebsolutions.oasis.network.ui.PostListState
import de.velwebsolutions.oasis.network.ui.components.ComposeFab
import de.velwebsolutions.oasis.network.ui.components.FeedHandlers
import de.velwebsolutions.oasis.network.ui.components.ImageViewer
import de.velwebsolutions.oasis.network.ui.components.NetworkPage
import de.velwebsolutions.oasis.network.ui.components.OasisMark
import de.velwebsolutions.oasis.network.ui.components.PostCard
import de.velwebsolutions.oasis.network.ui.components.block
import de.velwebsolutions.oasis.network.ui.components.copyToClipboard
import de.velwebsolutions.oasis.network.ui.components.feedSection
import de.velwebsolutions.oasis.network.ui.compose.ComposeUiState
import de.velwebsolutions.oasis.network.ui.compose.ComposeViewModel
import de.velwebsolutions.oasis.network.ui.compose.ComposerCallbacks
import de.velwebsolutions.oasis.network.ui.compose.ComposerForm
import de.velwebsolutions.oasis.network.ui.compose.ToneHintNote
import de.velwebsolutions.oasis.network.ui.feeds.SourceScreen
import de.velwebsolutions.oasis.network.ui.feeds.SourceViewModel
import de.velwebsolutions.oasis.network.ui.feeds.SwipeContent
import de.velwebsolutions.oasis.network.ui.feeds.TagScreen
import de.velwebsolutions.oasis.network.ui.feeds.TagViewModel
import de.velwebsolutions.oasis.network.ui.feeds.discoverContent
import de.velwebsolutions.oasis.network.ui.feeds.searchContent
import de.velwebsolutions.oasis.network.ui.feeds.sourceContent
import de.velwebsolutions.oasis.network.ui.oases.CloseOasisScreen
import de.velwebsolutions.oasis.network.ui.oases.CloseOasisViewModel
import de.velwebsolutions.oasis.network.ui.oases.OasisBanner
import de.velwebsolutions.oasis.network.ui.oases.OasisDetailScreen
import de.velwebsolutions.oasis.network.ui.oases.OasisFormScreen
import de.velwebsolutions.oasis.network.ui.oases.OasisFormViewModel
import de.velwebsolutions.oasis.network.ui.oases.OasisViewModel
import de.velwebsolutions.oasis.network.ui.oases.oasisContent
import de.velwebsolutions.oasis.network.ui.posts.CarryScreen
import de.velwebsolutions.oasis.network.ui.posts.CarryViewModel
import de.velwebsolutions.oasis.network.ui.posts.ComposeContent
import de.velwebsolutions.oasis.network.ui.posts.ComposeScreen
import de.velwebsolutions.oasis.network.ui.posts.EditPostScreen
import de.velwebsolutions.oasis.network.ui.posts.EditPostViewModel
import de.velwebsolutions.oasis.network.ui.posts.PostDetailScreen
import de.velwebsolutions.oasis.network.ui.posts.PostDetailViewModel
import de.velwebsolutions.oasis.network.ui.profiles.EditProfileScreen
import de.velwebsolutions.oasis.network.ui.profiles.EditProfileViewModel
import de.velwebsolutions.oasis.network.ui.profiles.FriendsScreen
import de.velwebsolutions.oasis.network.ui.profiles.FriendsViewModel
import de.velwebsolutions.oasis.network.ui.profiles.InvitationsScreen
import de.velwebsolutions.oasis.network.ui.profiles.InvitationsViewModel
import de.velwebsolutions.oasis.network.ui.profiles.ProfileScreen
import de.velwebsolutions.oasis.network.ui.profiles.ProfileViewModel
import de.velwebsolutions.oasis.network.ui.profiles.StatisticsScreen
import de.velwebsolutions.oasis.network.ui.profiles.StatisticsViewModel
import de.velwebsolutions.oasis.network.ui.profiles.profileContent
import java.time.Instant
import kotlinx.serialization.KSerializer
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
import org.robolectric.annotation.GraphicsMode

/** The less travelled paths on screen: menus, gestures, the welcome tour, empty and loading states, and where taps lead. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h3000dp")
class ScreenBranchesTest {
    @get:Rule
    val compose = createComposeRule()

    private val navigator = Navigator()
    private val models = ViewModelKeeper()
    private val actions = mutableListOf<Pair<PostAction, Int>>()
    private val handlers = FeedHandlers(perform = { action, post -> actions += action to post.id }, loadMore = {}, reload = {})
    private var screen by mutableStateOf<@Composable () -> Unit>({})
    private val client = StubbedNetwork().transport.client

    @After
    fun clear() {
        models.clear()
        NetworkSignals.confirmation.value = null
        NetworkSignals.replyFocus.value = null
    }

    private fun show(chrome: ScreenChrome = ScreenChrome(onBack = { navigator.back() }), content: @Composable () -> Unit) {
        screen = content
        compose.setContent {
            OasisTheme {
                CompositionLocalProvider(
                    LocalNavigator provides navigator,
                    LocalScreenChrome provides chrome,
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

    private fun waitFor(condition: () -> Boolean) = compose.settle(condition = condition)

    private fun closeToast() = compose.onAllNodesWithContentDescription("Schließen").onFirst().performClick()

    private fun <T> data(serializer: KSerializer<T>, json: String): T =
        OasisJson.snake.decodeFromString(DataEnvelope.serializer(serializer), json).data

    private val base = decodePost(postJson(1))
    private val viewer = base.viewer!!
    private val now = Instant.parse("2026-10-22T10:00:00Z")

    @Test
    fun `card menus for the host, the action row, semantics actions and the carried original`() {
        val hosted = base.copy(
            id = 30,
            images = listOf(Post.Image(1, "http://localhost/a.jpg", "Ein Beet", 1200, 800)),
            conversation = Post.Conversation(
                "Gespräch",
                hasMore = true,
                replies = listOf(Post.Conversation.Reply(31, base.author, "Ja", listOf(TextSegment(text = "Ja"))))
            ),
            carried = Post.Carried(
                Post.Carried.State.Visible,
                Post.Carried.Original(9, base.author, "Original", listOf(TextSegment(text = "Original")))
            ),
            viewer = viewer.copy(canRemoveFromOasis = true, reacted = true)
        )
        val gone = base.copy(id = 32, carried = Post.Carried(Post.Carried.State.Visible, null))
        show {
            NetworkPage { feedSection(PostListState<NoContext>(posts = listOf(hosted, gone), state = Loadable.Loaded(Unit)), handlers) }
        }

        compose.onAllNodesWithTag("post.menu").onFirst().performClick()
        text("Aus Oase entfernen …").performClick()
        text("Wirklich aus der Oase entfernen?")
        compose.onNodeWithTag("dialog.confirm").performClick()
        compose.onAllNodesWithTag("post.menu").onFirst().performClick()
        text("Stummschalten").performClick()
        compose.onAllNodesWithTag("post.menu").onFirst().performClick()
        text("Einschränken").performClick()

        compose.onNodeWithTag("post.card.30").performSemanticsAction(SemanticsActions.OnLongClick)
        text("Reaktion zurücknehmen").performClick()
        compose.onNodeWithTag("post.card.30").performSemanticsAction(SemanticsActions.OnLongClick)
        text("Merken").performClick()

        compose.onAllNodesWithTag("post.react").onFirst().performClick()
        compose.onAllNodesWithTag("post.bookmark").onFirst().performSemanticsAction(SemanticsActions.OnClick)
        compose.onAllNodesWithTag("post.action.Weitertragen").onFirst().performClick()
        assertEquals(NetworkRoute.CarryPost(30), navigator.stack(MainTab.Source).last())
        compose.onNodeWithContentDescription("Ein Beet").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("imageViewer.close").performClick()
        compose.onAllNodes(hasText("U 2 @u2", substring = true))[0].performClick()
        assertEquals(NetworkRoute.Post(9), navigator.stack(MainTab.Source).last())
        text("weitere Antworten").performClick()
        assertEquals(NetworkRoute.Post(30), navigator.stack(MainTab.Source).last())
        compose.onNodeWithTag("post.card.30").performSemanticsAction(SemanticsActions.OnLongClick)
        text("Teilen").performClick()
        assertEquals(
            listOf(
                PostAction.RemoveFromOasis to 30,
                PostAction.Mute to 30,
                PostAction.Restrict to 30,
                PostAction.Unreact to 30,
                PostAction.Bookmark to 30,
                PostAction.Unreact to 30,
                PostAction.Bookmark to 30
            ),
            actions
        )
    }

    @Test
    fun `on the Tropfen page Antworten focuses the reply field`() {
        show { NetworkPage { block { PostCard(base, onAction = { _, _ -> }, isDetail = true) } } }
        compose.onNodeWithTag("post.action.Antworten").performClick()
        assertEquals(1, NetworkSignals.replyFocus.value)
        assertTrue(navigator.stack(MainTab.Source).isEmpty())
    }

    @Test
    fun `the image viewer zooms with a double tap and two fingers and moves a zoomed image`() {
        val image = Post.Image(1, "http://localhost/a.jpg", "Ein Beet", 1200, 800)
        show { ImageViewer(listOf(image, image.copy(id = 2)), image) {} }
        val picture = compose.onAllNodesWithTag("imageViewer.image").onFirst()
        picture.performTouchInput { doubleClick() }
        picture.performTouchInput { swipe(center, center + Offset(40f, 40f)) }
        picture.performTouchInput { doubleClick() }
        picture.performTouchInput {
            pinch(
                center - Offset(10f, 0f),
                center - Offset(100f, 0f),
                center + Offset(10f, 0f),
                center + Offset(100f, 0f)
            )
        }
        picture.performTouchInput { swipe(center, center + Offset(30f, 0f)) }
        compose.waitForIdle()
    }

    @Test
    fun `pull to refresh, the shrinking floating button and copying without a clipboard`() {
        var refreshed = false
        var tapped = false
        show {
            Box(Modifier.fillMaxSize()) {
                val list = rememberLazyListState()
                NetworkPage(state = list, onRefresh = { refreshed = true }) {
                    repeat(40) { index -> block("b$index") { OasisBanner(OasisSummary(index, "o$index", "Oase $index")) } }
                }
                ComposeFab(list, onClick = { tapped = true }, Modifier.then(Modifier))
            }
        }
        compose.onNodeWithTag("network.page").performTouchInput { swipeDown() }
        waitFor { refreshed }
        compose.onNodeWithContentDescription("Tropfen schreiben").performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(tapped)
        compose.onNodeWithTag("network.page").performScrollToIndex(30)
        compose.waitForIdle()

        val app = ApplicationProvider.getApplicationContext<Context>()
        val noServices = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? = null
        }
        copyToClipboard(noServices, "Link", "http://localhost")
    }

    @Test
    fun `an oasis mark with a banner, the tone rules link and the oasis banner image`() {
        val withBanner = OasisSummary(1, "garten", "Garten", bannerUrl = "http://localhost/banner.webp")
        show {
            NetworkPage {
                block { OasisMark(withBanner) }
                block { OasisBanner(withBanner) }
                block { ToneHintNote("Idiot") }
            }
        }
        text("Regeln zum Umgangston", substring = true)
        compose.waitForIdle()
    }

    @Test
    fun `the composer ignores a second tap while it publishes and shows the link error`() {
        val composer = data(ComposerContext.serializer(), Fixtures.composer).copy(
            links = listOf(de.velwebsolutions.oasis.network.api.ExternalLink("https://example.org", "example.org"))
        )
        var publishes = 0
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val callbacks = ComposerCallbacks(
            onBody = {}, onVisibility = {}, onKind = {}, onLinkRead = {}, onToneConfirmed = {}, onAddImage = {}, onRemoveImage = {},
            onAlt = { _, _ -> }, checkTone = {},
            publish = {
                publishes++
                gate.await()
                null
            }
        )
        val failed = de.velwebsolutions.oasis.core.network.ApiError.Validation(
            "Bitte bestätige.",
            mapOf("link_read" to listOf("Bitte bestätige."))
        )
        show { ComposeContent(ComposeUiState(context = Loadable.Loaded(composer), error = failed), callbacks, onPublished = {}) }
        text("Bitte bestätige.")
        compose.onNodeWithTag("compose.submit").performClick()
        compose.onNodeWithTag("compose.submit").performClick()
        gate.complete(Unit)
        compose.waitForIdle()
        assertEquals(1, publishes)
    }

    @Test
    fun `Entdecken without oases, a topic with Tropfen and the end of short lists`() {
        val discover = OasisJson.snake.decodeFromString(FeedPage.serializer(DiscoverContext.serializer()), Fixtures.discover)
        var state by mutableStateOf(
            PostListState(posts = discover.data, context = DiscoverContext(), state = Loadable.Loaded(Unit), nextCursor = "x")
        )
        show { NetworkPage { discoverContent(state, handlers) } }
        text("Wer schon da ist")
        state = state.copy(context = null, nextCursor = null)
        compose.waitForIdle()
        state = state.copy(context = DiscoverContext(oases = emptyList()), posts = emptyList())
        compose.waitForIdle()

        val network =
            StubbedNetwork(stub("GET", "network/tags/garten", pageJson(postJson(5), context = """{"tag":"garten","can_post":true}""")))
        val tag = models.keep(TagViewModel("garten", network.repository))
        switchTo { TagScreen("garten", tag) }
        text("Tropfen 5")
    }

    @Test
    fun `the search submits a topic, shows its progress and the results' parts`() {
        var submitted = 0
        var state by mutableStateOf(PostListState<SearchContext>(state = Loadable.Loaded(Unit), isReloading = true))
        var query by mutableStateOf("ga")
        show {
            NetworkPage { searchContent(query, state, handlers, onQuery = {}, onSubmit = { submitted++ }) }
        }
        compose.onNodeWithContentDescription("Sucht …").assertExists()
        compose.onNode(hasSetTextAction()).performImeAction()
        state = PostListState(context = SearchContext(searching = false, topic = "garten"), state = Loadable.Loaded(Unit))
        query = "g"
        compose.waitForIdle()
        state = PostListState(context = SearchContext(searching = true, people = null, oases = null), state = Loadable.Loaded(Unit))
        query = "garten"
        compose.waitForIdle()
        text("Niemand gefunden.")
        assertEquals(1, submitted)
    }

    @Test
    fun `the search screen opens a topic from the keyboard`() {
        val network = StubbedNetwork(
            stub(
                "GET",
                "network/search",
                """{"data":[],"meta":{"next_cursor":null},"context":{"term":"#garten","searching":true,"topic":"garten"}}"""
            )
        )
        val model = models.keep(de.velwebsolutions.oasis.network.ui.feeds.SearchViewModel("#garten", network.repository))
        show { de.velwebsolutions.oasis.network.ui.feeds.SearchScreen("#garten", model) }
        compose.settle { compose.onAllNodes(hasTestTag("search.topic")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performImeAction()
        assertEquals(NetworkRoute.Tag("garten"), navigator.stack(MainTab.Source).last())
        model.updateQuery("")
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performImeAction()
    }

    @Test
    fun `the Quelle sends a new member to the welcome tour once and scrolls up for a new Tropfen`() {
        val network = StubbedNetwork(
            stub("GET", "network/source", pageJson(postJson(3), context = """{"show_all":false,"follows_anyone":true,"can_post":true}"""))
        )
        network.session.update(Me(7, "mira", "Mira Sol", "MS", "mira@example.org", onboarded = false))
        val model = models.keep(SourceViewModel(network.repository, network.session))
        show(ScreenChrome()) { SourceScreen(model) }
        text("Hallo Mira Sol.")
        assertEquals(AccountRoute.Welcome, navigator.sheet)
        assertTrue(model.askedWelcome)
        NetworkSignals.didPublish(decodePost(postJson(9)), "Dein Tropfen ist da.")
        text("Tropfen 9")
        compose.onNodeWithContentDescription("Dein Tropfen: Was möchtest du teilen?").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Gefolgt").performClick()
    }

    @Test
    fun `the Quelle's page before its context and when it follows somebody`() {
        var state by mutableStateOf(PostListState<SourceContext>(state = Loadable.Loaded(Unit)))
        show { NetworkPage { sourceContent(state, "Mira", handlers, onSelectView = {}) } }
        text("Hallo Mira.")
        state =
            PostListState(
                context = SourceContext(followsAnyone = true),
                posts = listOf(base),
                state = Loadable.Loaded(Unit),
                nextCursor = "x"
            )
        compose.waitForIdle()
    }

    @Test
    fun `the end of Durchblättern lists what waits, one and many`() {
        val vote = SwipeDeck.Vote(1, "Regeln")
        var deck by mutableStateOf(SwipeDeck(unreadChats = 1, friendRequests = 1, unreadHints = 1, openVote = vote))
        show { SwipeContent(deck) { _, _ -> } }
        text("1 Chat mit neuen Nachrichten")
        text("1 Freundschaftsanfrage")
        text("1 neuer Hinweis")
        deck = SwipeDeck(
            chapters = listOf(
                SwipeDeck.Chapter("freunde", "Freunde", "Weil ihr befreundet seid.", listOf(base)),
                SwipeDeck.Chapter("neu", "Neu", posts = listOf(base.copy(id = 2)))
            ),
            unreadChats = 2,
            friendRequests = 3,
            unreadHints = 4
        )
        compose.waitForIdle()
        compose.onNodeWithTag("swipe.next").performClick()
        compose.onNodeWithTag("swipe.next").performClick()
        text("2 Chats mit neuen Nachrichten")
        text("3 Freundschaftsanfragen")
        text("4 neue Hinweise").performClick()
    }

    private val oasisJson =
        """{"data": {"oasis": {"id": 2, "slug": "garten", "name": "Garten", "description": "Gärtnern.", "color": "spring",
        |"visibility": "public", "banner_url": null, "members_count": 4, "role": "member"}, "url": "http://localhost/oasen/garten",
        |"house_rules": ["Eine"], "hosts": [], "is_member": MEMBER, "is_host": false, "can_follow": true, "can_post": MEMBER}}
        """.trimMargin()

    @Test
    fun `an oasis filters its members, writes from its page, follows and closes its rules again`() {
        val members = """{"data":{"members":[],"hidden_members":3,"own_list_audience":null}}"""
        val network = StubbedNetwork(
            stub("GET", "network/oases/garten", oasisJson.replace("MEMBER", "true")),
            stub("GET", "network/oases/garten/posts", pageJson()),
            stub("GET", "network/oases/garten/members", members),
            stub("POST", "network/oases/garten/follow", """{"message":"Du folgst."}""")
        )
        val model = models.keep(OasisViewModel("garten", network.repository))
        show { OasisDetailScreen("garten", model) }
        text("3 weitere Mitglieder zeigen sich", substring = true)
        compose.onNode(hasSetTextAction()).performTextInput("ay")
        compose.mainClock.advanceTimeBy(400)
        waitFor { network.transport.requests.count { it.path == "network/oases/garten/members" } >= 2 }
        text("Niemand mit diesem Namen.")
        compose.onNodeWithContentDescription("Dein Tropfen in Garten").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(NetworkRoute.Compose(oasisSlug = "garten"), navigator.sheet)
        navigator.dismissSheet()
        compose.onNodeWithTag("oasis.rules").performClick()
        compose.onNodeWithTag("oasis.rules").performClick()

        network.add("GET", "network/oases/garten", json = oasisJson.replace("MEMBER", "false"))
        model.launch { model.load() }
        compose.settle { compose.onAllNodes(hasTestTag("oasis.follow")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("oasis.follow").performClick()
        text("Du folgst.")
        closeToast()
        network.add("POST", "network/oases/garten/follow", 422, """{"message":"Geht nicht."}""")
        compose.onNodeWithTag("oasis.follow").performClick()
        text("Geht nicht.")
        closeToast()
        compose.waitForIdle()
    }

    @Test
    fun `the oasis form saves, founds, chooses an audience and closes`() {
        val summary = """{"id": 2, "slug": "garten", "name": "Garten", "description": "x", "color": "spring", "visibility": "public",
            |"banner_url": "http://localhost/b.webp", "members_count": 4, "role": "host"}
        """.trimMargin()
        val edit = """{"data":{"oasis":$summary,"house_rules":"","members":[{"id":3,"username":"aylin","display_name":"Aylin",
            |"role":"host","role_label":"Gastgeber:in"}],"options":{"visibilities":[{"value":"public","label":"Alle"},
            |{"value":"members","label":"Mitglieder"}],"colors":[]}}}
        """.trimMargin()
        val network = StubbedNetwork(
            stub("GET", "network/oases/garten/edit", edit),
            stub("PUT", "network/oases/garten", """{"data":{"slug":"garten"}}"""),
            stub("PUT", "network/oases/garten/members/3/role", """{"message":"Mitglied.","data":{"still_host":true}}"""),
            stub("GET", "network/oases", Fixtures.oases),
            stub("POST", "network/oases", """{"data":{"slug":"neu"}}""", 201),
            stub("GET", "network/oases/garten/close", """{"data":{"name":"Garten","members_count":4,"posts_count":9}}"""),
            stub("DELETE", "network/oases/garten", """{"message":"Geschlossen."}""")
        )
        navigator.open(NetworkRoute.Oases)
        navigator.open(NetworkRoute.EditOasis("garten"))
        val editing = models.keep(OasisFormViewModel("garten", network.repository))
        show { OasisFormScreen("garten", editing) }
        text("Zu Mitglied machen").performClick()
        text("Mitglied.")
        closeToast()
        text("Mitglieder").performClick()
        compose.onNodeWithTag("oasisForm.save").performClick()
        waitFor { navigator.stack(MainTab.Source).last() == NetworkRoute.Oases }

        navigator.open(NetworkRoute.CreateOasis)
        val founding = models.keep(OasisFormViewModel(null, network.repository))
        switchTo { OasisFormScreen(null, founding) }
        text("Du wirst Gastgeber:in", substring = true)
        compose.onNodeWithTag("oasisForm.save").performClick()
        waitFor { navigator.stack(MainTab.Source).last() == NetworkRoute.Oasis("neu") }

        val closing = models.keep(CloseOasisViewModel("garten", network.repository))
        switchTo { CloseOasisScreen("garten", closing) }
        compose.settle { compose.onAllNodes(hasTestTag("closeOasis.confirm")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("closeOasis.confirm").performClick()
        compose.onNodeWithTag("closeOasis.submit").performClick()
        waitFor { navigator.selectedTab == MainTab.Oases }
    }

    @Test
    fun `the composer outside a sheet, carrying and editing lead back`() {
        val network = StubbedNetwork(
            stub("GET", "network/composer", Fixtures.composer),
            stub("POST", "network/posts", """{"message":"Da.","suspended":false,"data":${postJson(60)}}""", 201),
            stub("GET", "network/posts/12/carry", """{"message":"Kaputt."}""", 500),
            stub("GET", "network/posts/12/edit", """{"data":{"body":"Hallo","until":"2026-10-24T12:00:00.000000Z"}}"""),
            stub("PUT", "network/posts/12", """{"message":"Gespeichert."}""")
        )
        navigator.open(NetworkRoute.Discover)
        navigator.open(NetworkRoute.Compose())
        val composer = models.keep(ComposeViewModel("ohne-sheet", null, null, network.repository, network.common, network.session))
        show { ComposeScreen("ohne-sheet", null, composer) }
        text("Tropfen teilen")
        compose.onNode(hasSetTextAction()).performTextInput("Hallo")
        compose.onNodeWithTag("compose.submit").performClick()
        waitFor { navigator.stack(MainTab.Source).last() == NetworkRoute.Discover }

        val carrying = models.keep(ComposeViewModel(null, null, 12, network.repository, network.common, network.session))
        val source = models.keep(CarryViewModel(12, network.repository))
        switchTo { CarryScreen(12, source, carrying) }
        text("Noch einmal versuchen")
        network.add(
            "GET",
            "network/posts/12/carry",
            json = """{"data":{"id":12,"author":{"id":4,"username":"lukas","display_name":"Lukas","initials":"L"},"body":"Werkstatt",
                |"segments":[{"type":"text","text":"Werkstatt"}]}}
            """.trimMargin()
        )
        text("Noch einmal versuchen").performClick()
        text("Werkstatt")
        compose.onNode(hasSetTextAction()).performTextInput("Wichtig")
        compose.onNodeWithTag("compose.submit").performClick()
        waitFor { navigator.stack(MainTab.Source).isEmpty() }

        navigator.open(NetworkRoute.Discover)
        navigator.open(NetworkRoute.EditPost(12))
        val editing = models.keep(EditPostViewModel(12, network.repository))
        switchTo { EditPostScreen(12, editing) }
        text("Abbrechen").performClick()
        assertEquals(NetworkRoute.Discover, navigator.stack(MainTab.Source).last())
        navigator.open(NetworkRoute.EditPost(12))
        val again = models.keep(EditPostViewModel(12, network.repository))
        switchTo { EditPostScreen(12, again) }
        text("Tropfen bearbeiten")
        compose.onNode(hasSetTextAction()).performTextInput(" Welt")
        text("Abbrechen").performClick()
        text("Verwerfen").performClick()
        assertEquals(NetworkRoute.Discover, navigator.stack(MainTab.Source).last())
        navigator.open(NetworkRoute.EditPost(12))
        val saving = models.keep(EditPostViewModel(12, network.repository))
        switchTo { EditPostScreen(12, saving) }
        compose.settle { compose.onAllNodes(hasTestTag("edit.save")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("edit.save").performClick()
        waitFor { navigator.stack(MainTab.Source).last() == NetworkRoute.Discover }
    }

    @Test
    fun `the composer sheet closes without a draft`() {
        val network = StubbedNetwork(stub("GET", "network/composer", Fixtures.composer))
        navigator.present(NetworkRoute.Compose())
        val composer = models.keep(ComposeViewModel("leer-sheet", null, null, network.repository, network.common, network.session))
        show(ScreenChrome(onClose = { navigator.dismissSheet() })) { ComposeScreen("leer-sheet", null, composer) }
        text("Tropfen teilen")
        compose.onNodeWithTag("compose.close").performClick()
        assertNull(navigator.sheet)
    }

    @Test
    fun `the Tropfen page leaves after deleting, shares, and shows its parts for every viewer`() {
        val detail = """{"data":{"post":${postJson(12, author = 4).replace("\"edited_at\":null", "\"edited_at\":\"2026-10-22T11:00:00Z\"")
            .replace("\"can_delete\":false", "\"can_delete\":true").replace("\"can_write_reply\":true", "\"can_write_reply\":false")
            .replace("\"can_reply\":true", "\"can_reply\":false")},"revisions":[{"id":1,"body":"Alt","replaced_at":"2026-10-22T11:00:00Z"}],
            |"replies":[]}}
        """.trimMargin()
        val network = StubbedNetwork(
            stub("GET", "network/posts/12", detail),
            stub("DELETE", "network/posts/12", """{"message":"Gelöscht."}""")
        )
        navigator.open(NetworkRoute.Discover)
        navigator.open(NetworkRoute.Post(12))
        NetworkSignals.replyFocus.value = 99
        val model = models.keep(PostDetailViewModel(12, network.repository))
        val reply = models.keep(ComposeViewModel(null, 12, null, network.repository, network.common, network.session))
        show { PostDetailScreen(12, model, reply) }
        text("Frühere Fassungen (1)").performClick()
        text("Frühere Fassungen (1)").performClick()
        compose.onNodeWithTag("post.menu").performClick()
        text("Löschen …").performClick()
        compose.onNodeWithTag("dialog.confirm").performClick()
        waitFor { navigator.stack(MainTab.Source).last() == NetworkRoute.Discover }
        assertEquals(99, NetworkSignals.replyFocus.value)
        // Sharing opens the system sheet, which pauses the activity: the last step of the test.
        compose.onNodeWithContentDescription("Teilen").performClick()
    }

    @Test
    fun `the own profile form asks about the tone and removes photo and banner`() {
        val own = Fixtures.ownProfile.replace("\"has_photo\": false", "\"has_photo\": true")
            .replace("\"banner_url\": null", "\"banner_url\": \"http://localhost/banner.webp\"")
        val saved = own.replaceFirst("{\"data\"", "{\"message\":\"Gespeichert.\",\"data\"")
        val network = StubbedNetwork(
            stub("GET", "network/profile", own),
            stub("POST", "network/tone", """{"data":{"hint":"Idiot","nudge":true}}"""),
            stub("DELETE", "network/profile/photo", saved),
            stub("DELETE", "network/profile/banner", saved)
        )
        val model = models.keep(EditProfileViewModel(network.repository, network.common))
        show { EditProfileScreen(model) }
        text("Farbe deiner Initialen")
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("profileEdit.bio"))).performTextInput(" !")
        compose.mainClock.advanceTimeBy(600)
        text("So speichern – ich meine es genau so.")
        compose.onNodeWithContentDescription("Banner entfernen").performClick()
        text("Gespeichert.")
        closeToast()
        compose.onAllNodes(hasText("Entfernen")).onFirst().performClick()
        compose.waitForIdle()
        text("Deine Zahlen").performClick()
        assertEquals(NetworkRoute.Statistics, navigator.stack(MainTab.Source).last())
        text("Menschen einladen").performClick()
        assertEquals(NetworkRoute.Invitations, navigator.stack(MainTab.Source).last())
    }

    @Test
    fun `blocking a profile from a sheet leads back to the Quelle, its menu mutes and restricts`() {
        val network = StubbedNetwork(
            stub("GET", "network/profiles/jonas", Fixtures.profile.replace("\"oases\"", "\"oases_hidden\"")),
            stub("GET", "network/profiles/jonas/posts", pageJson()),
            stub("POST", "network/users/2/mute", """{"message":"Stumm."}"""),
            stub("POST", "network/users/2/restrict", """{"message":"Eingeschränkt."}""", 500),
            stub("POST", "network/users/2/block", """{"message":"Blockiert."}""")
        )
        navigator.present(NetworkRoute.Profile("jonas"))
        val model = models.keep(ProfileViewModel("jonas", network.repository))
        show { ProfileScreen("jonas", model) }
        compose.settle { compose.onAllNodes(hasTestTag("profile.menu")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("profile.menu").performClick()
        text("Stummschalten").performClick()
        text("Stumm.")
        closeToast()
        compose.onNodeWithTag("profile.menu").performClick()
        text("Einschränken").performClick()
        text("Eingeschränkt.")
        closeToast()
        compose.onNodeWithTag("profile.menu").performClick()
        text("Blockieren …").performClick()
        compose.onNodeWithTag("dialog.confirm").performClick()
        waitFor { navigator.sheet == null && navigator.selectedTab == MainTab.Source }
        // Sharing opens the system sheet, which pauses the activity: the last step of the test.
        compose.onNodeWithContentDescription("Profil teilen").performClick()
    }

    @Test
    fun `friends with nobody waiting, the own numbers in full and invitations without links`() {
        val person = """{"id": 3, "username": "aylin", "display_name": "Aylin Demir", "initials": "AD"}"""
        val network = StubbedNetwork(
            stub("GET", "network/friends", """{"data":{"incoming":[],"friends":[],"outgoing":[$person],"accepts_requests":true}}"""),
            stub("DELETE", "network/friends/3", """{"message":"Zurückgezogen."}"""),
            stub("GET", "network/statistics", Fixtures.statistics),
            stub(
                "GET",
                "network/invitations",
                """{"data":{"invitations":[],"remaining":0,"per_month":3,"valid_days":30,"free_months_this_year":0,
                |"free_months_per_year":12,"free_months_waiting":2,"trial_days":30,"can_create":false}}
                """.trimMargin()
            )
        )
        show { FriendsScreen(models.keep(FriendsViewModel(network.repository))) }
        text("Gerade keine offenen Anfragen.")
        text("Noch keine. Auf einem Profil findest du „Freundschaft anfragen“.")
        text("Zurückziehen").performClick()
        text("Zurückgezogen.")

        val stats = models.keep(StatisticsViewModel(network.repository))
        switchTo { StatisticsScreen(stats) }
        waitFor { compose.onAllNodes(hasContentDescription("In deiner Karawane", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        text("Frage an die Runde", substring = true).performClick()
        assertEquals(NetworkRoute.Post(9), navigator.stack(MainTab.Source).last())
        val quiet = StubbedNetwork(stub("GET", "network/statistics", """{"data":{"totals":{},"weekly":[],"top_posts":[]}}"""))
        val noPosts = models.keep(StatisticsViewModel(quiet.repository))
        switchTo { StatisticsScreen(noPosts) }
        text("In den letzten 30 Tagen hast du nichts geschrieben.")

        val invitations = models.keep(InvitationsViewModel(network.repository))
        switchTo { InvitationsScreen(invitations) }
        text("Du hast noch keine Einladungslinks erstellt.")
        text("2 warten auf eine laufende Mitgliedschaft.")
    }

    @Test
    fun `cards for a reader who may not interact, the author's own card and a real long press`() {
        val reader = base.copy(id = 40, viewer = viewer.copy(canInteract = false, canCarry = false, bookmarked = true))
        val own = base.copy(id = 41, viewer = viewer.copy(isAuthor = true, canDelete = false, canCarry = false))
        show { NetworkPage { feedSection(PostListState<NoContext>(posts = listOf(reader, own), state = Loadable.Loaded(Unit)), handlers) } }
        compose.onNodeWithTag("post.card.40").performTouchInput { longClick(Offset(centerX, 4f)) }
        text("Aus Merkliste entfernen").performClick()
        compose.onAllNodesWithTag("post.menu")[1].performClick()
        text("Melden").performClick()
        compose.onAllNodesWithTag("post.react").onFirst().performClick()
        assertEquals(listOf(PostAction.Unbookmark to 40, PostAction.React to 41), actions)
    }

    @Test
    fun `parts of pages that only some states show`() {
        val composer = data(ComposerContext.serializer(), Fixtures.composer).copy(kinds = emptyList())
        val broken = de.velwebsolutions.oasis.network.ui.compose.PickedImage(
            1,
            de.velwebsolutions.oasis.network.api.ImageUpload(byteArrayOf(1, 2), "x.jpg", "image/jpeg")
        )
        val noCallbacks = ComposerCallbacks(
            onBody = {}, onVisibility = {}, onKind = {}, onLinkRead = {}, onToneConfirmed = {}, onAddImage = {}, onRemoveImage = {},
            onAlt = { _, _ -> }, checkTone = {}, publish = { null }
        )
        val oasis = data(
            de.velwebsolutions.oasis.network.api.OasisDetail.serializer(),
            oasisJson.replace("MEMBER", "false")
        ).copy(canFollow = false)
        val profile = data(de.velwebsolutions.oasis.network.api.Profile.serializer(), Fixtures.profile)
            .copy(following = false, canFollow = false, friendship = null, acceptsFriendRequests = false)
        val discover = PostListState<DiscoverContext>(state = Loadable.Loaded(Unit), nextCursor = "x")
        val source = PostListState<SourceContext>(context = SourceContext(), state = Loadable.Loaded(Unit), nextCursor = "x")
        val search = PostListState<SearchContext>(state = Loadable.Loaded(Unit))
        show {
            NetworkPage {
                oasisContent(
                    oasis, PostListState(state = Loadable.Loaded(Unit)), handlers, null, "", onMemberQuery = {}, onFollow = {},
                    onUnfollow = {}, onCompose = {}
                )
            }
        }
        text("Gärtnern.")
        compose.onNodeWithTag("oasis.follow").assertDoesNotExist()
        switchTo {
            NetworkPage {
                profileContent(
                    profile,
                    PostListState(state = Loadable.Loaded(Unit)),
                    handlers,
                    de.velwebsolutions.oasis.network.ui.profiles.ProfileCallbacks({}, {})
                )
            }
        }
        compose.onNodeWithTag("profile.requestFriend").assertDoesNotExist()
        switchTo { NetworkPage { discoverContent(discover, handlers) } }
        switchTo { NetworkPage { sourceContent(source, "Mira", handlers, onSelectView = {}) } }
        switchTo { NetworkPage { searchContent("garten", search, handlers, onQuery = {}, onSubmit = {}) } }
        text("Menschen")
        val notSearching = PostListState(context = SearchContext(searching = false), state = Loadable.Loaded(Unit))
        switchTo { NetworkPage { searchContent("garten", notSearching, handlers, onQuery = {}, onSubmit = {}) } }
        text("Such nach Namen oder @Benutzernamen.")
    }

    @Test
    fun `a composer without kinds and with an image it cannot preview`() {
        val composer = data(ComposerContext.serializer(), Fixtures.composer).copy(kinds = emptyList())
        val broken = de.velwebsolutions.oasis.network.ui.compose.PickedImage(
            1,
            de.velwebsolutions.oasis.network.api.ImageUpload(byteArrayOf(1, 2), "x.jpg", "image/jpeg")
        )
        val noCallbacks = ComposerCallbacks(
            onBody = {}, onVisibility = {}, onKind = {}, onLinkRead = {}, onToneConfirmed = {}, onAddImage = {}, onRemoveImage = {},
            onAlt = { _, _ -> }, checkTone = {}, publish = { null }
        )
        show { ComposerForm(ComposeUiState(context = Loadable.Loaded(composer), images = listOf(broken)), noCallbacks) }
        text("Bild 1 entfernen")
        compose.onNodeWithTag("compose.kind").assertDoesNotExist()
    }

    @Test
    fun `a picked banner that cannot be shown keeps its name`() {
        val network = StubbedNetwork(stub("GET", "network/oases", Fixtures.oases))
        val model = models.keep(OasisFormViewModel(null, network.repository))
        show { OasisFormScreen(null, model) }
        text("Bild hochladen")
        model.launch { model.pickBanner(de.velwebsolutions.oasis.network.api.ImageUpload(byteArrayOf(1, 2), "kaputt.jpg", "image/jpeg")) }
        text("Ausgewählt: kaputt.jpg")
    }

    @Test
    fun `the search keyboard without a topic, before and after results`() {
        val network = StubbedNetwork(stub("GET", "network/search", Fixtures.search))
        val model = models.keep(de.velwebsolutions.oasis.network.ui.feeds.SearchViewModel("", network.repository))
        show { de.velwebsolutions.oasis.network.ui.feeds.SearchScreen("", model) }
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNode(hasSetTextAction()).performTextInput("ga")
        waitFor { model.list.state.value.context != null }
        compose.onNode(hasSetTextAction()).performImeAction()
        assertTrue(navigator.stack(MainTab.Source).isEmpty())
    }

    @Test
    fun `a member who knows the tour, and one already sent there in this session`() {
        val page = pageJson(postJson(3), context = """{"show_all":false,"follows_anyone":true,"can_post":true}""")
        val network = StubbedNetwork(stub("GET", "network/source", page))
        network.session.update(Me(7, "mira", "Mira Sol", "MS", "mira@example.org"))
        val known = models.keep(SourceViewModel(network.repository, network.session))
        show(ScreenChrome()) { SourceScreen(known) }
        text("Hallo Mira Sol.")
        assertNull(navigator.sheet)
        network.session.update(Me(7, "mira", "Mira Sol", "MS", "mira@example.org", onboarded = false))
        val asked = models.keep(SourceViewModel(network.repository, network.session)).apply { askedWelcome = true }
        switchTo { SourceScreen(asked) }
        compose.waitForIdle()
        assertNull(navigator.sheet)
    }

    @Test
    fun `house rules open and close, and a member becomes host`() {
        val members = """{"data":{"members":[],"hidden_members":0,"own_list_audience":null}}"""
        val edit = """{"data":{"oasis":{"id":2,"slug":"garten","name":"Garten"},"house_rules":"","members":[{"id":3,"username":"aylin",
            |"display_name":"Aylin","role":"member","role_label":"Mitglied"}],"options":{"visibilities":[],"colors":[]}}}
        """.trimMargin()
        val network = StubbedNetwork(
            stub("GET", "network/oases/garten", oasisJson.replace("MEMBER", "true")),
            stub("GET", "network/oases/garten/posts", pageJson()),
            stub("GET", "network/oases/garten/members", members),
            stub("GET", "network/oases/garten/edit", edit),
            stub("PUT", "network/oases/garten/members/3/role", """{"message":"Gastgeberin.","data":{"still_host":true}}""")
        )
        val oasis = models.keep(OasisViewModel("garten", network.repository))
        show { OasisDetailScreen("garten", oasis) }
        text("Hausregeln dieser Oase")
        compose.onNodeWithTag("oasis.rules").performClick()
        text("Eine")
        text("Hausregeln dieser Oase").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        val form = models.keep(OasisFormViewModel("garten", network.repository))
        switchTo { OasisFormScreen("garten", form) }
        text("Zu Gastgeber:in machen").performClick()
        text("Gastgeberin.")
    }

    @Test
    fun `the Tropfen page for every kind of reader`() {
        val detail = data(de.velwebsolutions.oasis.network.api.PostDetail.serializer(), Fixtures.post)
        val composer = data(ComposerContext.serializer(), Fixtures.composer)
        val noCallbacks = ComposerCallbacks(
            onBody = {}, onVisibility = {}, onKind = {}, onLinkRead = {}, onToneConfirmed = {}, onAddImage = {}, onRemoveImage = {},
            onAlt = { _, _ -> }, checkTone = {}, publish = { null }
        )
        val variants = listOf(
            detail.copy(post = detail.post.copy(editedAt = null, viewer = null)),
            detail.copy(
                post = detail.post.copy(editedAt = now, oasis = null, viewer = viewer.copy(canWriteReply = false, canReply = true)),
                revisions = emptyList()
            ),
            detail.copy(post = detail.post.copy(viewer = viewer.copy(canWriteReply = false, canReply = false)))
        )
        var shown by mutableStateOf(variants[0])
        show {
            de.velwebsolutions.oasis.network.ui.posts.PostDetailContent(
                shown,
                ComposeUiState(context = Loadable.Loaded(composer)),
                noCallbacks,
                onAction = { _, _ ->
                },
                onRefresh = {},
                onLoadReply = {}
            )
        }
        text("Leise Zahlen")
        variants.drop(1).forEach {
            shown = it
            compose.waitForIdle()
        }
        compose.onNodeWithTag("post.replyNeedsMembership").assertDoesNotExist()
    }

    @Test
    fun `Antworten from a card focuses the reply field of its page`() {
        val network = StubbedNetwork(stub("GET", "network/posts/12", Fixtures.post), stub("GET", "network/composer", Fixtures.composer))
        NetworkSignals.replyFocus.value = 12
        val model = models.keep(PostDetailViewModel(12, network.repository))
        val reply = models.keep(ComposeViewModel(null, 12, null, network.repository, network.common, network.session))
        show { PostDetailScreen(12, model, reply) }
        waitFor { NetworkSignals.replyFocus.value == null }
    }

    @Test
    fun `blocking a muted person without a sheet open`() {
        val network = StubbedNetwork(
            stub("GET", "network/profiles/jonas", Fixtures.profile.replace("\"has_muted\": false", "\"has_muted\": true")),
            stub("GET", "network/profiles/jonas/posts", pageJson()),
            stub("DELETE", "network/users/2/mute", """{"message":"Nicht mehr stumm."}"""),
            stub("POST", "network/users/2/block", """{"message":"Blockiert."}""")
        )
        navigator.open(NetworkRoute.Profile("jonas"))
        val model = models.keep(ProfileViewModel("jonas", network.repository))
        show { ProfileScreen("jonas", model) }
        compose.settle { compose.onAllNodes(hasTestTag("profile.menu")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("profile.menu").performClick()
        text("Nicht mehr stumm").performClick()
        text("Nicht mehr stumm.")
        closeToast()
        compose.onNodeWithTag("profile.menu").performClick()
        text("Blockieren …").performClick()
        compose.onNodeWithTag("dialog.confirm").performClick()
        waitFor { navigator.stack(MainTab.Source).isEmpty() }
    }

    @Test
    fun `no friends at all, and invitations that cannot be made`() {
        val overview = { remaining: Int, canCreate: Boolean ->
            """{"data":{"invitations":[],"remaining":$remaining,"per_month":3,"valid_days":30,"free_months_this_year":0,
            |"free_months_per_year":12,"free_months_waiting":0,"trial_days":30,"can_create":$canCreate}}
            """.trimMargin()
        }
        val network = StubbedNetwork(
            stub("GET", "network/friends", Fixtures.friends),
            stub("GET", "network/invitations", overview(2, false))
        )
        show { FriendsScreen(models.keep(FriendsViewModel(network.repository))) }
        text("Gerade keine offenen Anfragen.")
        val cannot = models.keep(InvitationsViewModel(network.repository))
        switchTo { InvitationsScreen(cannot) }
        text("Du hast noch keine Einladungslinks erstellt.")

        network.add("GET", "network/invitations", json = overview(1, true))
        network.add("POST", "network/invitations", 422, """{"message":"Keine Links mehr.","errors":{"invitation":["Keine Links mehr."]}}""")
        val budget = models.keep(InvitationsViewModel(network.repository))
        switchTo { InvitationsScreen(budget) }
        compose.settle { compose.onAllNodes(hasTestTag("invitations.create")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("invitations.create").performClick()
        text("Keine Links mehr.")
        network.add("POST", "network/invitations", 500, """{"message":"Kaputt."}""")
        compose.onNodeWithTag("invitations.create").performClick()
        text("Kaputt.")
    }
}
