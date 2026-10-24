package de.velwebsolutions.oasis.network.ui.feeds

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.design.components.Bullets
import de.velwebsolutions.oasis.design.components.DashedNote
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.components.PanelTone
import de.velwebsolutions.oasis.design.components.SideCard
import de.velwebsolutions.oasis.design.components.SkeletonLayout
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.network.api.DiscoverContext
import de.velwebsolutions.oasis.network.ui.PostListState
import de.velwebsolutions.oasis.network.ui.components.FeedHandlers
import de.velwebsolutions.oasis.network.ui.components.NetworkPage
import de.velwebsolutions.oasis.network.ui.components.OasisMark
import de.velwebsolutions.oasis.network.ui.components.TextLink
import de.velwebsolutions.oasis.network.ui.components.block
import de.velwebsolutions.oasis.network.ui.components.feedSection
import de.velwebsolutions.oasis.network.ui.components.handlers
import de.velwebsolutions.oasis.network.ui.components.membersLabel

/**
 * A list page around its content: card skeletons, the error with retry, then the page with pull to refresh and the
 * status toast. After a Tropfen was written the view model puts it on top (and the page scrolls up) or loads again.
 */
@Composable
fun <C> FeedScaffold(
    viewModel: FeedViewModel<C>,
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.(PostListState<C>, FeedHandlers) -> Unit
) {
    val state by viewModel.list.state.collectAsStateWithLifecycle()
    val scrollToTop by viewModel.scrollToTop.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val handlers = viewModel.list.handlers(viewModel::launch)
    LaunchedEffect(Unit) { viewModel.list.loadIfNeeded() }
    LaunchedEffect(scrollToTop) { if (scrollToTop > 0) listState.animateScrollToItem(0) }
    OasisScreen(
        title = title,
        actions = actions,
        message = state.status,
        error = state.actionError?.userMessage,
        onDismissMessage = viewModel.list::dismissStatus,
        onDismissError = viewModel.list::dismissError
    ) {
        LoadableView(state.state, onRetry = viewModel.list::reload, skeleton = SkeletonLayout.Cards) {
            FeedPage(listState, handlers) { content(state, handlers) }
        }
    }
}

/** The scrolling page of a feed with pull to refresh. */
@Composable
fun FeedPage(listState: LazyListState, handlers: FeedHandlers, bottomInset: Dp = 0.dp, content: LazyListScope.() -> Unit) {
    NetworkPage(state = listState, onRefresh = handlers.reload, bottomInset = bottomInset, content = content)
}

/** "Entdecken" (/app/entdecken): public Tropfen of public profiles, newest first, and the largest oases. No ranking. */
@Composable
fun DiscoverScreen(viewModel: DiscoverViewModel = hiltViewModel<DiscoverViewModel>()) {
    FeedScaffold(viewModel, "Entdecken") { state, handlers ->
        discoverContent(state, handlers)
    }
}

/** Entdecken's page. */
fun LazyListScope.discoverContent(state: PostListState<DiscoverContext>, handlers: FeedHandlers) {
    block("header") {
        PageHeader("Wer schon da ist", eyebrow = "Entdecken", lead = "Öffentliche Tropfen, der Reihe nach. Keine Empfehlungen nach Klicks.")
    }
    block("buttons") {
        val navigator = LocalNavigator.current
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OasisButton(
                "Suchen",
                { navigator.open(NetworkRoute.Search()) },
                kind = OasisButtonKind.Secondary,
                fullWidth = false,
                leading = {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = OasisColors.Night, modifier = Modifier.size(18.dp))
                }
            )
            OasisButton("Durchblättern", { navigator.open(NetworkRoute.Swipe) }, kind = OasisButtonKind.Accent, fullWidth = false)
        }
    }
    val oases = state.context?.oases.orEmpty()
    if (oases.isNotEmpty()) {
        block("oases") {
            val navigator = LocalNavigator.current
            LazyRow(Modifier.semantics { contentDescription = "Oasen" }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(oases, key = { it.id }) { oasis ->
                    Row(
                        Modifier
                            .background(OasisColors.Paper, CircleShape)
                            .pressable({ navigator.open(NetworkRoute.Oasis(oasis.slug)) })
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OasisMark(oasis)
                        Column {
                            Text(
                                oasis.name,
                                style = OasisType.SmallBold,
                                color = OasisColors.Night,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(oasis.membersCount?.let(::membersLabel).orEmpty(), style = OasisType.Caption, color = OasisColors.Muted)
                        }
                    }
                }
            }
        }
    }
    feedSection(state, handlers, empty = "Noch keine öffentlichen Tropfen.")
    if (state.reachedEnd || state.posts.isEmpty()) {
        block("side.how") {
            SideCard("So funktioniert Entdecken", tone = PanelTone.Dune) {
                Bullets(
                    listOf(
                        "Hier stehen nur Tropfen von Menschen, die ihr Profil öffentlich gemacht haben.",
                        "Neueste zuerst – niemand bezahlt dafür, weiter oben zu stehen.",
                        "Folge Menschen, dann landen ihre Tropfen in deiner Quelle."
                    )
                )
                TextLink("Alle Oasen", NetworkRoute.Oases)
            }
        }
    }
}

/**
 * "Gemerkt" (/app/gemerkt): the private bookmark list, newest bookmark first. A Tropfen leaves it once it is no longer
 * bookmarked.
 */
@Composable
fun BookmarksScreen(viewModel: BookmarksViewModel = hiltViewModel<BookmarksViewModel>()) {
    FeedScaffold(viewModel, "Gemerkt") { state, handlers ->
        block("header") {
            PageHeader(
                "Gemerkt",
                eyebrow = "Merkliste",
                lead = "Deine private Liste gespeicherter Tropfen. Niemand sieht, wer was gemerkt hat – auch Autor:innen nicht."
            )
        }
        feedSection(
            state,
            handlers,
            empty = "Du hast dir noch keine Tropfen gemerkt. In jeder Tropfen-Karte findest du den Knopf „Merken“."
        )
        block("side.quiet") {
            SideCard("Privat und leise", tone = PanelTone.Dune) {
                Text(
                    "Lesezeichen sind ausschließlich für dich sichtbar. Es gibt keine öffentlichen Listen oder Zähler für gemerkte Tropfen."
                )
                Text(
                    "Tropfen, die gelöscht oder aus einer Oase entfernt wurden, oder deren Autor:innen blockiert sind, verschwinden " +
                        "automatisch aus deiner Liste."
                )
            }
        }
        block("side.safety") {
            val navigator = LocalNavigator.current
            SideCard("Sicherheit") {
                Text("Verwalte blockierte, stummgeschaltete und eingeschränkte Konten.")
                OasisButton("Konten verwalten", {
                    navigator.open(AccountRoute.Safety)
                }, kind = OasisButtonKind.Secondary, fullWidth = false)
            }
        }
    }
}

/** A topic page (/themen/{tag}): every readable Tropfen with #tag, newest first. */
@Composable
fun TagScreen(
    tag: String,
    viewModel: TagViewModel = hiltViewModel<TagViewModel, TagViewModel.Factory>(creationCallback = { it.create(tag.lowercase()) })
) {
    val lowered = tag.lowercase()
    FeedScaffold(viewModel, "#$lowered") { state, handlers ->
        block("header") { PageHeader("#$lowered", eyebrow = "Thema", lead = "Alle Tropfen mit #$lowered, neueste zuerst.") }
        if (state.posts.isEmpty()) {
            block("empty") {
                val navigator = LocalNavigator.current
                DashedNote {
                    Text("Noch keine Tropfen zu #$lowered.", style = OasisType.Body, color = OasisColors.Ink2)
                    if (state.context?.canPost == true) {
                        OasisButton(
                            "Den ersten Tropfen schreiben",
                            { navigator.present(NetworkRoute.Compose()) },
                            fullWidth = false,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    } else {
                        OasisButton(
                            "Etwas anderes suchen",
                            { navigator.open(NetworkRoute.Search()) },
                            kind = OasisButtonKind.Secondary,
                            fullWidth = false,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        } else {
            feedSection(state, handlers)
        }
        block("side.topics") {
            SideCard("Themen auf Oasis", tone = PanelTone.Dune) {
                Text(
                    "Schreib #$lowered in deinen Tropfen, dann erscheint er hier. Es gibt keine „Trends“ – nur die Reihenfolge, in der geschrieben wurde."
                )
                TextLink("Zur Suche", NetworkRoute.Search())
            }
        }
    }
}
