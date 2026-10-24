package de.velwebsolutions.oasis.network.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.HeartBroken
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.core.format.GermanTime
import de.velwebsolutions.oasis.core.format.RelativeTime
import de.velwebsolutions.oasis.design.components.AuthorizedImage
import de.velwebsolutions.oasis.design.components.AvatarTint
import de.velwebsolutions.oasis.design.components.Banner
import de.velwebsolutions.oasis.design.components.BannerKind
import de.velwebsolutions.oasis.design.components.DashedNote
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.network.NetworkSignals
import de.velwebsolutions.oasis.network.api.Member
import de.velwebsolutions.oasis.network.api.Post
import de.velwebsolutions.oasis.network.api.TextSegment
import de.velwebsolutions.oasis.network.ui.PostAction
import java.time.Instant

/**
 * A Tropfen card – the app's `x-network.post-card`: reply context, author line, notices for the author (held, removed
 * from the oasis), text, images, the carried Tropfen, "leise Zahlen" only for the author, the bundled conversation of
 * the Quelle, the action row and the "⋯" menu (also on a long press). Buttons a viewer may not use are not shown.
 */
@Composable
fun PostCard(
    post: Post,
    onAction: (PostAction, Post) -> Unit,
    modifier: Modifier = Modifier,
    /** On the Tropfen page itself the card does not link to itself and shows the full date. */
    isDetail: Boolean = false
) {
    val navigator = LocalNavigator.current
    var confirmation by remember { mutableStateOf<CardConfirmation?>(null) }
    var contextMenu by remember { mutableStateOf(false) }
    // Where the finger rested, so the long-press menu opens there (like Android's text and list menus).
    var pressedAt by remember { mutableStateOf(DpOffset.Zero) }
    var cardHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val viewer = post.viewer
    val shape = RoundedCornerShape(OasisRadius.M.dp)
    val reply = {
        NetworkSignals.replyFocus.value = post.id
        if (!isDetail) navigator.open(NetworkRoute.Post(post.id))
    }
    val run = { action: PostAction -> onAction(action, post) }

    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(OasisColors.Paper, shape)
                .border(1.dp, OasisColors.PaperLine, shape)
                .then(
                    if (viewer == null) {
                        Modifier
                    } else {
                        // A long press opens the menu like the iOS context menu; TalkBack offers it as an action.
                        Modifier
                            .onSizeChanged { cardHeight = with(density) { it.height.toDp() } }
                            .pointerInput(post.id) {
                                detectTapGestures(onLongPress = { offset ->
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    pressedAt = with(density) { DpOffset(offset.x.toDp(), offset.y.toDp()) }
                                    contextMenu = true
                                })
                            }
                            .semantics {
                                onLongClick("Aktionen zum Tropfen") {
                                    pressedAt = DpOffset.Zero
                                    contextMenu = true
                                    true
                                }
                            }
                    }
                )
                .padding(20.dp)
                .testTag("post.card.${post.id}"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ReplyContext(post)
            Header(post, isDetail, run, onConfirm = { confirmation = it })
            if (post.isQuestion) {
                Row(
                    Modifier.background(OasisColors.Sand, CircleShape).padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Groups, contentDescription = null, tint = OasisColors.Night, modifier = Modifier.size(16.dp))
                    Text(
                        if (viewer?.isAuthor == true) "Frage an meine Karawane" else "Frage an die Karawane",
                        style = OasisType.Caption.copy(fontWeight = FontWeight.Bold),
                        color = OasisColors.Night
                    )
                }
            }
            if (post.removedFromOasis) {
                Banner("Die Gastgeber:innen haben diesen Tropfen aus der Oase entfernt. Nur du siehst ihn noch.", kind = BannerKind.Info)
            }
            post.held?.let { HeldNotice(it) }
            RichTextView(post.segments)
            PostImages(post)
            post.carried?.let { CarriedView(it) }
            Stats(post)
            val conversation = post.conversation
            if (!isDetail && conversation != null && conversation.replies.isNotEmpty()) ConversationView(post, conversation)
            if (viewer != null) ActionRow(post, viewer, run, reply)
            if (!isDetail && viewer?.canQuickReply == true) {
                HorizontalDivider(color = OasisColors.PaperLine)
                Row(
                    Modifier
                        .defaultMinSize(minHeight = 44.dp)
                        .clickable(role = Role.Button) { navigator.present(NetworkRoute.Compose(replyTo = post.id)) },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Reply,
                        contentDescription = null,
                        tint = OasisColors.Night,
                        modifier = Modifier.size(18.dp)
                    )
                    Text("Schnell antworten", style = OasisType.SmallBold, color = OasisColors.Night)
                }
            }
        }
        if (viewer != null) {
            DropdownMenu(
                expanded = contextMenu,
                onDismissRequest = { contextMenu = false },
                // The menu's offset counts from the card's bottom edge.
                offset = DpOffset(pressedAt.x, pressedAt.y - cardHeight),
                containerColor = OasisColors.Paper
            ) {
                ContextMenuItems(post, viewer, run, reply, close = { contextMenu = false }, onConfirm = { confirmation = it })
            }
        }
    }

    confirmation?.let { asked ->
        ConfirmDialog(
            title = asked.title,
            message = asked.message,
            confirmLabel = asked.button,
            onConfirm = { run(asked.action) },
            onDismiss = { confirmation = null }
        )
    }
}

/** A destructive card action that asks first, with the web's wording. */
enum class CardConfirmation(val action: PostAction, val title: String, val button: String, val message: String?) {
    Delete(PostAction.Delete, "Wirklich löschen?", "Löschen bestätigen", null),
    Block(PostAction.Block, "Wirklich blockieren?", "Blockieren bestätigen", "Ihr verliert gegenseitige Follows."),
    RemoveFromOasis(
        PostAction.RemoveFromOasis,
        "Wirklich aus der Oase entfernen?",
        "Entfernen bestätigen",
        "Danach sieht ihn nur noch die Person, die ihn geschrieben hat."
    )
}

/** "Noch hat niemand geantwortet" / "1 Mensch hat geantwortet" / "3 Menschen haben geantwortet". */
fun answeredLabel(replies: Int): String = when (replies) {
    0 -> "Noch hat niemand geantwortet"
    1 -> "1 Mensch hat geantwortet"
    else -> "$replies Menschen haben geantwortet"
}

/** "22. Oktober 2026, 14:05". */
fun fullDate(instant: Instant): String = GermanTime.format(instant, "d. MMMM yyyy, HH:mm")

private fun plural(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"

@Composable
private fun ReplyContext(post: Post) {
    val navigator = LocalNavigator.current
    val reply = post.replyTo
    if (reply != null) {
        Text(
            buildAnnotatedString {
                append("Antwort an ")
                withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append("@${reply.username}") }
            },
            style = OasisType.Small,
            color = OasisColors.Muted,
            modifier = Modifier
                .defaultMinSize(minHeight = 44.dp)
                .clickable(role = Role.Button) { navigator.open(NetworkRoute.Post(reply.id)) }
                .padding(vertical = 12.dp)
        )
    } else if (post.replyUnavailable) {
        Text("Antwort auf einen Tropfen, der nicht mehr verfügbar ist", style = OasisType.Small, color = OasisColors.Muted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(post: Post, isDetail: Boolean, run: (PostAction) -> Unit, onConfirm: (CardConfirmation) -> Unit) {
    val navigator = LocalNavigator.current
    val openProfile = { navigator.open(NetworkRoute.Profile(post.author.username)) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        MemberAvatar(post.author, Modifier.clip(CircleShape).clickable(onClick = openProfile).clearAndSetSemantics {})
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                post.author.displayName,
                style = OasisType.BodyBold,
                color = OasisColors.Ink,
                modifier = Modifier.clickable(role = Role.Button, onClick = openProfile)
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val meta = OasisType.Small
                Text("@${post.author.username}", style = meta, color = OasisColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                post.oasis?.let { oasis ->
                    Text(
                        buildAnnotatedString {
                            append("· in ")
                            withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(oasis.name) }
                        },
                        style = meta,
                        color = OasisColors.Muted,
                        maxLines = 1,
                        modifier = Modifier.clickable(role = Role.Button) { navigator.open(NetworkRoute.Oasis(oasis.slug)) }
                    )
                }
                if (isDetail) {
                    Text("· ${fullDate(post.createdAt)}", style = meta, color = OasisColors.Muted)
                } else {
                    val relative = RelativeTime.format(post.createdAt)
                    Text(
                        buildAnnotatedString {
                            append("· ")
                            withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(relative) }
                        },
                        style = meta,
                        color = OasisColors.Muted,
                        modifier = Modifier
                            .clickable(role = Role.Button) { navigator.open(NetworkRoute.Post(post.id)) }
                            .semantics { contentDescription = "Zum Tropfen, $relative" }
                            .testTag("post.open")
                    )
                }
                if (post.isMembersOnly) Text("· nur Mitglieder", style = meta, color = OasisColors.Muted)
                if (post.editedAt != null) Text("· bearbeitet", style = meta, color = OasisColors.Muted)
            }
        }
        val viewer = post.viewer
        if (viewer != null) PostMenu(post, viewer, run, onConfirm)
    }
}

/** The "⋯" menu: host removal, edit and delete for the author; mute, restrict, block and report for everybody else. */
@Composable
private fun PostMenu(post: Post, viewer: Post.Viewer, run: (PostAction) -> Unit, onConfirm: (CardConfirmation) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button) { open = true }
                .semantics { contentDescription = "Weitere Aktionen zum Tropfen von ${post.author.displayName}" }
                .testTag("post.menu"),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.MoreHoriz, contentDescription = null, tint = OasisColors.Night)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = OasisColors.Paper) {
            MenuItems(post, viewer, run, close = { open = false }, onConfirm = onConfirm)
        }
    }
}

/** The "⋯" entries, shared by the menu button and the long-press menu. */
@Composable
private fun MenuItems(
    post: Post,
    viewer: Post.Viewer,
    run: (PostAction) -> Unit,
    close: () -> Unit,
    onConfirm: (CardConfirmation) -> Unit
) {
    val navigator = LocalNavigator.current
    val entries = buildList {
        if (viewer.canRemoveFromOasis) {
            add(
                MenuEntry("Aus Oase entfernen …", destructive = true) {
                    onConfirm(CardConfirmation.RemoveFromOasis)
                }
            )
        }
        if (viewer.canEdit) add(MenuEntry("Bearbeiten") { navigator.open(NetworkRoute.EditPost(post.id)) })
        if (viewer.canDelete) {
            add(MenuEntry("Löschen …", destructive = true) { onConfirm(CardConfirmation.Delete) })
        } else {
            if (!viewer.isAuthor) {
                add(
                    MenuEntry(if (viewer.hasMutedAuthor) "Nicht mehr stumm" else "Stummschalten") {
                        run(if (viewer.hasMutedAuthor) PostAction.Unmute else PostAction.Mute)
                    }
                )
                add(
                    MenuEntry(if (viewer.hasRestrictedAuthor) "Nicht mehr einschränken" else "Einschränken") {
                        run(if (viewer.hasRestrictedAuthor) PostAction.Unrestrict else PostAction.Restrict)
                    }
                )
                add(MenuEntry("Blockieren …", destructive = true) { onConfirm(CardConfirmation.Block) })
            }
            add(MenuEntry("Melden") { navigator.open(AccountRoute.Report(post.url)) })
        }
    }
    entries.forEach { entry ->
        DropdownMenuItem(
            text = { Text(entry.title, style = OasisType.Body, color = if (entry.destructive) OasisColors.SunText else OasisColors.Ink) },
            onClick = {
                close()
                entry.action()
            }
        )
    }
}

private class MenuEntry(val title: String, val destructive: Boolean = false, val action: () -> Unit)

/** Long press on a card: the action row's actions with icons, sharing, then the "⋯" entries. */
@Composable
private fun ContextMenuItems(
    post: Post,
    viewer: Post.Viewer,
    run: (PostAction) -> Unit,
    reply: () -> Unit,
    close: () -> Unit,
    onConfirm: (CardConfirmation) -> Unit
) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current

    @Composable
    fun item(title: String, icon: ImageVector, action: () -> Unit) {
        DropdownMenuItem(
            text = { Text(title, style = OasisType.Body, color = OasisColors.Ink) },
            leadingIcon = { Icon(icon, contentDescription = null, tint = OasisColors.Night) },
            onClick = {
                close()
                action()
            }
        )
    }
    if (viewer.canInteract && viewer.canReply) item("Antworten", Icons.AutoMirrored.Outlined.Reply, reply)
    if (viewer.canInteract &&
        viewer.canCarry
    ) {
        item("Weitertragen", Icons.Outlined.Repeat) { navigator.open(NetworkRoute.CarryPost(post.id)) }
    }
    if (viewer.canInteract) {
        item(
            if (viewer.reacted) "Reaktion zurücknehmen" else "Reagieren",
            if (viewer.reacted) Icons.Outlined.HeartBroken else Icons.Outlined.FavoriteBorder
        ) {
            run(if (viewer.reacted) PostAction.Unreact else PostAction.React)
        }
    }
    item(
        if (viewer.bookmarked) "Aus Merkliste entfernen" else "Merken",
        if (viewer.bookmarked) Icons.Outlined.BookmarkRemove else Icons.Outlined.BookmarkBorder
    ) {
        run(if (viewer.bookmarked) PostAction.Unbookmark else PostAction.Bookmark)
    }
    item("Teilen", Icons.Outlined.Share) { share(context, post.url) }
    HorizontalDivider(color = OasisColors.PaperLine)
    MenuItems(post, viewer, run, close, onConfirm)
}

@Composable
private fun ActionRow(post: Post, viewer: Post.Viewer, run: (PostAction) -> Unit, reply: () -> Unit) {
    val navigator = LocalNavigator.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (viewer.canInteract) {
            if (viewer.canReply) {
                ActionTile(
                    "Antworten",
                    Icons.AutoMirrored.Outlined.Reply,
                    OasisColors.Night,
                    "post.action.Antworten",
                    onClick = reply
                )
            }
            if (viewer.canCarry) {
                ActionTile("Weitertragen", Icons.Outlined.Repeat, OasisColors.Night, "post.action.Weitertragen") {
                    navigator.open(NetworkRoute.CarryPost(post.id))
                }
            }
            ActionTile(
                if (viewer.reacted) "Reagiert" else "Reagieren",
                if (viewer.reacted) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                if (viewer.reacted) OasisColors.SunText else OasisColors.Night,
                "post.react",
                isOn = viewer.reacted
            ) { run(if (viewer.reacted) PostAction.Unreact else PostAction.React) }
        }
        ActionTile(
            if (viewer.bookmarked) "Gemerkt" else "Merken",
            if (viewer.bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
            if (viewer.bookmarked) OasisColors.SpringText else OasisColors.Night,
            "post.bookmark",
            isOn = viewer.bookmarked,
            label = if (viewer.bookmarked) "Aus Merkliste entfernen" else "Tropfen merken"
        ) { run(if (viewer.bookmarked) PostAction.Unbookmark else PostAction.Bookmark) }
    }
}

/** An icon-over-label tile of the action row. */
@Composable
private fun RowScope.ActionTile(
    title: String,
    icon: ImageVector,
    tint: Color,
    tag: String,
    isOn: Boolean = false,
    label: String? = null,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .weight(1f)
            .defaultMinSize(minHeight = 48.dp)
            .pressable(onClick)
            .testTag(tag)
            .clearAndSetSemantics {
                contentDescription = label ?: title
                role = Role.Button
                selected = isOn
                onClick {
                    onClick()
                    true
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(title, style = OasisType.Caption, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Stats(post: Post) {
    val stats = post.stats ?: return
    val reactions = plural(stats.reactions, "Reaktion", "Reaktionen")
    val shape = RoundedCornerShape(OasisRadius.TILE.dp)
    if (post.isQuestion && post.viewer?.isAuthor == true) {
        Row(
            Modifier.fillMaxWidth().background(OasisColors.Sand, shape).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.Reply,
                contentDescription = null,
                tint = OasisColors.SpringText,
                modifier = Modifier.size(18.dp)
            )
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(answeredLabel(stats.replies)) }
                    withStyle(OasisType.Caption.toSpanStyle()) { append(" (nur du siehst diese Zahlen · $reactions)") }
                },
                style = OasisType.Small,
                color = OasisColors.Night
            )
        }
    } else {
        Row(
            Modifier.fillMaxWidth().background(OasisColors.Sand, shape).padding(horizontal = 16.dp, vertical = 10.dp).testTag("post.stats"),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Outlined.VisibilityOff, contentDescription = null, tint = OasisColors.Night, modifier = Modifier.size(18.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Nur du siehst diese Zahlen: ") }
                    append("$reactions, ${plural(stats.replies, "Antwort", "Antworten")}")
                },
                style = OasisType.Small,
                color = OasisColors.Ink
            )
        }
    }
}

/** "Zurückgehalten und dem Moderationsteam gemeldet" for the author of a held Tropfen. */
@Composable
private fun HeldNotice(held: Post.Held) {
    val shape = RoundedCornerShape(OasisRadius.TILE.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(OasisColors.Dune, shape)
            .border(1.dp, OasisColors.Sun.copy(alpha = 0.4f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Zurückgehalten und dem Moderationsteam gemeldet", style = OasisType.SmallBold, color = OasisColors.Night)
        Text(
            "Unsere automatische Prüfung hat in diesem Tropfen möglicherweise ${held.reason} erkannt. Bis ein Mensch aus dem " +
                "Moderationsteam ihn geprüft hat, siehst nur du ihn – in der Regel innerhalb von ${held.reviewHours} Stunden.",
            style = OasisType.Small,
            color = OasisColors.Ink
        )
        held.checkReason?.let { Text("Begründung der Prüfung: „$it“", style = OasisType.Small, color = OasisColors.Ink2) }
        Text(
            "Ist alles in Ordnung, wird er für alle sichtbar. Du kannst ihn jederzeit selbst löschen.",
            style = OasisType.Small,
            color = OasisColors.Ink2
        )
    }
}

/**
 * The images of a card; covered images load only after "Bild anzeigen", locked ones never. A tap opens the image full
 * screen.
 */
@Composable
private fun PostImages(post: Post) {
    var revealed by rememberSaveable(post.id) { mutableStateOf(setOf<Int>()) }
    var viewing by remember { mutableStateOf<Post.Image?>(null) }
    if (post.imagesLocked) {
        DashedNote { Text("Bilder gesperrt.", style = OasisType.Small, color = OasisColors.Ink2) }
        return
    }
    if (post.images.isEmpty()) return
    val shape = RoundedCornerShape(OasisRadius.TILE.dp)
    val columns = if (post.images.size > 1) 2 else 1
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        post.images.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { image ->
                    val cell = Modifier.weight(1f).aspectRatio(image.aspectRatio)
                    if (image.covered && image.id !in revealed) {
                        CoveredImage(image, cell) { revealed = revealed + image.id }
                    } else {
                        Box(
                            cell
                                .clip(shape)
                                .background(OasisColors.Dune)
                                .pressable({ viewing = image })
                                .semantics {
                                    contentDescription = image.alt
                                    role = Role.Image
                                    onClick("Bild vergrößern") {
                                        viewing = image
                                        true
                                    }
                                }
                        ) {
                            AuthorizedImage(
                                image.url,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(image.aspectRatio)
                            )
                        }
                    }
                }
                if (row.size < columns) Box(Modifier.weight(1f))
            }
        }
    }
    viewing?.let { start ->
        ImageViewer(post.images.filter { !it.covered || it.id in revealed }, start) { viewing = null }
    }
}

/** An image of a person whose images the viewer uncovers on request. */
@Composable
private fun CoveredImage(image: Post.Image, modifier: Modifier, reveal: () -> Unit) {
    val navigator = LocalNavigator.current
    Column(
        modifier.background(OasisColors.Dune, RoundedCornerShape(OasisRadius.TILE.dp)).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
    ) {
        Icon(Icons.Outlined.VisibilityOff, contentDescription = null, tint = OasisColors.Night)
        OasisButton(
            "Bild anzeigen",
            onClick = reveal,
            fullWidth = false,
            modifier = Modifier.semantics { contentDescription = "Bild anzeigen: ${image.alt}" }
        )
        UnderlinedLink("Einstellung ändern", { navigator.open(AccountRoute.Privacy) }, style = OasisType.Caption, color = OasisColors.Ink2)
    }
}

/** The carried Tropfen, or its placeholder. */
@Composable
private fun CarriedView(carried: Post.Carried) {
    when (carried.state) {
        Post.Carried.State.Muted -> DashedNote {
            Text("Tropfen einer stummgeschalteten Person.", style = OasisType.Small, color = OasisColors.Muted)
        }

        Post.Carried.State.Unavailable -> DashedNote {
            Text("Dieser Tropfen ist nicht mehr verfügbar.", style = OasisType.Small, color = OasisColors.Muted)
        }

        Post.Carried.State.Visible -> carried.post?.let { CarriedOriginal(it.author, it.segments, it.id) }
    }
}

/**
 * A carried Tropfen in its box: author and text – in a card shortened and linking to the original, on the carry page in
 * full.
 */
@Composable
fun CarriedOriginal(author: Member, segments: List<TextSegment>, postId: Int? = null) {
    val navigator = LocalNavigator.current
    val shape = RoundedCornerShape(OasisRadius.TILE.dp)
    Column(
        Modifier.fillMaxWidth().background(OasisColors.Paper, shape).border(1.dp, OasisColors.DuneLine, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val line = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = OasisColors.Night)) { append(author.displayName) }
            withStyle(SpanStyle(color = OasisColors.Muted)) { append(" @${author.username}") }
        }
        Text(
            line,
            style = OasisType.Small,
            modifier = if (postId != null) {
                Modifier.defaultMinSize(minHeight = 44.dp).clickable(role = Role.Button) {
                    navigator.open(NetworkRoute.Post(postId))
                }.padding(vertical = 12.dp)
            } else {
                Modifier
            }
        )
        RichTextView(segments, maxLines = if (postId == null) Int.MAX_VALUE else 4)
    }
}

/** The short conversation under a Quelle card. */
@Composable
private fun ConversationView(post: Post, conversation: Post.Conversation) {
    val navigator = LocalNavigator.current
    val shape = RoundedCornerShape(OasisRadius.TILE.dp)
    Column(
        Modifier.fillMaxWidth().background(
            OasisColors.Sand.copy(alpha = 0.4f),
            shape
        ).border(1.dp, OasisColors.DuneLine, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                conversation.title.uppercase(),
                style = OasisType.Caption.copy(fontWeight = FontWeight.Bold, letterSpacing = OasisType.Caption.letterSpacing),
                color = OasisColors.Muted,
                modifier = Modifier.weight(1f)
            )
            if (conversation.hasMore) {
                Text(
                    "weitere Antworten",
                    style = OasisType.Caption.copy(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline),
                    color = OasisColors.Night,
                    modifier = Modifier.clickable(role = Role.Button) { navigator.open(NetworkRoute.Post(post.id)) }
                )
            }
        }
        conversation.replies.forEach { reply ->
            val openProfile = { navigator.open(NetworkRoute.Profile(reply.author.username)) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                MemberAvatar(
                    reply.author,
                    Modifier.clip(CircleShape).clickable(onClick = openProfile).clearAndSetSemantics {
                    },
                    size = 32.dp
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = OasisColors.Ink)) { append(reply.author.displayName) }
                            withStyle(OasisType.Caption.toSpanStyle().copy(color = OasisColors.Muted)) {
                                append(" @${reply.author.username}")
                            }
                        },
                        style = OasisType.Small,
                        modifier = Modifier.clickable(role = Role.Button, onClick = openProfile)
                    )
                    RichTextView(reply.segments, style = OasisType.Small, color = OasisColors.Ink2)
                }
            }
        }
    }
}

/** The colour dot of an oasis next to its name (side card "In der Oase"). */
@Composable
fun OasisDot(color: String, modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp).background(AvatarTint.surface(color), CircleShape))
}
