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
import de.velwebsolutions.oasis.network.ui.compose.ComposerCallbacks
import de.velwebsolutions.oasis.network.ui.compose.ComposerForm
import de.velwebsolutions.oasis.network.ui.compose.ToneHintNote
import de.velwebsolutions.oasis.network.ui.feeds.SeenCut
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

/** „Mach deine Startseite lebendig“ on the Quelle (docs/oasis-accounts.md, „Nach der Registrierung“): no forced tour any more. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h3000dp")
class OnboardingCardUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val navigator = Navigator()
    private val models = ViewModelKeeper()
    private var screen by mutableStateOf<@Composable () -> Unit>({})
    private val client = StubbedNetwork().transport.client

    @After
    fun clear() {
        models.clear()
        NetworkSignals.confirmation.value = null
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

    @Test
    fun `the Quelle offers a new member the welcome tour on a card and scrolls up for a new Tropfen`() {
        val network = StubbedNetwork(
            stub("GET", "network/source", pageJson(postJson(3), context = """{"show_all":false,"follows_anyone":true,"can_post":true}"""))
        )
        network.session.update(Me(7, "mira", "Mira Sol", "MS", "mira@example.org", onboarded = false))
        val card = de.velwebsolutions.oasis.testing.MemoryOnboardingCardStore()
        val model = models.keep(SourceViewModel(network.repository, network.session, card))
        show(ScreenChrome()) { SourceScreen(model) }
        text("Hallo Mira Sol.")
        assertNull("no tour forced on the member", navigator.sheet)
        text("Mach deine Startseite lebendig")
        text("Wähl ein paar Crowds und Menschen, dann füllt sich deine Startseite.")
        compose.onNodeWithTag("source.onboarding.start").performClick()
        assertEquals(AccountRoute.Welcome, navigator.sheet)
        compose.onNodeWithTag("source.onboarding.later").performClick()
        compose.waitUntil(5_000) { model.cardSnoozed.value }
        compose.onNodeWithTag("source.onboarding").assertDoesNotExist()
        val days = java.time.Duration.between(java.time.Instant.now(), card.until).toDays()
        assertEquals("„Später“ hides the card for 14 days", 13L, days)
        NetworkSignals.didPublish(decodePost(postJson(9)), "Dein Post ist da.")
        text("Post 9")
        compose.onNodeWithContentDescription("Dein Post: Was möchtest du teilen?").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Gefolgt").performClick()
    }

    @Test
    fun `no card for a member who knows the tour, nor while „Später“ holds`() {
        val page = pageJson(postJson(3), context = """{"show_all":false,"follows_anyone":true,"can_post":true}""")
        val network = StubbedNetwork(stub("GET", "network/source", page))
        network.session.update(Me(7, "mira", "Mira Sol", "MS", "mira@example.org"))
        val known = models.keep(
            SourceViewModel(network.repository, network.session, de.velwebsolutions.oasis.testing.MemoryOnboardingCardStore())
        )
        show(ScreenChrome()) { SourceScreen(known) }
        text("Hallo Mira Sol.")
        compose.onNodeWithTag("source.onboarding").assertDoesNotExist()
        network.session.update(Me(7, "mira", "Mira Sol", "MS", "mira@example.org", onboarded = false))
        val later = java.time.Instant.now().plus(java.time.Duration.ofDays(3))
        val snoozed = models.keep(
            SourceViewModel(network.repository, network.session, de.velwebsolutions.oasis.testing.MemoryOnboardingCardStore(later))
        )
        assertTrue(snoozed.cardSnoozed.value)
        switchTo { SourceScreen(snoozed) }
        text("Hallo Mira Sol.")
        compose.onNodeWithTag("source.onboarding").assertDoesNotExist()
        val past = java.time.Instant.now().minus(java.time.Duration.ofDays(1))
        val expired = SourceViewModel(network.repository, network.session, de.velwebsolutions.oasis.testing.MemoryOnboardingCardStore(past))
        assertFalse("after 14 days the card is back", models.keep(expired).cardSnoozed.value)
    }
}
