package de.velwebsolutions.oasis.network

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.DataEnvelope
import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.network.api.ComposerContext
import de.velwebsolutions.oasis.network.api.DiscoverContext
import de.velwebsolutions.oasis.network.api.ExternalLink
import de.velwebsolutions.oasis.network.api.FeedPage
import de.velwebsolutions.oasis.network.api.ImageUpload
import de.velwebsolutions.oasis.network.api.NoContext
import de.velwebsolutions.oasis.network.api.OasisDetail
import de.velwebsolutions.oasis.network.api.OasisDirectory
import de.velwebsolutions.oasis.network.api.OasisMembers
import de.velwebsolutions.oasis.network.api.Post
import de.velwebsolutions.oasis.network.api.PostDetail
import de.velwebsolutions.oasis.network.api.PostRevision
import de.velwebsolutions.oasis.network.api.Profile
import de.velwebsolutions.oasis.network.api.SearchContext
import de.velwebsolutions.oasis.network.api.SwipeDeck
import de.velwebsolutions.oasis.network.api.TextSegment
import de.velwebsolutions.oasis.network.ui.PostAction
import de.velwebsolutions.oasis.network.ui.PostListState
import de.velwebsolutions.oasis.network.ui.components.FeedHandlers
import de.velwebsolutions.oasis.network.ui.components.ImageViewer
import de.velwebsolutions.oasis.network.ui.components.NetworkPage
import de.velwebsolutions.oasis.network.ui.components.block
import de.velwebsolutions.oasis.network.ui.components.feedSection
import de.velwebsolutions.oasis.network.ui.compose.ComposeUiState
import de.velwebsolutions.oasis.network.ui.compose.ComposerCallbacks
import de.velwebsolutions.oasis.network.ui.compose.PickedImage
import de.velwebsolutions.oasis.network.ui.feeds.SwipeContent
import de.velwebsolutions.oasis.network.ui.feeds.discoverContent
import de.velwebsolutions.oasis.network.ui.feeds.searchContent
import de.velwebsolutions.oasis.network.ui.oases.oasesContent
import de.velwebsolutions.oasis.network.ui.oases.oasisContent
import de.velwebsolutions.oasis.network.ui.posts.ComposeContent
import de.velwebsolutions.oasis.network.ui.posts.PostDetailContent
import de.velwebsolutions.oasis.network.ui.profiles.ProfileAction
import de.velwebsolutions.oasis.network.ui.profiles.ProfileCallbacks
import de.velwebsolutions.oasis.network.ui.profiles.ProfileConfirmation
import de.velwebsolutions.oasis.network.ui.profiles.profileContent
import java.time.Instant
import kotlinx.serialization.KSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every network page in its states, with screenshots (Roborazzi) and the taps that lead elsewhere. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h4000dp")
class NetworkScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val navigator = Navigator()
    private val actions = mutableListOf<Pair<PostAction, Int>>()
    private val handlers = FeedHandlers(perform = { action, post -> actions += action to post.id }, loadMore = {}, reload = {})

    @After
    fun clear() {
        NetworkSignals.replyFocus.value = null
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { OasisTheme { CompositionLocalProvider(LocalNavigator provides navigator, content = content) } }
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/network-$name.png")

    private fun scrollTo(text: String) = compose.onNodeWithTag("network.page").performScrollToNode(hasText(text, substring = true))

    private fun <T> data(serializer: KSerializer<T>, json: String): T =
        OasisJson.snake.decodeFromString(DataEnvelope.serializer(serializer), json).data

    private fun <C> page(serializer: KSerializer<C>, json: String): FeedPage<C> =
        OasisJson.snake.decodeFromString(FeedPage.serializer(serializer), json)

    private val base = decodePost(postJson(1))
    private val viewer = base.viewer!!
    private val author = base.author

    private val noComposer = ComposerCallbacks(
        onBody = {}, onVisibility = {}, onKind = {}, onLinkRead = {}, onToneConfirmed = {}, onAddImage = {}, onRemoveImage = {},
        onAlt = { _, _ -> }, checkTone = {}, publish = { null }
    )

    @Test
    fun `a card shows the author's notices, quiet numbers and the menu`() {
        val own = base.copy(
            id = 2,
            kind = "question",
            visibility = "members",
            editedAt = Instant.parse("2026-10-22T11:00:00Z"),
            oasis = Post.OasisRef("stadtgarten", "Stadtgarten", "spring"),
            removedFromOasis = true,
            held = Post.Held("Beleidigung", checkReason = "Ein Schimpfwort", reviewHours = 12),
            stats = Post.Stats(reactions = 1, replies = 3),
            viewer = viewer.copy(isAuthor = true, canEdit = true, canDelete = true, canRemoveFromOasis = true, canReply = false)
        )
        val statsOnly = base.copy(id = 3, stats = Post.Stats(reactions = 2, replies = 1), viewer = viewer.copy(isAuthor = true))
        show {
            NetworkPage { feedSection(PostListState<NoContext>(posts = listOf(own, statsOnly), state = Loadable.Loaded(Unit)), handlers) }
        }

        compose.onNodeWithText("Frage an meine Karawane").assertIsDisplayed()
        compose.onNodeWithText("Zurückgehalten und dem Moderationsteam gemeldet").assertIsDisplayed()
        capture("card-author")
        scrollTo("Nur du siehst diese Zahlen:")
        scrollTo("3 Menschen haben geantwortet")

        compose.onAllNodesWithTag("post.menu").onFirst().performClick()
        compose.onNodeWithText("Bearbeiten").assertIsDisplayed()
        compose.onNodeWithText("Aus Oase entfernen …").assertIsDisplayed()
        compose.onNodeWithText("Löschen …").performClick()
        compose.onNodeWithText("Wirklich löschen?").assertIsDisplayed()
        capture("card-delete-dialog")
        compose.onNodeWithTag("dialog.confirm").performClick()
        assertEquals(listOf(PostAction.Delete to 2), actions)

        compose.onAllNodesWithTag("post.menu").onFirst().performClick()
        compose.onNodeWithText("Bearbeiten").performClick()
        assertEquals(NetworkRoute.EditPost(2), navigator.stack(MainTab.Source).last())
    }

    @Test
    fun `someone else's card offers muting, blocking, reporting and the long-press menu`() {
        val other = base.copy(
            id = 4,
            replyTo = Post.ReplyRef(1, "jonas"),
            viewer = viewer.copy(canQuickReply = true, bookmarked = true, hasMutedAuthor = true, hasRestrictedAuthor = true)
        )
        val unavailable = base.copy(id = 5, replyUnavailable = true, viewer = viewer.copy(reacted = true))
        show {
            NetworkPage {
                feedSection(PostListState<NoContext>(posts = listOf(other, unavailable), state = Loadable.Loaded(Unit)), handlers)
            }
        }

        compose.onNodeWithText("Antwort an @jonas", substring = true).assertIsDisplayed()
        compose.onAllNodesWithTag("post.menu").onFirst().performClick()
        compose.onNodeWithText("Nicht mehr stumm").performClick()
        compose.onAllNodesWithTag("post.menu").onFirst().performClick()
        compose.onNodeWithText("Nicht mehr einschränken").performClick()
        compose.onAllNodesWithTag("post.menu").onFirst().performClick()
        compose.onNodeWithText("Blockieren …").performClick()
        compose.onNodeWithText("Ihr verliert gegenseitige Follows.").assertIsDisplayed()
        compose.onNodeWithTag("dialog.cancel").performClick()
        compose.onAllNodesWithTag("post.menu").onFirst().performClick()
        compose.onNodeWithText("Melden").performClick()
        assertEquals(AccountRoute.Report(other.url), navigator.stack(MainTab.Source).last())

        compose.onNodeWithTag("post.card.4").performTouchInput { longClick(Offset(centerX, 4f)) }
        compose.onNodeWithText("Aus Merkliste entfernen").assertIsDisplayed()
        capture("card-long-press")
        compose.onNodeWithText("Reagieren").performClick()
        compose.onNodeWithTag("post.card.4").performTouchInput { longClick(Offset(centerX, 4f)) }
        compose.onNodeWithText("Weitertragen").performClick()
        assertEquals(NetworkRoute.CarryPost(4), navigator.stack(MainTab.Source).last())

        compose.onNodeWithText("Schnell antworten").performClick()
        assertEquals(NetworkRoute.Compose(replyTo = 4), navigator.sheet)
        navigator.dismissSheet()

        compose.onAllNodesWithTag("post.action.Antworten").onFirst().performClick()
        assertEquals(NetworkRoute.Post(4), navigator.stack(MainTab.Source).last())
        compose.onAllNodesWithTag("post.bookmark").onFirst().performClick()
        assertEquals(
            listOf(PostAction.Unmute to 4, PostAction.Unrestrict to 4, PostAction.React to 4, PostAction.Unbookmark to 4),
            actions
        )
        scrollTo("Antwort auf einen Tropfen, der nicht mehr verfügbar ist")
    }

    @Test
    fun `images, covered images, locked images and carried Tropfen`() {
        val images = base.copy(
            id = 6,
            images = listOf(
                Post.Image(1, "http://localhost/a.jpg", "Ein Beet", 1200, 800),
                Post.Image(2, "http://localhost/b.jpg", "Ein Baum", 800, 800, covered = true),
                Post.Image(3, "http://localhost/c.jpg", "Ein Weg", 800, 1200)
            )
        )
        val locked = base.copy(id = 7, imagesLocked = true)
        val carried = base.copy(
            id = 8,
            carried = Post.Carried(
                Post.Carried.State.Visible,
                Post.Carried.Original(9, author, "Das Original", listOf(TextSegment(text = "Das Original")))
            )
        )
        val muted = base.copy(id = 10, carried = Post.Carried(Post.Carried.State.Muted))
        val gone = base.copy(id = 11, carried = Post.Carried(Post.Carried.State.Unavailable), viewer = null)
        val posts = listOf(images, locked, carried, muted, gone)
        show { NetworkPage { feedSection(PostListState<NoContext>(posts = posts, state = Loadable.Loaded(Unit)), handlers) } }

        compose.onNodeWithContentDescription("Bild anzeigen: Ein Baum").performClick()
        compose.onNodeWithContentDescription("Ein Baum").assertIsDisplayed()
        capture("card-images")
        compose.onNodeWithContentDescription("Ein Beet").performClick()
        compose.onNodeWithContentDescription("Bild 1 von 3").assertExists()
        compose.onNodeWithTag("imageViewer.close").performClick()
        scrollTo("Bilder gesperrt.")
        scrollTo("Das Original")
        scrollTo("Tropfen einer stummgeschalteten Person.")
        scrollTo("Dieser Tropfen ist nicht mehr verfügbar.")
    }

    @Test
    fun `the image viewer of a single image has no page dots`() {
        var closed = false
        val image = Post.Image(1, "http://localhost/a.jpg", "Ein Beet", 1200, 800)
        show { ImageViewer(listOf(image), image) { closed = true } }
        compose.onNodeWithTag("imageViewer.pages").assertDoesNotExist()
        compose.onNodeWithContentDescription("Schließen").performClick()
        assertTrue(closed)
    }

    @Test
    fun `the feed's states - loading, empty, failed and older Tropfen`() {
        show {
            NetworkPage {
                feedSection(PostListState<NoContext>(state = Loadable.Loading), handlers, keyPrefix = "loading")
                feedSection(
                    PostListState<NoContext>(state = Loadable.Loaded(Unit)),
                    handlers,
                    empty = "Noch nichts da.",
                    keyPrefix = "empty"
                )
                feedSection(
                    PostListState<NoContext>(state = Loadable.Failed(ApiError.Server(500, "Kaputt."))),
                    handlers,
                    keyPrefix = "failed"
                )
                feedSection(
                    PostListState<NoContext>(posts = listOf(base), state = Loadable.Loaded(Unit), nextCursor = "abc", isLoadingMore = true),
                    handlers,
                    keyPrefix = "more"
                )
                feedSection(
                    PostListState<NoContext>(
                        posts = listOf(base.copy(id = 20)),
                        state = Loadable.Loaded(Unit),
                        nextCursor = "abc",
                        loadMoreError = ApiError.Server(500, "Nicht erreichbar.")
                    ),
                    handlers,
                    keyPrefix = "moreFailed"
                )
                feedSection(
                    PostListState<NoContext>(posts = listOf(base.copy(id = 21)), state = Loadable.Loaded(Unit)),
                    handlers,
                    end = "Das sind alle Treffer.",
                    keyPrefix = "end"
                )
            }
        }
        capture("feed-states")
        scrollTo("Noch nichts da.")
        scrollTo("Noch einmal laden")
        scrollTo("Ältere Tropfen …")
        scrollTo("Nicht erreichbar.")
        scrollTo("Das sind alle Treffer.")
    }

    @Test
    fun `Entdecken lists the largest oases and explains itself`() {
        val discover = page(DiscoverContext.serializer(), Fixtures.discover)
        val state = PostListState(posts = discover.data, context = discover.context, state = Loadable.Loaded(Unit))
        show { NetworkPage { discoverContent(state, handlers) } }
        compose.onNodeWithText("Wer schon da ist").assertIsDisplayed()
        capture("discover")
        compose.onNodeWithText("Suchen").performClick()
        assertEquals(NetworkRoute.Search(), navigator.stack(MainTab.Source).last())
        compose.onNodeWithText("Durchblättern").performClick()
        assertEquals(NetworkRoute.Swipe, navigator.stack(MainTab.Source).last())
    }

    @Test
    fun `the search before typing explains itself`() {
        show { NetworkPage { searchContent("", PostListState(), handlers, onQuery = {}, onSubmit = {}) } }
        compose.onNodeWithText("Wonach suchst du?").assertIsDisplayed()
        compose.onNodeWithText("So sucht Oasis").assertIsDisplayed()
        compose.onNodeWithText("Such nach Namen oder @Benutzernamen.").assertIsDisplayed()
        capture("search-empty")
    }

    @Test
    fun `search results show people, oases and Tropfen`() {
        val results = page(SearchContext.serializer(), Fixtures.search)
        val state = PostListState(posts = results.data, context = results.context, state = Loadable.Loaded(Unit), filter = "ga")
        show { NetworkPage { searchContent("ga", state, handlers, onQuery = {}, onSubmit = {}) } }
        compose.onNodeWithText("Ergebnisse für „ga“").assertIsDisplayed()
        capture("search-results")
        scrollTo("Tropfen")
    }

    @Test
    fun `a topic term offers its page`() {
        val topic =
            PostListState(context = SearchContext(term = "#garten", searching = true, topic = "garten"), state = Loadable.Loaded(Unit))
        show { NetworkPage { searchContent("#garten", topic, handlers, onQuery = {}, onSubmit = {}) } }
        compose.onNodeWithTag("search.topic").performClick()
        assertEquals(NetworkRoute.Tag("garten"), navigator.stack(MainTab.Source).last())
        capture("search-topic")
    }

    @Test
    fun `a search over the budget says to wait, a long term is refused`() {
        val failed = PostListState<SearchContext>(state = Loadable.Failed(ApiError.TooManyRequests(60)))
        var cleared: String? = null
        show { NetworkPage { searchContent("g".repeat(101), failed, handlers, onQuery = { cleared = it }, onSubmit = {}) } }
        compose.onNodeWithText("Dein Suchbegriff darf höchstens 100 Zeichen lang sein.").assertIsDisplayed()
        compose.onNodeWithText("Du hast gerade viel gesucht. Warte bitte eine Minute.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Suchbegriff löschen").performClick()
        assertEquals("", cleared)
        capture("search-failed")
    }

    @Test
    fun `the oases overview with its sections`() {
        val directory = data(OasisDirectory.serializer(), Fixtures.oases)
        show { NetworkPage { oasesContent(directory.copy(canCreate = true, mine = emptyList())) } }
        compose.onNodeWithText("Räume für das, was dich interessiert").assertIsDisplayed()
        compose.onNodeWithText("Du folgst noch keiner Oase.").assertIsDisplayed()
        capture("oases")
        compose.onNodeWithTag("oases.create").performClick()
        assertEquals(NetworkRoute.CreateOasis, navigator.stack(MainTab.Source).last())
    }

    private val oasis =
        """{"data": {"oasis": {"id": 2, "slug": "stadtgarten", "name": "Stadtgarten", "description": "Gärtnern.", "color": "dune",
        |"visibility": "public", "banner_url": null, "members_count": 1, "role": "host"}, "url": "http://localhost/oasen/stadtgarten",
        |"house_rules": ["Freundlich bleiben.", "Keine Werbung."], "hosts": [{"id": 1, "username": "mira", "display_name": "Mira Sol",
        |"initials": "MS", "avatar_color": "sun", "photo_url": null}], "is_member": true, "is_host": true, "can_follow": false,
        |"can_post": true}}
        """.trimMargin()

    @Test
    fun `an oasis for its host with members, rules and host tools`() {
        val detail = data(OasisDetail.serializer(), oasis)
        val members = OasisMembers(
            members = listOf(OasisMembers.Entry(3, "aylin", "Aylin Demir", "AD", canWrite = true)),
            hiddenMembers = 1,
            ownListAudience = "contacts"
        )
        var unfollowed = false
        var composed = false
        show {
            NetworkPage {
                oasisContent(
                    detail, PostListState(state = Loadable.Loaded(Unit)), handlers, members, "", onMemberQuery = {},
                    onFollow = {}, onUnfollow = { unfollowed = true }, onCompose = { composed = true }
                )
            }
        }
        compose.onNodeWithTag("oasis.unfollow").performClick()
        assertTrue(unfollowed)
        compose.onNodeWithTag("oasis.rules").performClick()
        compose.onNodeWithText("Keine Werbung.").assertIsDisplayed()
        capture("oasis-host")
        compose.onNodeWithContentDescription("Dein Tropfen in Stadtgarten").performClick()
        assertTrue(composed)
        scrollTo("Ein weiteres Mitglied zeigt sich")
        compose.onNodeWithContentDescription("Aylin Demir schreiben").performClick()
        scrollTo("Bearbeiten")
        compose.onNodeWithTag("oasis.edit").performClick()
        assertEquals(NetworkRoute.EditOasis("stadtgarten"), navigator.stack(MainTab.Source).last())
    }

    @Test
    fun `an oasis for a visitor offers following and reporting`() {
        val detail = data(OasisDetail.serializer(), oasis).let {
            it.copy(isMember = false, isHost = false, canFollow = true, canPost = false, houseRules = listOf("Nur eine Regel."))
        }
        show {
            NetworkPage {
                oasisContent(
                    detail, PostListState(state = Loadable.Loaded(Unit)), handlers, null, "", onMemberQuery = {},
                    onFollow = {}, onUnfollow = {}, onCompose = {}
                )
            }
        }
        compose.onNodeWithTag("oasis.follow").assertIsDisplayed()
        compose.onNodeWithText("Melden").performClick()
        assertEquals(AccountRoute.Report("http://localhost/oasen/stadtgarten"), navigator.stack(MainTab.Source).last())
        compose.onNodeWithTag("oasis.rules").performClick()
        compose.onNodeWithText("Nur eine Regel.").assertIsDisplayed()
        capture("oasis-visitor")
    }

    private val profile get() = data(Profile.serializer(), Fixtures.profile)

    @Test
    fun `the own profile links the numbers, the list and the privacy settings`() {
        val own = profile.copy(isMe = true, profileAudience = "Dein Profil sehen alle", bioHeld = true, bannerUrl = null)
        show { NetworkPage { profileContent(own, PostListState(state = Loadable.Loaded(Unit)), handlers, ProfileCallbacks({}, {})) } }
        compose.onNodeWithTag("profile.edit").performClick()
        assertEquals(NetworkRoute.EditProfile, navigator.stack(MainTab.Source).last())
        capture("profile-own")
        scrollTo("Dein Profil sehen alle.")
    }

    @Test
    fun `a friend's profile asks before ending the friendship or blocking`() {
        val asked = mutableListOf<ProfileConfirmation>()
        val friend = profile.copy(friendship = "friends", following = false, canFollow = true)
        show {
            NetworkPage {
                profileContent(
                    friend,
                    PostListState(state = Loadable.Loaded(Unit)),
                    handlers,
                    ProfileCallbacks({}, {
                        asked +=
                            it
                    })
                )
            }
        }
        compose.onNodeWithText("Befreundet ✓").performClick()
        compose.onNodeWithTag("profile.follow").assertIsDisplayed()
        compose.onNodeWithTag("profile.menu").performClick()
        compose.onNodeWithText("Blockieren …").performClick()
        assertEquals(listOf(ProfileConfirmation.EndFriendship, ProfileConfirmation.Block), asked)
        capture("profile-friends")
    }

    @Test
    fun `an own request can be withdrawn and a new one asked for`() {
        val actions = mutableListOf<ProfileAction>()
        var shown by mutableStateOf(profile.copy(friendship = "outgoing"))
        show {
            NetworkPage {
                profileContent(shown, PostListState(state = Loadable.Loaded(Unit)), handlers, ProfileCallbacks({ actions += it }, {}))
            }
        }
        compose.onNodeWithTag("profile.withdrawFriend").performClick()
        shown = profile.copy(friendship = null, acceptsFriendRequests = true, hasRestricted = true)
        compose.waitForIdle()
        compose.onNodeWithTag("profile.requestFriend").performClick()
        compose.onNodeWithTag("profile.menu").performClick()
        compose.onNodeWithText("Nicht mehr einschränken").performClick()
        compose.waitForIdle()
        assertEquals(listOf(ProfileAction.RemoveFriend, ProfileAction.RequestFriend, ProfileAction.Unrestrict), actions)
    }

    @Test
    fun `incoming and outgoing friendship requests on a profile`() {
        val asked = mutableListOf<ProfileConfirmation>()
        show {
            NetworkPage {
                profileContent(
                    profile.copy(friendship = "incoming", hasMuted = true),
                    PostListState(state = Loadable.Loaded(Unit)),
                    handlers,
                    ProfileCallbacks({
                    }, {
                        asked +=
                            it
                    })
                )
            }
        }
        compose.onNodeWithText("Freundschaft annehmen").assertIsDisplayed()
        compose.onNodeWithText("Ablehnen").performClick()
        assertEquals(listOf(ProfileConfirmation.Decline), asked)
        compose.onNodeWithTag("profile.menu").performClick()
        compose.onNodeWithText("Nicht mehr stumm").assertIsDisplayed()
        compose.onNodeWithText("Melden").performClick()
        assertEquals(AccountRoute.Report("http://localhost/profil/jonas"), navigator.stack(MainTab.Source).last())
        capture("profile-incoming")
    }

    @Test
    fun `the Tropfen page with versions, the reply form and the side cards`() {
        val detail = data(PostDetail.serializer(), Fixtures.post).let { loaded ->
            loaded.copy(
                post = loaded.post.copy(
                    editedAt = Instant.parse("2026-10-21T08:00:00Z"),
                    viewer = loaded.post.viewer?.copy(canWriteReply = true)
                ),
                revisions = listOf(PostRevision(1, "Die erste Fassung", Instant.parse("2026-10-21T08:00:00Z"))),
                authorBio = "Schraubt gern."
            )
        }
        val composer = data(ComposerContext.serializer(), Fixtures.composer)
        show {
            PostDetailContent(
                detail = detail,
                reply = ComposeUiState(replyTo = 12, context = Loadable.Loaded(composer)),
                replyCallbacks = noComposer,
                onAction = { action, post -> actions += action to post.id },
                onRefresh = {},
                onLoadReply = {},
                focusReply = true
            )
        }
        compose.onNodeWithTag("post.versions").performClick()
        compose.onNodeWithText("Die erste Fassung").assertIsDisplayed()
        capture("post-detail")
        scrollTo("Schraubt gern.")
        scrollTo("Leise Zahlen")
    }

    @Test
    fun `a reply in an oasis needs its membership`() {
        val detail = data(PostDetail.serializer(), Fixtures.post).let { loaded ->
            loaded.copy(
                post = loaded.post.copy(viewer = loaded.post.viewer?.copy(canWriteReply = false, canReply = true)),
                replies = emptyList()
            )
        }
        show {
            PostDetailContent(detail, ComposeUiState(), noComposer, onAction = { _, _ -> }, onRefresh = {}, onLoadReply = {})
        }
        compose.onNodeWithTag("post.replyNeedsMembership").performClick()
        assertEquals(NetworkRoute.Oasis("rad-werkstatt"), navigator.stack(MainTab.Source).last())
        scrollTo("Noch keine Antworten.")
        capture("post-detail-membership")
    }

    @Test
    fun `Durchblättern goes card by card to the end`() {
        val deck = data(SwipeDeck.serializer(), Fixtures.swipe)
        show { SwipeContent(deck) { action, post -> actions += action to post.id } }
        compose.onNodeWithTag("swipe.progress").assertIsDisplayed()
        capture("swipe-card")
        val total = deck.chapters.sumOf { it.posts.size }
        repeat(total) { compose.onNodeWithTag("swipe.next").performClick() }
        compose.onNodeWithTag("swipe.end").assertIsDisplayed()
        capture("swipe-end")
        compose.onNodeWithContentDescription("Vorherige Karte").performClick()
        compose.onNodeWithTag("swipe.end").assertIsDisplayed()
    }

    @Test
    fun `an empty deck says that nothing is new and nothing waits`() {
        show { SwipeContent(SwipeDeck()) { _, _ -> } }
        compose.onNodeWithText("Gerade gibt es nichts Neues.").assertIsDisplayed()
        compose.onNodeWithText("Alles gelesen – nichts wartet auf dich.").assertIsDisplayed()
        compose.onNodeWithText("Zur Quelle").performClick()
        assertEquals(MainTab.Source, navigator.selectedTab)
        capture("swipe-empty")
    }

    @Test
    fun `the composer with tone notes, a link to confirm, images and the choices`() {
        val bitmap = android.graphics.Bitmap.createBitmap(20, 20, android.graphics.Bitmap.Config.ARGB_8888)
        val png = java.io.ByteArrayOutputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
        val context = data(ComposerContext.serializer(), Fixtures.composer).copy(
            oasis = ComposerContext.OasisName("stadtgarten", "Stadtgarten"),
            links = listOf(ExternalLink("https://example.org/a", "example.org"))
        )
        val state = ComposeUiState(
            oasisSlug = "stadtgarten",
            context = Loadable.Loaded(context),
            body = "Du Idiot",
            toneHint = "Idiot",
            toneNudge = true,
            images = listOf(PickedImage(1, ImageUpload(png, "bild-1.png", "image/png"), "Ein Beet"))
        )
        val calls = mutableListOf<String>()
        val callbacks = ComposerCallbacks(
            onBody = { calls += "body" }, onVisibility = { calls += "visibility:$it" }, onKind = { calls += "kind:$it" },
            onLinkRead = { calls += "linkRead:$it" }, onToneConfirmed = { calls += "tone:$it" }, onAddImage = {},
            onRemoveImage = { calls += "remove:$it" }, onAlt = { _, _ -> }, checkTone = {}, publish = { null }
        )
        show { ComposeContent(state, callbacks, onPublished = {}) }
        compose.onNodeWithText("Dein Tropfen in Stadtgarten").assertExists()
        compose.onNodeWithTag("tone.hint").assertIsDisplayed()
        compose.onNodeWithTag("compose.toneConfirmed").performClick()
        compose.onNodeWithTag("compose.linkRead").performClick()
        compose.onNodeWithText("example.org").performClick()
        capture("composer")
        scrollTo("Bild 1 entfernen")
        compose.onNodeWithText("Bild 1 entfernen").performClick()
        scrollTo("Art des Tropfens")
        compose.onNodeWithTag("compose.kind").performClick()
        compose.onNodeWithText("Frage an meine Karawane").performClick()
        compose.onNodeWithTag("compose.visibility").performClick()
        compose.onNodeWithText("Nur für Mitglieder").performClick()
        compose.onNodeWithTag("compose.submit").performClick()
        compose.waitForIdle()
        assertEquals(
            listOf("tone:true", "linkRead:true", "remove:0", "kind:question", "visibility:members"),
            calls
        )
    }
}
