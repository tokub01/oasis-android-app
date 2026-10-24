package de.velwebsolutions.oasis.network.ui.feeds

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.design.components.Eyebrow
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.Pill
import de.velwebsolutions.oasis.design.components.PillTone
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.AppRoute
import de.velwebsolutions.oasis.design.navigation.ChatRoute
import de.velwebsolutions.oasis.design.navigation.LiveRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.network.api.Post
import de.velwebsolutions.oasis.network.api.SwipeDeck
import de.velwebsolutions.oasis.network.ui.PostAction
import de.velwebsolutions.oasis.network.ui.components.OnNightButton
import de.velwebsolutions.oasis.network.ui.components.OnNightKind
import de.velwebsolutions.oasis.network.ui.components.PostCard
import kotlinx.coroutines.launch

/**
 * "Durchblättern" (/app/durchblaettern): the deck's Tropfen as cards to swipe, chapter by chapter, with a progress line,
 * and the end card "Du bist durch" with what waits for the member. A deck with an end – no endless feed.
 */
@Composable
fun SwipeScreen() {
    val viewModel = hiltViewModel<SwipeViewModel>()
    val deck by viewModel.deck.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }
    OasisScreen(
        title = "Durchblättern",
        message = status,
        error = error?.userMessage,
        onDismissMessage = viewModel::dismissStatus,
        onDismissError = viewModel::dismissError
    ) {
        LoadableView(deck, onRetry = viewModel::load) { loaded ->
            SwipeContent(loaded) { action, post -> viewModel.launch { viewModel.perform(action, post) } }
        }
    }
}

/** One card of the deck: a Tropfen of a chapter, or the end card (no post). */
private data class SwipeCard(val index: Int, val chapter: SwipeDeck.Chapter?, val post: Post?, val firstOfChapter: Boolean)

private fun cards(deck: SwipeDeck): List<SwipeCard> = buildList {
    deck.chapters.forEach { chapter ->
        chapter.posts.forEachIndexed { offset, post -> add(SwipeCard(size, chapter, post, offset == 0)) }
    }
    add(SwipeCard(size, null, null, false))
}

/** A card takes 88 % of the width, so the next one peeks in. */
private object PeekingPage : PageSize {
    override fun Density.calculateMainAxisPageSize(availableSpace: Int, pageSpacing: Int): Int = (availableSpace * 0.88f).toInt()
}

/** The deck with its progress line and the arrows. */
@Composable
fun SwipeContent(deck: SwipeDeck, onAction: (PostAction, Post) -> Unit) {
    val cards = cards(deck)
    val pager = rememberPagerState { cards.size }
    val scope = rememberCoroutineScope()
    val position = pager.currentPage.coerceAtMost(cards.lastIndex)
    Column(Modifier.fillMaxSize().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Eyebrow("Durchblättern")
            Text("Was es Neues gibt", style = OasisType.display(26), color = OasisColors.Night, modifier = Modifier.semantics { heading() })
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clearAndSetSemantics { contentDescription = "Karte ${position + 1} von ${cards.size}" }
                .testTag("swipe.progress"),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            cards.forEach { card ->
                Box(
                    Modifier.weight(1f).height(4.dp).background(
                        if (card.index <=
                            position
                        ) {
                            OasisColors.Night
                        } else {
                            OasisColors.DuneLine
                        },
                        CircleShape
                    )
                )
            }
        }
        HorizontalPager(
            pager,
            modifier = Modifier.weight(1f).testTag("swipe.track"),
            contentPadding = PaddingValues(horizontal = 16.dp),
            pageSize = PeekingPage,
            pageSpacing = 12.dp,
            verticalAlignment = Alignment.Top,
            key = { cards[it].index }
        ) { page ->
            val card = cards[page]
            val post = card.post
            val chapter = card.chapter
            if (post != null && chapter != null) {
                PostPage(post, chapter, card, total = cards.size - 1, onAction)
            } else {
                EndCard(deck)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { scope.launch { pager.animateScrollToPage(maxOf(0, position - 1)) } },
                enabled = position > 0,
                modifier = Modifier
                    .size(52.dp)
                    .background(OasisColors.Paper, CircleShape)
                    .border(1.dp, OasisColors.DuneLine, CircleShape)
                    .alpha(if (position > 0) 1f else 0.45f)
            ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Vorherige Karte", tint = OasisColors.Night) }
            Text("Wischen oder tippen", style = OasisType.SmallBold, color = OasisColors.Muted)
            IconButton(
                onClick = { scope.launch { pager.animateScrollToPage(minOf(cards.lastIndex, position + 1)) } },
                enabled = position < cards.lastIndex,
                modifier = Modifier
                    .size(52.dp)
                    .background(OasisColors.Night, CircleShape)
                    .alpha(if (position < cards.lastIndex) 1f else 0.45f)
                    .testTag("swipe.next")
            ) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "Nächste Karte", tint = OasisColors.OnNight) }
        }
    }
}

@Composable
private fun PostPage(post: Post, chapter: SwipeDeck.Chapter, card: SwipeCard, total: Int, onAction: (PostAction, Post) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(chapter.title, tone = PillTone.Night, icon = chapterIcon(chapter.key))
            Spacer(Modifier.weight(1f))
            Text("${card.index + 1} / $total", style = OasisType.Caption.copy(fontWeight = FontWeight.Bold), color = OasisColors.Muted)
        }
        PostCard(post, onAction = onAction)
        if (card.firstOfChapter) {
            Text(
                chapter.reason,
                style = OasisType.Small,
                color = OasisColors.Ink2,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun EndCard(deck: SwipeDeck) {
    val navigator = LocalNavigator.current
    Column(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 440.dp)
            .background(OasisColors.Night, RoundedCornerShape(OasisRadius.L.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Box(Modifier.size(56.dp).background(OasisColors.Spring, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Check, contentDescription = null, tint = OasisColors.SunInk, modifier = Modifier.size(30.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (deck.chapters.isEmpty()) "Gerade gibt es nichts Neues." else "Du bist durch.",
                style = OasisType.display(28),
                color = OasisColors.OnNight,
                modifier = Modifier.testTag("swipe.end")
            )
            Text("Zeit zum Durchatmen. Das wartet noch auf dich:", style = OasisType.Body, color = OasisColors.OnNightMuted)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (deck.unreadChats > 0) {
                val text = if (deck.unreadChats == 1) "1 Chat mit neuen Nachrichten" else "${deck.unreadChats} Chats mit neuen Nachrichten"
                NewsLink(text, Icons.Outlined.Email, ChatRoute.Conversations, accent = true)
            }
            if (deck.friendRequests > 0) {
                val text = if (deck.friendRequests == 1) "1 Freundschaftsanfrage" else "${deck.friendRequests} Freundschaftsanfragen"
                NewsLink(text, Icons.Outlined.People, NetworkRoute.Friends)
            }
            if (deck.unreadHints > 0) {
                NewsLink(
                    if (deck.unreadHints ==
                        1
                    ) {
                        "1 neuer Hinweis"
                    } else {
                        "${deck.unreadHints} neue Hinweise"
                    },
                    Icons.Outlined.Notifications,
                    LiveRoute.Inbox
                )
            }
            deck.openVote?.let { NewsLink("Abstimmung: ${it.title}", Icons.Outlined.CheckBox, AccountRoute.Votes) }
            if (deck.unreadChats + deck.friendRequests + deck.unreadHints == 0 && deck.openVote == null) {
                Text(
                    "Alles gelesen – nichts wartet auf dich.",
                    style = OasisType.Body,
                    color = OasisColors.OnNightMuted,
                    modifier = Modifier.fillMaxWidth().background(
                        OasisColors.NightLine,
                        RoundedCornerShape(OasisRadius.TILE.dp)
                    ).padding(14.dp)
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OasisButton(
                "Zur Quelle",
                {
                    navigator.popToRoot(MainTab.Source)
                    navigator.selectedTab = MainTab.Source
                },
                kind = OasisButtonKind.Accent,
                fullWidth = false
            )
            OnNightButton("Oasen entdecken", { navigator.open(NetworkRoute.Oases) }, kind = OnNightKind.Outline)
        }
    }
}

@Composable
private fun NewsLink(title: String, icon: ImageVector, route: AppRoute, accent: Boolean = false) {
    val navigator = LocalNavigator.current
    val color = if (accent) OasisColors.SunInk else OasisColors.OnNight
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .background(if (accent) OasisColors.Sun else OasisColors.NightLine, RoundedCornerShape(OasisRadius.TILE.dp))
            .pressable({ navigator.open(route) })
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(title, style = OasisType.BodyBold, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun chapterIcon(key: String): ImageVector = when (key) {
    "freunde" -> Icons.Outlined.People
    "oasen" -> Icons.Outlined.Place
    "besprochen" -> Icons.AutoMirrored.Outlined.Reply
    else -> Icons.Outlined.Visibility
}
