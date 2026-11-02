package de.velwebsolutions.oasis.network.ui.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.core.format.GermanTime
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Bullets
import de.velwebsolutions.oasis.design.components.DashedNote
import de.velwebsolutions.oasis.design.components.Eyebrow
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.components.PanelTone
import de.velwebsolutions.oasis.design.components.SideCard
import de.velwebsolutions.oasis.design.components.TierLoadableView
import de.velwebsolutions.oasis.design.components.TierLockedView
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.navigation.WebRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.network.api.Choice
import de.velwebsolutions.oasis.network.api.TimeCapsule
import de.velwebsolutions.oasis.network.api.TimeCapsules
import de.velwebsolutions.oasis.network.api.Yearbook
import de.velwebsolutions.oasis.network.ui.components.ButtonFlow
import de.velwebsolutions.oasis.network.ui.components.ChoiceMenu
import de.velwebsolutions.oasis.network.ui.components.ConfirmDialog
import de.velwebsolutions.oasis.network.ui.components.DateTimeField
import de.velwebsolutions.oasis.network.ui.components.ImageGrid
import de.velwebsolutions.oasis.network.ui.components.NetworkPage
import de.velwebsolutions.oasis.network.ui.components.PaperBox
import de.velwebsolutions.oasis.network.ui.components.SectionTitle
import de.velwebsolutions.oasis.network.ui.components.UnderlinedLink
import de.velwebsolutions.oasis.network.ui.components.block
import de.velwebsolutions.oasis.network.ui.components.capsuleTime
import java.time.Instant
import java.time.ZoneId

/** The text of a locked tier page when an old server sends no message of its own. */
private const val FROM_OASE = "Das gibt es ab der Mitgliedschaft Oase."

/** The question before a capsule is opened early or deleted. */
private enum class CapsuleQuestion { Open, Delete }

/**
 * Zeitkapseln (/app/zeitkapseln, from Oase): the form that seals a Tropfen for a later day, the waiting capsules with
 * "Jetzt öffnen" and "Löschen", and the opened ones. Below Oase the server's message and the way to the membership.
 */
@Composable
fun TimeCapsulesScreen(viewModel: TimeCapsulesViewModel = hiltViewModel<TimeCapsulesViewModel>()) {
    val capsules by viewModel.capsules.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val fields = error?.fieldMessages.orEmpty()
    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }
    OasisScreen(
        title = "Zeitkapseln",
        message = status,
        // Field errors stand below their field; everything else is a toast.
        error = error?.takeIf { "body" !in fields && "opens_at" !in fields }?.userMessage,
        onDismissMessage = viewModel::dismissStatus,
        onDismissError = viewModel::dismissError
    ) {
        LoadableView(capsules, onRetry = viewModel::load) { info ->
            if (!info.available) {
                TierLockedView(
                    "Zeitkapseln",
                    info.lockedMessage ?: FROM_OASE,
                    feature = "Schreib heute einen Tropfen, der erst an einem Tag deiner Wahl erscheint: zum Geburtstag, zum " +
                        "Jahrestag oder für dein künftiges Ich. Bis dahin sieht ihn niemand außer dir."
                )
            } else {
                NetworkPage(onRefresh = viewModel::load) {
                    block("header") {
                        PageHeader(
                            "Zeitkapseln",
                            eyebrow = "Für später",
                            lead =
                                "Schreib heute, was erst an einem bestimmten Tag erscheinen soll: zum Geburtstag, zum Jahrestag oder für " +
                                    "dein künftiges Ich. Bis dahin sieht die Zeitkapsel niemand außer dir."
                        )
                    }
                    block("form") { CapsuleForm(info, draft, fields, viewModel) }
                    block("waiting") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionTitle("Warten noch")
                            if (info.waiting.isEmpty()) DashedNote("Noch keine Zeitkapsel versiegelt.")
                            info.waiting.forEach { capsule -> WaitingCapsule(capsule, viewModel) }
                        }
                    }
                    if (info.past.isNotEmpty()) {
                        block("past") {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                SectionTitle("Aufgegangen")
                                info.past.forEach { capsule -> PastCapsule(capsule, viewModel) }
                            }
                        }
                    }
                    block("side") {
                        SideCard("So funktioniert's", tone = PanelTone.Dune) {
                            Bullets(
                                listOf(
                                    "Frühestens in einer Stunde, spätestens in ${info.maxYears} Jahren.",
                                    "Bis zu ${info.maxWaiting} Zeitkapseln können gleichzeitig warten.",
                                    "Beim Aufgehen prüfen wir sie wie jeden Tropfen. Läuft deine Mitgliedschaft dann nicht, bleibt sie zu."
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CapsuleForm(info: TimeCapsules, draft: CapsuleDraft, fields: Map<String, String>, viewModel: TimeCapsulesViewModel) {
    val places = listOf(Choice("", "in deiner Quelle")) + info.oases.map { Choice("${it.id}", "in der Oase ${it.name}") }
    PaperBox(spacing = 16) {
        OasisTextField(
            "Dein Tropfen",
            draft.body,
            viewModel::updateBody,
            lines = 4..10,
            error = fields["body"],
            hint = "${draft.body.codePointCount(0, draft.body.length)} / ${info.maxLength}",
            modifier = Modifier.testTag("capsule.body")
        )
        val now = Instant.now()
        DateTimeField(
            "Geht auf am",
            draft.opensAt,
            viewModel::updateOpensAt,
            error = fields["opens_at"],
            earliest = now,
            latest = CapsuleDraft.latestOpening(now, info.maxYears)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Erscheint", style = OasisType.SmallBold, color = OasisColors.Ink)
            ChoiceMenu(
                "Erscheint",
                places,
                draft.oasisId?.toString().orEmpty(),
                { value -> viewModel.updateOasis(value.toIntOrNull()) },
                Modifier.testTag("capsule.place")
            )
        }
        AsyncOasisButton(
            "Zeitkapsel versiegeln",
            viewModel::seal,
            enabled = draft.body.isNotBlank(),
            fullWidth = false,
            modifier = Modifier.testTag("capsule.seal")
        )
    }
}

@Composable
private fun WaitingCapsule(capsule: TimeCapsule, viewModel: TimeCapsulesViewModel) {
    var question by remember { mutableStateOf<CapsuleQuestion?>(null) }
    PaperBox(spacing = 8, modifier = Modifier.testTag("capsule.waiting.${capsule.id}")) {
        Text(
            "Geht auf am ${capsuleTime(capsule.opensAt)} ${capsule.oasis?.let { "in ${it.name}" } ?: "in deiner Quelle"}",
            style = OasisType.SmallBold,
            color = OasisColors.Ink2
        )
        Text(capsule.body, style = OasisType.Body, color = OasisColors.Ink)
        ButtonFlow {
            OasisButton("Jetzt öffnen", { question = CapsuleQuestion.Open }, kind = OasisButtonKind.Secondary, fullWidth = false)
            OasisButton("Löschen", { question = CapsuleQuestion.Delete }, kind = OasisButtonKind.Destructive, fullWidth = false)
        }
    }
    if (question == CapsuleQuestion.Open) {
        ConfirmDialog(
            "Die Zeitkapsel jetzt schon öffnen?",
            "Jetzt öffnen",
            onConfirm = { viewModel.launch { viewModel.openNow(capsule.id) } },
            onDismiss = { question = null },
            message = "Dann erscheint sie gleich als Tropfen.",
            destructive = false
        )
    }
    if (question == CapsuleQuestion.Delete) {
        ConfirmDialog(
            "Die Zeitkapsel löschen?",
            "Löschen",
            onConfirm = { viewModel.launch { viewModel.delete(capsule.id) } },
            onDismiss = { question = null },
            message = "Das lässt sich nicht rückgängig machen."
        )
    }
}

@Composable
private fun PastCapsule(capsule: TimeCapsule, viewModel: TimeCapsulesViewModel) {
    val navigator = LocalNavigator.current
    var deleting by remember { mutableStateOf(false) }
    PaperBox(spacing = 4, modifier = Modifier.testTag("capsule.past.${capsule.id}")) {
        val postId = capsule.postId
        val reason = capsule.failedReason
        if (postId != null) {
            UnderlinedLink(
                "Aufgegangen am ${GermanTime.format(capsule.openedAt ?: capsule.opensAt, "d. MMMM yyyy", ZoneId.systemDefault())}",
                { navigator.open(NetworkRoute.Post(postId)) },
                style = OasisType.BodyBold
            )
        } else if (reason != null) {
            Text("Nicht aufgegangen: $reason", style = OasisType.BodyBold, color = OasisColors.SunText)
        } else {
            // Claimed by the server, its Tropfen is being published right now (web: „Geht gerade auf …“).
            Text("Geht gerade auf …", style = OasisType.BodyBold, color = OasisColors.Ink2)
        }
        Text(capsule.body, style = OasisType.Body, color = OasisColors.Ink2, maxLines = 2)
        if (postId == null && capsule.openedAt == null) {
            // A capsule that did not open keeps its text until the author removes it.
            OasisButton(
                "Löschen",
                { deleting = true },
                kind = OasisButtonKind.Destructive,
                fullWidth = false,
                modifier = Modifier.testTag("capsule.delete.${capsule.id}")
            )
        }
    }
    if (deleting) {
        ConfirmDialog(
            "Die Zeitkapsel löschen?",
            "Löschen",
            onConfirm = { viewModel.launch { viewModel.delete(capsule.id) } },
            onDismiss = { deleting = false },
            message = "Das lässt sich nicht rückgängig machen."
        )
    }
}

/**
 * Dein Jahrbuch (/app/jahrbuch/{year?}, from Oase): the year chips, the cover with the year's totals, the months with
 * their Tropfen, and "Drucken oder als PDF", which opens the printable book on the website. Below Oase the server's
 * message and the way to the membership.
 */
@Composable
fun YearbookScreen(
    year: Int?,
    viewModel: YearbookViewModel = hiltViewModel<YearbookViewModel, YearbookViewModel.Factory>(creationCallback = { it.create(year) })
) {
    val book by viewModel.book.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }
    OasisScreen(title = "Jahrbuch", error = error?.userMessage, onDismissError = viewModel::dismissError) {
        TierLoadableView(
            book,
            "Dein Jahrbuch",
            onRetry = viewModel::load,
            feature = "Deine Tropfen und Fotos eines Jahres, gestaltet als Buch zum Durchblättern, Drucken oder als PDF zum " +
                "Aufheben. Damit bleibt, was du geteilt hast, auch ohne Bildschirm."
        ) { shown ->
            NetworkPage(onRefresh = viewModel::load) {
                block("years") {
                    ButtonFlow {
                        shown.years.forEach { option ->
                            OasisButton(
                                "$option",
                                { viewModel.launch { viewModel.show(option) } },
                                kind = if (option == shown.year) OasisButtonKind.Primary else OasisButtonKind.Secondary,
                                fullWidth = false,
                                modifier = Modifier.testTag("yearbook.year.$option").semantics { selected = option == shown.year }
                            )
                        }
                    }
                }
                block("print") {
                    OasisButton(
                        "Drucken oder als PDF",
                        { navigator.open(WebRoute(shown.printUrl)) },
                        kind = OasisButtonKind.Secondary,
                        modifier = Modifier.testTag("yearbook.print")
                    )
                }
                block("cover") { Cover(shown) }
                if (shown.months.isEmpty()) {
                    block("empty") { DashedNote("In ${shown.year} hast du noch keine Tropfen geteilt. Was du schreibst, landet hier.") }
                }
                shown.months.forEach { month ->
                    block("month.${shown.year}.${month.name}") {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(
                                month.name,
                                style = OasisType.display(28),
                                color = OasisColors.Night,
                                modifier = Modifier.semantics {
                                    heading()
                                }
                            )
                            month.posts.forEach { entry -> YearbookEntry(entry) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Cover(book: Yearbook) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(OasisColors.Night, RoundedCornerShape(OasisRadius.L.dp))
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Eyebrow("Jahrbuch", onNight = true)
        Text("${book.year}", style = OasisType.display(56), color = OasisColors.OnNight, modifier = Modifier.semantics { heading() })
        Text(yearbookTotals(book.totals), style = OasisType.Body, color = OasisColors.OnNight2, textAlign = TextAlign.Center)
    }
}

@Composable
private fun YearbookEntry(entry: Yearbook.Entry) {
    PaperBox(modifier = Modifier.testTag("yearbook.entry.${entry.id}")) {
        val day = GermanTime.format(entry.createdAt, "EEEE, d. MMMM")
        Text(listOfNotNull(day, entry.oasis?.name).joinToString(" · "), style = OasisType.SmallBold, color = OasisColors.Ink2)
        Text(entry.body, style = OasisType.Lead, color = OasisColors.Ink)
        if (entry.images.isNotEmpty()) ImageGrid(entry.images, entry.id)
        if (entry.reactions > 0 || entry.replies > 0) {
            val reactions = "${entry.reactions} ${if (entry.reactions == 1) "Reaktion" else "Reaktionen"}"
            val replies = "${entry.replies} ${if (entry.replies == 1) "Antwort" else "Antworten"}"
            Text("$reactions · $replies", style = OasisType.Small, color = OasisColors.Muted)
        }
    }
}
