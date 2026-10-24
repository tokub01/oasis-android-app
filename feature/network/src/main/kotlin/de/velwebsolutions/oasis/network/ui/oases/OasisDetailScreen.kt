package de.velwebsolutions.oasis.network.ui.oases

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Bullets
import de.velwebsolutions.oasis.design.components.Eyebrow
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.PanelTone
import de.velwebsolutions.oasis.design.components.SideCard
import de.velwebsolutions.oasis.design.components.SkeletonLayout
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.ChatRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.network.api.NoContext
import de.velwebsolutions.oasis.network.api.OasisDetail
import de.velwebsolutions.oasis.network.api.OasisMembers
import de.velwebsolutions.oasis.network.ui.PostListState
import de.velwebsolutions.oasis.network.ui.components.ButtonFlow
import de.velwebsolutions.oasis.network.ui.components.ConfirmDialog
import de.velwebsolutions.oasis.network.ui.components.Disclosure
import de.velwebsolutions.oasis.network.ui.components.FeedHandlers
import de.velwebsolutions.oasis.network.ui.components.MemberRow
import de.velwebsolutions.oasis.network.ui.components.NetworkPage
import de.velwebsolutions.oasis.network.ui.components.OnNightButton
import de.velwebsolutions.oasis.network.ui.components.OnNightKind
import de.velwebsolutions.oasis.network.ui.components.SectionTitle
import de.velwebsolutions.oasis.network.ui.components.UnderlinedLink
import de.velwebsolutions.oasis.network.ui.components.block
import de.velwebsolutions.oasis.network.ui.components.feedSection
import de.velwebsolutions.oasis.network.ui.components.handlers
import de.velwebsolutions.oasis.network.ui.components.membersLabel
import de.velwebsolutions.oasis.network.ui.feeds.ComposeEntry
import kotlinx.coroutines.delay

/**
 * One oasis (/oasen/{slug}): banner, description, following, house rules, the composer for members, the Tropfen, hosts,
 * the member list for members and the host tools.
 */
@Composable
fun OasisDetailScreen(slug: String) {
    val viewModel = hiltViewModel<OasisViewModel, OasisViewModel.Factory>(creationCallback = { it.create(slug) })
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val posts by viewModel.posts.state.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val memberQuery by viewModel.memberQuery.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    var confirmUnfollow by remember { mutableStateOf(false) }
    val loaded = detail.valueOrNull
    val compose = { oasis: OasisDetail -> navigator.present(NetworkRoute.Compose(oasisSlug = oasis.oasis.slug)) }

    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }
    LaunchedEffect(memberQuery) {
        if (viewModel.members.value != null) {
            delay(300)
            viewModel.loadMembers()
        }
    }

    OasisScreen(
        title = loaded?.oasis?.name ?: "Oase",
        message = status ?: posts.status,
        error = (error ?: posts.actionError)?.userMessage,
        onDismissMessage = {
            viewModel.dismissStatus()
            viewModel.posts.dismissStatus()
        },
        onDismissError = {
            viewModel.dismissError()
            viewModel.posts.dismissError()
        },
        actions = {
            if (loaded?.canPost == true) {
                IconButton(onClick = { compose(loaded) }, modifier = Modifier.testTag("oasis.compose")) {
                    Icon(Icons.Outlined.EditNote, contentDescription = "Tropfen in ${loaded.oasis.name} schreiben")
                }
            }
        }
    ) {
        LoadableView(detail, onRetry = viewModel::load, skeleton = SkeletonLayout.Cards) { oasis ->
            val handlers = viewModel.posts.handlers(viewModel::launch)
            NetworkPage(onRefresh = viewModel::load) {
                oasisContent(
                    detail = oasis,
                    posts = posts,
                    handlers = handlers,
                    members = members,
                    memberQuery = memberQuery,
                    onMemberQuery = viewModel::updateMemberQuery,
                    onFollow = { viewModel.setFollowing(true) },
                    onUnfollow = { confirmUnfollow = true },
                    onCompose = { compose(oasis) }
                )
            }
        }
    }
    if (confirmUnfollow) {
        ConfirmDialog(
            title = "Der Oase „${loaded?.oasis?.name.orEmpty()}“ nicht mehr folgen?",
            confirmLabel = "Nicht mehr folgen",
            onConfirm = { viewModel.launch { viewModel.setFollowing(false) } },
            onDismiss = { confirmUnfollow = false }
        )
    }
}

/** The oasis page's content. */
fun LazyListScope.oasisContent(
    detail: OasisDetail,
    posts: PostListState<NoContext>,
    handlers: FeedHandlers,
    members: OasisMembers?,
    memberQuery: String,
    onMemberQuery: (String) -> Unit,
    onFollow: suspend () -> Unit,
    onUnfollow: () -> Unit,
    onCompose: () -> Unit
) {
    block("header") { Header(detail, onFollow, onUnfollow) }
    block("rules") {
        var showRules by rememberSaveable { mutableStateOf(false) }
        Disclosure("Hausregeln dieser Oase", showRules, {
            showRules = !showRules
        }, Modifier.testTag("oasis.rules"), border = OasisColors.DuneLine) {
            HouseRules(detail.houseRules)
        }
    }
    if (detail.canPost) block("compose") { ComposeEntry("Dein Tropfen in ${detail.oasis.name}", onClick = onCompose) }
    block("posts.title") { SectionTitle("Tropfen") }
    feedSection(posts, handlers, empty = "In dieser Oase gibt es noch keine Tropfen.")
    block("side.hosts") { SideCard("Gastgeber:innen") { detail.hosts.forEach { MemberRow(it) } } }
    if (detail.isMember && members != null) block("side.members") { MemberListCard(members, memberQuery, onMemberQuery) }
    if (detail.isHost) {
        block("side.host") {
            val navigator = LocalNavigator.current
            SideCard("Du bist Gastgeber:in", tone = PanelTone.Night) {
                Text("Bearbeite Texte und Rollen, oder sieh nach, wie es der Oase geht.")
                ButtonFlow {
                    OnNightButton("Bearbeiten", {
                        navigator.open(NetworkRoute.EditOasis(detail.oasis.slug))
                    }, Modifier.testTag("oasis.edit"))
                    OnNightButton(
                        "Statistik",
                        { navigator.open(NetworkRoute.OasisStatistics(detail.oasis.slug)) },
                        Modifier.testTag("oasis.statistics"),
                        kind = OnNightKind.Outline
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(detail: OasisDetail, onFollow: suspend () -> Unit, onUnfollow: () -> Unit) {
    val navigator = LocalNavigator.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(OasisRadius.M.dp)).background(OasisColors.Paper)) {
        OasisBanner(detail.oasis, height = if (detail.oasis.bannerUrl == null) 128.dp else 120.dp)
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Eyebrow("Oase")
                Text(
                    detail.oasis.name,
                    style = OasisType.display(28),
                    color = OasisColors.Night,
                    modifier = Modifier.semantics {
                        heading()
                    }
                )
            }
            Text(detail.oasis.description, style = OasisType.Body, color = OasisColors.Ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.People, contentDescription = null, tint = OasisColors.Ink2, modifier = Modifier.size(20.dp))
                Text(membersLabel(detail.oasis.membersCount ?: 0), style = OasisType.Body, color = OasisColors.Ink2)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (detail.isMember) {
                    OasisButton(
                        "Gefolgt ✓",
                        onUnfollow,
                        fullWidth = false,
                        modifier = Modifier.semantics { contentDescription = "Gefolgt ✓, Nicht mehr folgen" }.testTag("oasis.unfollow")
                    )
                } else if (detail.canFollow) {
                    AsyncOasisButton(
                        "Folgen",
                        onFollow,
                        kind = OasisButtonKind.Accent,
                        fullWidth = false,
                        modifier = Modifier.testTag("oasis.follow")
                    )
                }
                if (!detail.isHost) {
                    UnderlinedLink("Melden", {
                        navigator.open(AccountRoute.Report(detail.url))
                    }, style = OasisType.Small, color = OasisColors.SpringText)
                }
            }
        }
    }
}

/** House rules one per line plus the hint to the Community-Regeln (`partials/house-rules`). */
@Composable
fun HouseRules(rules: List<String>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (rules.size > 1) {
            Bullets(rules)
        } else {
            rules.firstOrNull()?.let { Text(it, style = OasisType.Body, color = OasisColors.Ink2) }
        }
        Text("Zusätzlich gelten die Community-Regeln von Oasis.", style = OasisType.Caption, color = OasisColors.Muted)
    }
}

/** The member list for members of the oasis, with "Schreiben" where the person accepts messages. */
@Composable
private fun MemberListCard(members: OasisMembers, query: String, onQuery: (String) -> Unit) {
    val navigator = LocalNavigator.current
    SideCard("Mitglieder") {
        val shape = RoundedCornerShape(OasisRadius.XS.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 44.dp)
                .background(OasisColors.Field, shape)
                .border(1.dp, OasisColors.FieldLine, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (query.isEmpty()) Text("Namen suchen …", style = OasisType.Body, color = OasisColors.Muted)
            BasicTextField(
                query,
                onQuery,
                singleLine = true,
                textStyle = OasisType.Body.copy(color = OasisColors.Ink),
                cursorBrush = SolidColor(OasisColors.Night),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Mitglieder nach Namen suchen" }
            )
        }
        if (members.members.isEmpty()) {
            Text(if (query.isBlank()) "Außer dir ist hier noch niemand." else "Niemand mit diesem Namen.", color = OasisColors.Muted)
        }
        members.members.forEach { entry ->
            MemberRow(entry.member, size = 36.dp) {
                if (entry.canWrite) {
                    OasisButton(
                        "Schreiben",
                        { navigator.open(ChatRoute.NewConversationWith(entry.username)) },
                        kind = OasisButtonKind.Secondary,
                        fullWidth = false,
                        modifier = Modifier.semantics { contentDescription = "${entry.displayName} schreiben" }
                    )
                }
            }
        }
        if (members.hiddenMembers > 0) {
            val who = if (members.hiddenMembers ==
                1
            ) {
                "Ein weiteres Mitglied zeigt sich"
            } else {
                "${members.hiddenMembers} weitere Mitglieder zeigen sich"
            }
            Text("$who hier nur Freund:innen und Menschen, denen sie folgen.")
        }
        Text(
            "„Schreiben“ erscheint, wenn die Person den Chat eingerichtet hat und Nachrichten von dir annimmt.",
            style = OasisType.Caption,
            color = OasisColors.Muted
        )
        members.ownListAudience?.let { audience ->
            Column(
                Modifier.fillMaxWidth().background(OasisColors.Dune, RoundedCornerShape(OasisRadius.CHIP.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    if (audience == "nobody") {
                        "Du bist in Mitgliederlisten für niemanden sichtbar."
                    } else {
                        "Zu deinem eigenen Schutz ist aktuell eingestellt, dass dich in dieser Liste nur Freund:innen und Menschen sehen, denen du folgst."
                    },
                    style = OasisType.Caption,
                    color = OasisColors.Ink
                )
                UnderlinedLink("Umstellen", { navigator.open(AccountRoute.Privacy) }, style = OasisType.Caption)
            }
        }
    }
}
