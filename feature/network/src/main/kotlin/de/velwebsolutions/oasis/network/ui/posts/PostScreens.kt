package de.velwebsolutions.oasis.network.ui.posts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.core.format.GermanTime
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Banner
import de.velwebsolutions.oasis.design.components.DashedNote
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextButton
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.components.PanelTone
import de.velwebsolutions.oasis.design.components.SideCard
import de.velwebsolutions.oasis.design.components.SkeletonLayout
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.network.NetworkSignals
import de.velwebsolutions.oasis.network.api.Post
import de.velwebsolutions.oasis.network.api.PostDetail
import de.velwebsolutions.oasis.network.api.PostEditForm
import de.velwebsolutions.oasis.network.ui.PostAction
import de.velwebsolutions.oasis.network.ui.components.CarriedOriginal
import de.velwebsolutions.oasis.network.ui.components.ConfirmDialog
import de.velwebsolutions.oasis.network.ui.components.Disclosure
import de.velwebsolutions.oasis.network.ui.components.LeaveGuard
import de.velwebsolutions.oasis.network.ui.components.MemberRow
import de.velwebsolutions.oasis.network.ui.components.NetworkPage
import de.velwebsolutions.oasis.network.ui.components.OasisDot
import de.velwebsolutions.oasis.network.ui.components.PaperBox
import de.velwebsolutions.oasis.network.ui.components.PostCard
import de.velwebsolutions.oasis.network.ui.components.SectionTitle
import de.velwebsolutions.oasis.network.ui.components.TextLink
import de.velwebsolutions.oasis.network.ui.components.block
import de.velwebsolutions.oasis.network.ui.components.fullDate
import de.velwebsolutions.oasis.network.ui.components.share
import de.velwebsolutions.oasis.network.ui.compose.ComposeUiState
import de.velwebsolutions.oasis.network.ui.compose.ComposeViewModel
import de.velwebsolutions.oasis.network.ui.compose.ComposerCallbacks
import de.velwebsolutions.oasis.network.ui.compose.ComposerForm
import de.velwebsolutions.oasis.network.ui.compose.callbacks

/**
 * The composer as a sheet: a new Tropfen in the Quelle or an oasis, or a (quick) reply. While something is written or
 * picked, "Schließen" and the back gesture ask before discarding the draft; the sheet closes itself after publishing.
 */
@Composable
fun ComposeScreen(
    oasisSlug: String?,
    replyTo: Int?,
    viewModel: ComposeViewModel = hiltViewModel<ComposeViewModel, ComposeViewModel.Factory>(
        key = "compose:$oasisSlug:$replyTo",
        creationCallback = { it.create(oasisSlug, replyTo, null) }
    )
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    var confirmDiscard by remember { mutableStateOf(false) }
    val close: () -> Unit = { if (navigator.sheet != null) navigator.dismissSheet() else navigator.back() }

    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }

    // A draft keeps the sheet from being swiped away; "Schließen" and back ask first.
    DisposableEffect(state.hasDraft) {
        navigator.sheetDismissible = !state.hasDraft
        onDispose { navigator.sheetDismissible = true }
    }

    LeaveGuard(enabled = state.hasDraft, onAttempt = { confirmDiscard = true }) {
        ComposePage(state, viewModel, onClose = { if (state.hasDraft) confirmDiscard = true else close() }, onPublished = close)
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = "Entwurf verwerfen?",
            message = "Dein Text und deine Bilder gehen verloren.",
            confirmLabel = "Verwerfen",
            onConfirm = {
                viewModel.discard()
                close()
            },
            onDismiss = { confirmDiscard = false }
        )
    }
}

@Composable
private fun ComposePage(state: ComposeUiState, viewModel: ComposeViewModel, onClose: () -> Unit, onPublished: () -> Unit) {
    val navigator = LocalNavigator.current
    OasisScreen(
        title = if (state.isReply) "Antworten" else "Tropfen schreiben",
        closesItself = true,
        navigationAction = {
            if (navigator.sheet != null) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.testTag("compose.close")
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = "Schließen", tint = OasisColors.Night)
                }
            }
        }
    ) {
        LoadableView(state.context, onRetry = viewModel::load) {
            ComposeContent(state, viewModel.callbacks(), onPublished = { onPublished() })
        }
    }
}

/** The composer page: the form on paper, with the text field focused. */
@Composable
fun ComposeContent(state: ComposeUiState, callbacks: ComposerCallbacks, onPublished: () -> Unit) {
    var focus by rememberSaveable { mutableStateOf(true) }
    NetworkPage {
        block {
            PaperBox {
                ComposerForm(state, callbacks, focusRequest = focus, onFocusHandled = { focus = false }, onPublished = { onPublished() })
            }
        }
    }
}

/**
 * "Weitertragen" (/tropfen/{id}/weitertragen): the original above a composer for own words; links need "Ich habe den
 * Inhalt gelesen". Afterwards the Quelle shows the new Tropfen.
 */
@Composable
fun CarryScreen(
    id: Int,
    viewModel: CarryViewModel = hiltViewModel<CarryViewModel, CarryViewModel.Factory>(creationCallback = { it.create(id) }),
    composer: ComposeViewModel =
        hiltViewModel<ComposeViewModel, ComposeViewModel.Factory>(key = "carry:$id", creationCallback = { it.create(null, null, id) })
) {
    val source by viewModel.source.collectAsStateWithLifecycle()
    val state by composer.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    LaunchedEffect(Unit) {
        viewModel.load()
        composer.loadIfNeeded()
    }
    OasisScreen(title = "Weitertragen") {
        LoadableView(source, onRetry = {
            viewModel.load()
            composer.loadIfNeeded()
        }) { original ->
            NetworkPage {
                block { PageHeader("Weitertragen", lead = "Schreib ein paar eigene Worte dazu: Warum ist das wichtig?") }
                block { CarriedOriginal(original.author, original.segments) }
                block {
                    PaperBox {
                        ComposerForm(state, composer.callbacks(), onPublished = {
                            navigator.popToRoot(MainTab.Source)
                            navigator.selectedTab = MainTab.Source
                        })
                    }
                }
            }
        }
    }
}

/**
 * "Tropfen bearbeiten" (/tropfen/{id}/bearbeiten): the text within the edit window; images and audience stay. A changed
 * text is only left through "Änderung speichern" or "Abbrechen", which asks first.
 */
@Composable
fun EditPostScreen(
    id: Int,
    viewModel: EditPostViewModel = hiltViewModel<EditPostViewModel, EditPostViewModel.Factory>(creationCallback = { it.create(id) })
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val text by viewModel.text.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    var confirmDiscard by remember { mutableStateOf(false) }
    val changed = form.valueOrNull?.let { it.body != text } ?: false

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(saved) { if (saved) navigator.back() }

    LeaveGuard(enabled = changed, onAttempt = { confirmDiscard = true }) {
        EditPostPage(viewModel, form, text, error, onCancel = { if (changed) confirmDiscard = true else navigator.back() })
    }
    if (confirmDiscard) {
        ConfirmDialog("Änderung verwerfen?", "Verwerfen", onConfirm = { navigator.back() }, onDismiss = { confirmDiscard = false })
    }
}

@Composable
private fun EditPostPage(viewModel: EditPostViewModel, form: Loadable<PostEditForm>, text: String, error: ApiError?, onCancel: () -> Unit) {
    OasisScreen(title = "Bearbeiten") {
        LoadableView(form, onRetry = viewModel::load) { loaded ->
            NetworkPage {
                block {
                    PageHeader(
                        "Tropfen bearbeiten",
                        eyebrow = "Tropfen",
                        lead = "Du kannst den Text bis ${GermanTime.format(
                            loaded.until,
                            "HH:mm"
                        )} Uhr ändern. Bilder und Sichtbarkeit bleiben, wie sie sind."
                    )
                }
                block {
                    PaperBox {
                        val failure = error
                        if (failure != null && failure.message("body") == null) Banner(failure.userMessage)
                        OasisTextField(
                            "Dein Tropfen",
                            text,
                            viewModel::updateText,
                            error = failure?.message("body"),
                            lines = 6..12,
                            modifier = Modifier.testTag("edit.body")
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AsyncOasisButton(
                                "Änderung speichern",
                                viewModel::save,
                                fullWidth = false,
                                modifier = Modifier.testTag("edit.save")
                            )
                            OasisButton(
                                "Abbrechen",
                                onCancel,
                                kind = OasisButtonKind.Secondary,
                                fullWidth = false
                            )
                        }
                    }
                }
                block {
                    SideCard("Offen für alle", tone = PanelTone.Dune) {
                        Text(
                            "Wer deinen Tropfen lesen darf, sieht, dass er bearbeitet wurde, und kann frühere Fassungen aufklappen. So " +
                                "bleibt nachvollziehbar, worauf andere geantwortet haben."
                        )
                    }
                }
            }
        }
    }
}

/**
 * One Tropfen with its replies (/tropfen/{id}): earlier versions ("Frühere Fassungen"), the reply form where the author
 * allows replies, the replies oldest first, and the side cards "Geschrieben von", "In der Oase", "Leise Zahlen". Opened
 * with "Antworten" from a card, the reply field is focused (the web jumps to #antworten).
 */
@Composable
fun PostDetailScreen(
    id: Int,
    viewModel: PostDetailViewModel = hiltViewModel<PostDetailViewModel, PostDetailViewModel.Factory>(creationCallback = { it.create(id) }),
    reply: ComposeViewModel =
        hiltViewModel<ComposeViewModel, ComposeViewModel.Factory>(key = "reply:$id", creationCallback = { it.create(null, id, null) })
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val replyState by reply.state.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val leave by viewModel.leave.collectAsStateWithLifecycle()
    val replyFocus by NetworkSignals.replyFocus.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    var focusReply by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(replyFocus) {
        if (replyFocus == id) {
            NetworkSignals.replyFocus.value = null
            focusReply = true
        }
    }
    LaunchedEffect(leave) { if (leave) navigator.back() }

    OasisScreen(
        title = "Tropfen",
        message = status,
        error = error?.userMessage,
        onDismissMessage = viewModel::dismissStatus,
        onDismissError = viewModel::dismissError,
        actions = {
            detail.valueOrNull?.post?.let { post ->
                IconButton(onClick = { share(context, post.url) }) { Icon(Icons.Outlined.Share, contentDescription = "Teilen") }
            }
        }
    ) {
        LoadableView(detail, onRetry = viewModel::load, skeleton = SkeletonLayout.Cards) { loaded ->
            PostDetailContent(
                detail = loaded,
                reply = replyState,
                replyCallbacks = reply.callbacks(),
                onAction = { action, post -> viewModel.launch { viewModel.perform(action, post) } },
                onRefresh = viewModel::load,
                onLoadReply = reply::loadIfNeeded,
                focusReply = focusReply,
                onFocusHandled = { focusReply = false }
            )
        }
    }
}

/** The Tropfen page's content. */
@Composable
fun PostDetailContent(
    detail: PostDetail,
    reply: ComposeUiState,
    replyCallbacks: ComposerCallbacks,
    onAction: (PostAction, Post) -> Unit,
    onRefresh: suspend () -> Unit,
    onLoadReply: suspend () -> Unit,
    focusReply: Boolean = false,
    onFocusHandled: () -> Unit = {}
) {
    val navigator = LocalNavigator.current
    var showVersions by rememberSaveable { mutableStateOf(false) }
    val post = detail.post
    NetworkPage(onRefresh = onRefresh) {
        block("post") { PostCard(post, onAction = onAction, isDetail = true) }
        if (post.editedAt != null && detail.revisions.isNotEmpty()) {
            block("versions") {
                Disclosure(
                    "Frühere Fassungen (${detail.revisions.size})",
                    showVersions,
                    { showVersions = !showVersions },
                    Modifier.testTag("post.versions")
                ) {
                    detail.revisions.forEach { revision ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            HorizontalDivider(color = OasisColors.PaperLine)
                            Text("Gültig bis ${fullDate(revision.replacedAt)} Uhr", style = OasisType.Small, color = OasisColors.Muted)
                            Text(revision.body, style = OasisType.Body, color = OasisColors.Ink2)
                        }
                    }
                }
            }
        }
        block("replies.title") { SectionTitle("Antworten") }
        val oasis = post.oasis
        if (post.viewer?.canWriteReply == true) {
            block("reply.form") {
                LaunchedEffect(Unit) { onLoadReply() }
                PaperBox {
                    ComposerForm(reply, replyCallbacks, showsImages = false, focusRequest = focusReply, onFocusHandled = onFocusHandled)
                }
            }
        } else if (post.viewer?.canReply == true && oasis != null) {
            // Replies in an oasis land in it, so only its members write them: say so instead of a missing form.
            block("reply.membership") {
                DashedNote(
                    Modifier
                        .clickable(role = Role.Button) { navigator.open(NetworkRoute.Oasis(oasis.slug)) }
                        .testTag("post.replyNeedsMembership")
                ) {
                    Text(
                        buildAnnotatedString {
                            append("Antworten kann hier, wer der Oase folgt. ")
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)) {
                                append("Zu „${oasis.name}“")
                            }
                        },
                        style = OasisType.Body,
                        color = OasisColors.Ink2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        if (detail.replies.isEmpty()) block("replies.empty") { DashedNote("Noch keine Antworten.") }
        detail.replies.forEach { item ->
            block("reply.${item.id}", contentType = "post") { PostCard(item, onAction = onAction) }
        }
        block("side.author") {
            SideCard("Geschrieben von") {
                MemberRow(post.author, size = 52.dp)
                detail.authorBio?.let { Text(it) }
                TextLink("Zum Profil", NetworkRoute.Profile(post.author.username))
            }
        }
        if (oasis != null) {
            block("side.oasis") {
                SideCard("In der Oase") {
                    Row(
                        Modifier.clickable(role = Role.Button) { navigator.open(NetworkRoute.Oasis(oasis.slug)) }.padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OasisDot(oasis.color)
                        Text(oasis.name, style = OasisType.BodyBold, color = OasisColors.Night)
                    }
                    detail.oasisDescription?.let { Text(it) }
                }
            }
        }
        block("side.quiet") {
            SideCard("Leise Zahlen", tone = PanelTone.Dune) {
                Text("Wie viele reagiert haben, sieht nur, wer den Tropfen geschrieben hat. So zählt, was gesagt wird – nicht, wie laut.")
            }
        }
    }
}
