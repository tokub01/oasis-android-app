package de.velwebsolutions.oasis.network.ui.feeds

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.core.format.GermanTime
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Bullets
import de.velwebsolutions.oasis.design.components.DashedNote
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.components.Panel
import de.velwebsolutions.oasis.design.components.PanelTone
import de.velwebsolutions.oasis.design.components.Pill
import de.velwebsolutions.oasis.design.components.PillTone
import de.velwebsolutions.oasis.design.components.SideCard
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.LiveRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.network.api.Choice
import de.velwebsolutions.oasis.network.api.Idea
import de.velwebsolutions.oasis.network.api.Post
import de.velwebsolutions.oasis.network.api.WeeklyLetter
import de.velwebsolutions.oasis.network.api.WeeklyLetterSummary
import de.velwebsolutions.oasis.network.ui.PostAction
import de.velwebsolutions.oasis.network.ui.components.ButtonFlow
import de.velwebsolutions.oasis.network.ui.components.ChoiceMenu
import de.velwebsolutions.oasis.network.ui.components.ConfirmDialog
import de.velwebsolutions.oasis.network.ui.components.NetworkPage
import de.velwebsolutions.oasis.network.ui.components.PostCard
import de.velwebsolutions.oasis.network.ui.components.UnderlinedLink
import de.velwebsolutions.oasis.network.ui.components.block
import de.velwebsolutions.oasis.network.ui.retry

/** „Dein Oasen-Wochenbrief“ (/app/wochenbrief/{id?}). */
@Composable
fun WeeklyLetterScreen(
    id: Int?,
    viewModel: WeeklyLetterViewModel = hiltViewModel<WeeklyLetterViewModel, WeeklyLetterViewModel.Factory>(
        key = "letter-$id",
        creationCallback = { it.create(id) }
    )
) {
    val letter by viewModel.letter.collectAsStateWithLifecycle()
    val earlier by viewModel.earlier.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }
    OasisScreen(
        title = "Wochenbrief",
        message = status,
        error = error?.userMessage,
        onDismissMessage = viewModel::dismissStatus,
        onDismissError = viewModel::dismissError
    ) {
        LoadableView(letter, onRetry = viewModel.retry(viewModel::load)) { shown ->
            WeeklyLetterContent(
                shown,
                earlier,
                onShow = { other -> viewModel.launch { viewModel.show(other) } },
                onAction = { action, post -> viewModel.launch { viewModel.perform(action, post) } },
                onRefresh = viewModel::load
            )
        }
    }
}

/** The letter (or the note that none came yet) and „Frühere Wochenbriefe“. */
@Composable
internal fun WeeklyLetterContent(
    letter: WeeklyLetter?,
    earlier: List<WeeklyLetterSummary>,
    onShow: (Int) -> Unit,
    onAction: (PostAction, Post) -> Unit,
    onRefresh: suspend () -> Unit
) {
    val navigator = LocalNavigator.current
    NetworkPage(onRefresh = onRefresh) {
        block("header") {
            PageHeader(
                "Dein Crowd-Wochenbrief",
                eyebrow = "Einmal in der Woche",
                lead = letter?.let {
                    letterWeek(it.weekStart, it.weekEnd)
                }
            )
        }
        if (letter == null) {
            block("none") { DashedNote("Noch kein Wochenbrief. Er kommt sonntags am Abend, wenn in deinen Crowds etwas los war.") }
        } else if (letter.oases.isEmpty()) {
            block("gone") { Text("Was in diesem Wochenbrief stand, ist nicht mehr da.", style = OasisType.Body, color = OasisColors.Ink2) }
        }
        letter?.let { shown ->
            shown.oases.forEach { entry -> block("oasis.${shown.id}.${entry.oasis.slug}") { LetterOasis(entry, onAction) } }
        }
        block("earlier") {
            SideCard("Frühere Wochenbriefe") {
                if (earlier.isEmpty()) Text("Noch keine.", style = OasisType.Body, color = OasisColors.Ink2)
                earlier.forEach { item ->
                    val current = item.id == letter?.id
                    Text(
                        "Woche vom ${letterDay(item.weekStart, "d. MMMM yyyy")}",
                        style = if (current) OasisType.BodyBold else OasisType.Body,
                        color = OasisColors.Night,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 44.dp)
                            .semantics { selected = current }
                            .clickable(role = Role.Button) { onShow(item.id) }
                            .padding(vertical = 10.dp)
                            .testTag("letter.${item.id}")
                    )
                }
                UnderlinedLink("Wochenbrief per E-Mail einstellen", { navigator.open(LiveRoute.Settings) })
            }
        }
    }
}

/** One oasis of the letter: its name, new members, Tropfen, meetups and new collections. */
@Composable
private fun LetterOasis(entry: WeeklyLetter.Entry, onAction: (PostAction, Post) -> Unit) {
    val navigator = LocalNavigator.current
    val slug = entry.oasis.slug
    Column(Modifier.fillMaxWidth().testTag("letter.oasis.$slug"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UnderlinedLink(entry.oasis.name, { navigator.open(NetworkRoute.Oasis(slug)) }, style = OasisType.heading(21))
        if (entry.newMembers > 0) Text(newMembersText(entry.newMembers), style = OasisType.Body, color = OasisColors.Ink2)
        entry.posts.forEach { post -> PostCard(post, onAction = onAction) }
        if (entry.meetups.isNotEmpty()) {
            Panel(spacing = 4) {
                Text("Treffen in den nächsten Tagen", style = OasisType.BodyBold, color = OasisColors.Night)
                entry.meetups.forEach { meetup ->
                    UnderlinedLink(
                        "${meetup.title} · ${GermanTime.format(meetup.startsAt, "EEEE, d. MMMM yyyy, HH:mm")} Uhr",
                        { navigator.open(NetworkRoute.Oasis(slug, meetups = true)) },
                        Modifier.testTag("letter.meetup.${meetup.id}")
                    )
                }
            }
        }
        if (entry.collections.isNotEmpty()) {
            Panel(spacing = 4) {
                Text("Neue Sammlungen", style = OasisType.BodyBold, color = OasisColors.Night)
                entry.collections.forEach { collection ->
                    UnderlinedLink(
                        collection.name,
                        { navigator.open(NetworkRoute.Oasis(slug, collection = collection.id)) },
                        Modifier.testTag("letter.collection.${collection.id}")
                    )
                }
            }
        }
    }
}

/** „Ideen für Oasis“ (/app/ideen/{id?}). */
@Composable
fun IdeasScreen(
    id: Int?,
    viewModel: IdeasViewModel =
        hiltViewModel<IdeasViewModel, IdeasViewModel.Factory>(key = "ideas-$id", creationCallback = { it.create(id) })
) {
    val ideas by viewModel.ideas.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }
    OasisScreen(
        title = "Ideen",
        message = status,
        error = error?.userMessage,
        onDismissMessage = viewModel::dismissStatus,
        onDismissError = viewModel::dismissError
    ) {
        LoadableView(ideas, onRetry = viewModel.retry(viewModel::load)) { list -> IdeasPage(list.ideas, list.nextPage, viewModel) }
    }
}

@Composable
private fun IdeasPage(ideas: List<Idea>, nextPage: Int?, viewModel: IdeasViewModel) {
    val focused by viewModel.focused.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val statusFilter by viewModel.statusFilter.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<Idea?>(null) }
    val actions = IdeaActions(
        vote = { idea -> viewModel.launch { viewModel.toggleVote(idea) } },
        delete = { idea -> deleting = idea }
    )
    NetworkPage(onRefresh = viewModel::load) {
        block("header") {
            PageHeader(
                "Ideen für Mildcrowd",
                eyebrow = "Gemeinsam weiterbauen",
                lead = "Was fehlt dir? Schlag es vor oder gib einer Idee deine Stimme. Wir schauen uns die beliebtesten an und " +
                    "schreiben dazu, wie es steht."
            )
        }
        block("form") {
            val open = form
            if (open == null) {
                OasisButton("Idee vorschlagen", viewModel::openForm, Modifier.testTag("ideas.suggest"), fullWidth = false)
            } else {
                Panel(spacing = 12) {
                    OasisTextField(
                        "Titel",
                        open.title,
                        { value -> viewModel.editForm { it.copy(title = value) } },
                        Modifier.testTag("ideas.title"),
                        error = open.error?.message("title"),
                        maxLength = 100
                    )
                    OasisTextField(
                        "Beschreibung (optional)",
                        open.description,
                        { value -> viewModel.editForm { it.copy(description = value) } },
                        Modifier.testTag("ideas.description"),
                        error = open.error?.message("description"),
                        lines = 3..8,
                        maxLength = 1000
                    )
                    open.error?.takeIf { it.fieldMessages.isEmpty() }?.let {
                        Text(it.userMessage, style = OasisType.Small, color = OasisColors.Danger)
                    }
                    ButtonFlow {
                        AsyncOasisButton("Idee einreichen", viewModel::suggest, Modifier.testTag("ideas.submit"), fullWidth = false)
                        OasisButton(
                            "Abbrechen",
                            viewModel::closeForm,
                            Modifier.testTag("ideas.cancel"),
                            kind = OasisButtonKind.Secondary,
                            fullWidth = false
                        )
                    }
                }
            }
        }
        focused?.let { idea -> block("focused.${idea.id}") { IdeaCard(idea, actions) } }
        block("filters") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                ChoiceMenu(
                    "Sortieren",
                    listOf(Choice("popular", "Beliebt"), Choice("new", "Neu")),
                    sort,
                    { choice -> viewModel.launch { viewModel.choose(choice) } },
                    Modifier.testTag("ideas.sort")
                )
                ChoiceMenu(
                    "Stand",
                    listOf(Choice("", "Alle")) + Idea.statuses.map { (key, label) -> Choice(key, label) },
                    statusFilter,
                    { choice -> viewModel.launch { viewModel.filter(choice) } },
                    Modifier.testTag("ideas.status")
                )
            }
        }
        if (ideas.isEmpty()) block("empty") { DashedNote("Hier ist noch keine Idee. Mach den Anfang!") }
        ideas.forEach { idea -> block("idea.${idea.id}") { IdeaCard(idea, actions) } }
        if (nextPage != null) {
            block("more") {
                AsyncOasisButton(
                    "Weitere Ideen",
                    viewModel::loadMore,
                    Modifier.testTag("ideas.more"),
                    kind = OasisButtonKind.Secondary,
                    fullWidth = false
                )
            }
        }
        block("how") {
            SideCard("So funktioniert's", tone = PanelTone.Dune) {
                Bullets(
                    listOf(
                        "Eine Stimme pro Idee, jederzeit zurücknehmbar. Wer gestimmt hat, sieht niemand.",
                        "Wir prüfen Ideen wie Posts. Unpassendes kannst du melden.",
                        "Ändert sich der Stand deiner Idee, bekommst du einen Hinweis."
                    )
                )
            }
        }
    }
    deleting?.let { idea ->
        ConfirmDialog(
            "Deine Idee löschen?",
            "Löschen",
            onConfirm = { viewModel.launch { viewModel.delete(idea) } },
            onDismiss = { deleting = null }
        )
    }
}

/** What an idea card can do. */
internal class IdeaActions(val vote: (Idea) -> Unit, val delete: (Idea) -> Unit)

/**
 * One idea: the vote with its count, status, author and day, title, description, the answer of Oasis, the linked vote,
 * and „Löschen“ for the own open idea or „Melden“.
 */
@Composable
internal fun IdeaCard(idea: Idea, actions: IdeaActions) {
    val navigator = LocalNavigator.current
    Panel(Modifier.testTag("idea.${idea.id}"), spacing = 12) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            VoteButton(idea, actions)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(idea.statusLabel, tone = if (idea.isUnderway) PillTone.Spring else PillTone.Dune)
                }
                Text(
                    "${idea.author?.displayName ?: "Unbekannt"} · ${GermanTime.format(idea.createdAt, "d. MMMM yyyy")}",
                    style = OasisType.SmallBold,
                    color = OasisColors.Ink2
                )
                Text(idea.title, style = OasisType.CardTitle, color = OasisColors.Night)
                if (idea.hidden) {
                    Text(
                        "Ausgeblendet nach einer Meldung. Nur du siehst diese Idee.",
                        style = OasisType.SmallBold,
                        color = OasisColors.Danger
                    )
                }
            }
        }
        idea.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = OasisType.Body, color = OasisColors.Ink) }
        idea.statusReply?.takeIf { it.isNotBlank() }?.let { reply ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(OasisColors.Dune, RoundedCornerShape(OasisRadius.TILE.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("Antwort von Mildcrowd", style = OasisType.SmallBold, color = OasisColors.Night)
                Text(reply, style = OasisType.Body, color = OasisColors.Ink)
            }
        }
        if (idea.canDelete) {
            OasisButton(
                "Löschen",
                { actions.delete(idea) },
                Modifier.testTag("idea.${idea.id}.delete"),
                kind = OasisButtonKind.Secondary,
                fullWidth = false
            )
        } else if (!idea.mine) {
            UnderlinedLink(
                "Melden",
                { navigator.open(AccountRoute.Report(idea.reportUrl)) },
                Modifier.testTag("idea.${idea.id}.report"),
                color = OasisColors.SpringText
            )
        }
    }
}

/** The upvote with its count; taken back with a second tap, closed for done and declined ideas. */
@Composable
private fun VoteButton(idea: Idea, actions: IdeaActions) {
    val shape = RoundedCornerShape(OasisRadius.CHIP.dp)
    val foreground = if (idea.voted) OasisColors.OnNight else OasisColors.Night
    Column(
        Modifier
            .size(52.dp)
            .background(if (idea.voted) OasisColors.Night else Color.Transparent, shape)
            .border(2.dp, if (idea.voted) OasisColors.Night else OasisColors.DuneLine, shape)
            .clickable(enabled = idea.canVote || idea.voted, role = Role.Button) { actions.vote(idea) }
            .semantics {
                contentDescription = if (idea.voted) "Stimme zurücknehmen" else "Für diese Idee stimmen"
                stateDescription = "${idea.votes} Stimmen"
                selected = idea.voted
            }
            .testTag("idea.${idea.id}.vote"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.ArrowDropUp, contentDescription = null, tint = foreground, modifier = Modifier.size(20.dp))
        Text("${idea.votes}", style = OasisType.BodyBold.copy(fontWeight = FontWeight.Bold), color = foreground)
    }
}
