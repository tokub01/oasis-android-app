package de.velwebsolutions.oasis.network.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.DashedNote
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.network.api.Post
import de.velwebsolutions.oasis.network.ui.PostAction
import de.velwebsolutions.oasis.network.ui.PostListModel
import de.velwebsolutions.oasis.network.ui.PostListState

/** What a feed can ask its list for: card actions, the next page and a new first page. */
class FeedHandlers(
    val perform: (PostAction, Post) -> Unit,
    val loadMore: () -> Unit,
    val reload: suspend () -> Unit
)

/** The handlers of a [PostListModel], run by [launch] so they outlive the card that started them. */
fun <C> PostListModel<C>.handlers(launch: (suspend () -> Unit) -> Unit) = FeedHandlers(
    perform = { action, post -> launch { perform(action, post) } },
    loadMore = { launch { loadMore() } },
    reload = { reload() }
)

/**
 * The cards of a Tropfen list with the web feed's states (`x-network.feed`): card shapes while the first page loads, an
 * empty text, older Tropfen loading while scrolling, and the honest end ("Du bist auf dem neuesten Stand." / "Zeit zum
 * Durchatmen.").
 */
fun <C> LazyListScope.feedSection(
    state: PostListState<C>,
    handlers: FeedHandlers,
    empty: String = "Hier ist noch nichts.",
    /** A closing line instead of the feed wording (search results are not a feed). */
    end: String? = null,
    keyPrefix: String = "feed",
    /** Fades the cards while a new view of the list loads (the Quelle's switch). */
    dimmed: Boolean = false
) {
    when (val first = state.state) {
        Loadable.Idle, Loadable.Loading -> {
            block("$keyPrefix.skeleton.1") { PostCardSkeleton() }
            block("$keyPrefix.skeleton.2") { PostCardSkeleton() }
        }

        is Loadable.Failed -> block("$keyPrefix.failed") {
            RetryNote(first.error.userMessage, "Noch einmal laden", handlers.reload)
        }

        is Loadable.Loaded -> if (state.posts.isEmpty()) block("$keyPrefix.empty") { DashedNote(empty) }
    }
    val lastId = state.posts.lastOrNull()?.id
    state.posts.forEach { post ->
        block("$keyPrefix.post.${post.id}", contentType = "post") {
            PostCard(post, onAction = handlers.perform, modifier = Modifier.alpha(if (dimmed) 0.5f else 1f))
            if (post.id == lastId) LaunchedEffect(post.id) { handlers.loadMore() }
        }
    }
    when {
        state.isLoadingMore -> block("$keyPrefix.more") {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), color = OasisColors.Night, strokeWidth = 2.dp)
                Text("Ältere Tropfen …", style = OasisType.Small, color = OasisColors.Muted)
            }
        }

        state.loadMoreError != null -> block("$keyPrefix.moreFailed") {
            RetryNote(state.loadMoreError.userMessage, "Ältere Tropfen") { handlers.loadMore() }
        }

        state.reachedEnd && state.posts.isNotEmpty() -> block("$keyPrefix.end") { FeedEnd(end) }
    }
}

/** An error inside a list with a button to try again. */
@Composable
fun RetryNote(message: String, title: String, retry: suspend () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, style = OasisType.Small, color = OasisColors.SunText)
        AsyncOasisButton(title, retry, kind = OasisButtonKind.Secondary, fullWidth = false)
    }
}

/** The shape of a Tropfen card while the list loads, so the page does not jump when the cards arrive. */
@Composable
fun PostCardSkeleton() {
    Column(
        Modifier
            .fillMaxWidth()
            .background(OasisColors.Paper, RoundedCornerShape(OasisRadius.M.dp))
            .padding(20.dp)
            .clearAndSetSemantics { contentDescription = "Lädt …" },
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(OasisColors.Dune, CircleShape))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Bar(130.dp, 14.dp)
                Bar(90.dp, 11.dp)
            }
        }
        Bar(null, 14.dp)
        Bar(null, 14.dp)
        Bar(180.dp, 14.dp)
    }
}

@Composable
private fun Bar(width: Dp?, height: Dp) {
    Box(
        (if (width == null) Modifier.fillMaxWidth() else Modifier.width(width))
            .height(height)
            .background(OasisColors.Dune, CircleShape)
    )
}

/** The end of a list. */
@Composable
fun FeedEnd(text: String? = null) {
    DashedNote(Modifier.testTag("feed.end"), highlighted = true) {
        if (text != null) {
            Text(text, style = OasisType.BodyBold, color = OasisColors.SpringText)
        } else {
            Text("Du bist auf dem neuesten Stand.", style = OasisType.BodyBold, color = OasisColors.SpringText)
            Text("Zeit zum Durchatmen.", style = OasisType.Body, color = OasisColors.Ink2)
        }
    }
}
