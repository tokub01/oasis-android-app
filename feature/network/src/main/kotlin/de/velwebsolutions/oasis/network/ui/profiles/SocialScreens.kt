package de.velwebsolutions.oasis.network.ui.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.core.format.GermanTime
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Bullets
import de.velwebsolutions.oasis.design.components.DashedNote
import de.velwebsolutions.oasis.design.components.Eyebrow
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.components.Panel
import de.velwebsolutions.oasis.design.components.PanelTone
import de.velwebsolutions.oasis.design.components.SideCard
import de.velwebsolutions.oasis.design.components.TierLoadableView
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.navigation.WebRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.network.api.InvitationOverview
import de.velwebsolutions.oasis.network.api.Member
import de.velwebsolutions.oasis.network.ui.components.ConfirmDialog
import de.velwebsolutions.oasis.network.ui.components.MemberRow
import de.velwebsolutions.oasis.network.ui.components.NetworkPage
import de.velwebsolutions.oasis.network.ui.components.PaperBox
import de.velwebsolutions.oasis.network.ui.components.SectionTitle
import de.velwebsolutions.oasis.network.ui.components.StatGrid
import de.velwebsolutions.oasis.network.ui.components.UnderlinedLink
import de.velwebsolutions.oasis.network.ui.components.WeekChart
import de.velwebsolutions.oasis.network.ui.components.WeekRow
import de.velwebsolutions.oasis.network.ui.components.block
import de.velwebsolutions.oasis.network.ui.components.copyToClipboard
import de.velwebsolutions.oasis.network.ui.components.rememberBaseUrl
import de.velwebsolutions.oasis.network.ui.components.share
import de.velwebsolutions.oasis.network.ui.feeds.dayMonth
import de.velwebsolutions.oasis.network.ui.retry
import java.time.Instant

/** "Freund:innen" (/app/freunde): incoming requests (accept, decline), friends (end) and own open requests (withdraw). */
@Composable
fun FriendsScreen(viewModel: FriendsViewModel = hiltViewModel<FriendsViewModel>()) {
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    var ending by remember { mutableStateOf<Member?>(null) }
    var declining by remember { mutableStateOf<Member?>(null) }
    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }
    OasisScreen(
        title = "Freund:innen",
        message = status,
        error = error?.userMessage,
        onDismissMessage = viewModel::dismissStatus,
        onDismissError = viewModel::dismissError
    ) {
        LoadableView(lists, onRetry = viewModel.retry(viewModel::load)) { friends ->
            NetworkPage(onRefresh = viewModel::load) {
                block("header") {
                    PageHeader(
                        "Freundschaften",
                        eyebrow = "Freund:innen",
                        lead =
                            "Freundschaft und Folgen sind getrennt: Freund:innen dürfen dir schreiben und ihre Bilder siehst du sofort " +
                                "– folgen müsst ihr euch dafür nicht."
                    )
                }
                block("incoming") {
                    Panel(spacing = 14) {
                        SectionTitle("Anfragen an dich")
                        if (friends.incoming.isEmpty()) {
                            Text(
                                "Gerade keine offenen Anfragen.",
                                style = OasisType.Body,
                                color = OasisColors.Ink2
                            )
                        }
                        friends.incoming.forEach { person ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                MemberRow(person)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AsyncOasisButton(
                                        "Annehmen",
                                        { viewModel.accept(person.id) },
                                        fullWidth = false,
                                        modifier = Modifier.testTag("friends.accept.${person.username}")
                                    )
                                    OasisButton("Ablehnen", { declining = person }, kind = OasisButtonKind.Secondary, fullWidth = false)
                                }
                            }
                        }
                        Text(
                            if (friends.acceptsRequests) {
                                "Ablehnen sagt niemandem etwas. Möchtest du gar keine Anfragen bekommen? Freundschaftsanfragen ausschalten"
                            } else {
                                "Du nimmst gerade keine Freundschaftsanfragen an. Wieder einschalten"
                            },
                            style = OasisType.Small.copy(textDecoration = TextDecoration.Underline),
                            color = OasisColors.Muted,
                            modifier = Modifier.clickable(role = Role.Button) { navigator.open(AccountRoute.Privacy) }
                        )
                    }
                }
                block("friends") {
                    Panel(spacing = 14) {
                        SectionTitle("Deine Freund:innen")
                        if (friends.friends.isEmpty()) {
                            Text(
                                "Noch keine. Auf einem Profil findest du „Freundschaft anfragen“.",
                                style = OasisType.Body,
                                color = OasisColors.Ink2
                            )
                        }
                        friends.friends.forEach { person ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                MemberRow(person)
                                OasisButton("Freundschaft beenden", {
                                    ending = person
                                }, kind = OasisButtonKind.Secondary, fullWidth = false)
                            }
                        }
                    }
                }
                if (friends.outgoing.isNotEmpty()) {
                    block("outgoing") {
                        Panel(spacing = 14) {
                            SectionTitle("Von dir gesendet")
                            friends.outgoing.forEach { person ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        buildAnnotatedString {
                                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(person.displayName) }
                                            withStyle(SpanStyle(color = OasisColors.Muted)) { append(" @${person.username}") }
                                        },
                                        style = OasisType.Body,
                                        color = OasisColors.Ink,
                                        modifier = Modifier.weight(1f)
                                    )
                                    AsyncOasisButton("Zurückziehen", {
                                        viewModel.remove(person.id)
                                    }, kind = OasisButtonKind.Secondary, fullWidth = false)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    ending?.let { person ->
        ConfirmDialog(
            title = "Freundschaft mit ${person.displayName} beenden?",
            confirmLabel = "Freundschaft beenden",
            onConfirm = { viewModel.launch { viewModel.remove(person.id) } },
            onDismiss = { ending = null }
        )
    }
    declining?.let { person ->
        ConfirmDialog(
            title = "Anfrage von ${person.displayName} ablehnen?",
            message = "Ablehnen sagt niemandem etwas.",
            confirmLabel = "Ablehnen",
            onConfirm = { viewModel.launch { viewModel.remove(person.id) } },
            onDismiss = { declining = null }
        )
    }
}

/** "Deine Zahlen" (/app/statistik): counts only the member sees – never who reacted. */
@Composable
fun StatisticsScreen(viewModel: StatisticsViewModel = hiltViewModel<StatisticsViewModel>()) {
    val statistics by viewModel.statistics.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }
    OasisScreen(title = "Deine Zahlen") {
        TierLoadableView(
            statistics,
            "Deine Zahlen",
            onRetry = viewModel.retry(viewModel::load),
            feature = "Wie deine Karawane wächst, welche Tropfen ankommen und wie lebendig deine Oasen sind: leise Zahlen über " +
                "zwölf Wochen, die nur du siehst. Wer reagiert hat, zeigen sie nie."
        ) { stats ->
            NetworkPage(onRefresh = viewModel::load) {
                block("header") {
                    PageHeader(
                        "Deine Zahlen",
                        eyebrow = "Nur für dich sichtbar",
                        lead =
                            "Niemand sonst sieht diese Zahlen. Wer reagiert hat, zeigen wir auch dir nicht – es zählt, was gesagt wird. " +
                                "Deine Karawane sind die Menschen, die dir folgen."
                    )
                }
                block("tiles") {
                    StatGrid(
                        listOf(
                            stats.totals.followers to "In deiner Karawane",
                            stats.totals.following to "Du folgst",
                            stats.totals.posts to "Tropfen",
                            stats.totals.reactions to "Reaktionen erhalten",
                            stats.totals.replies to "Antworten erhalten",
                            stats.totals.carries to "Mal weitergetragen"
                        )
                    )
                }
                block("weeks") {
                    WeekChart(
                        "Die letzten 12 Wochen",
                        listOf("Neu in der Karawane", "Tropfen", "Reaktionen"),
                        stats.weekly.map { WeekRow(it.week, listOf(it.followers, it.posts, it.reactions)) }
                    )
                }
                block("top") {
                    PaperBox {
                        SectionTitle("Deine Tropfen der letzten 30 Tage mit den meisten Reaktionen")
                        if (stats.topPosts.isEmpty()) {
                            Text(
                                "In den letzten 30 Tagen hast du nichts geschrieben.",
                                style = OasisType.Body,
                                color = OasisColors.Ink2
                            )
                        }
                        stats.topPosts.forEach { post ->
                            val shape = RoundedCornerShape(OasisRadius.TILE.dp)
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, OasisColors.DuneLine, shape)
                                    .clickable(role = Role.Button) { navigator.open(NetworkRoute.Post(post.id)) }
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(post.body, style = OasisType.Body, color = OasisColors.Ink, maxLines = 2)
                                val reactions = "${post.reactions} ${if (post.reactions == 1) "Reaktion" else "Reaktionen"}"
                                val replies = "${post.replies} ${if (post.replies == 1) "Antwort" else "Antworten"}"
                                Text(
                                    "$reactions · $replies · ${dayMonth(post.createdAt)}",
                                    style = OasisType.Small,
                                    color = OasisColors.Muted
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * "Menschen einladen" (/app/einladen): personal links within the monthly budget, their state and earned free months, and
 * the group starts („Gemeinsam starten“: a named group, one shared link, the progress).
 */
@Composable
fun InvitationsScreen(viewModel: InvitationsViewModel = hiltViewModel<InvitationsViewModel>()) {
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val groupName by viewModel.groupName.collectAsStateWithLifecycle()
    val groupTarget by viewModel.groupTarget.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }
    OasisScreen(title = "Einladen", message = status, onDismissMessage = viewModel::dismissStatus) {
        LoadableView(overview, onRetry = viewModel.retry(viewModel::load)) { info ->
            NetworkPage(onRefresh = viewModel::load) {
                block("header") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Eyebrow("Gemeinsam starten")
                        Text(
                            "Menschen einladen",
                            style = OasisType.display(28),
                            color = OasisColors.Night,
                            modifier = Modifier.semantics {
                                heading()
                            }
                        )
                        Text(
                            "Mit deinem Link folgt ihr euch nach der Anmeldung automatisch. Du kannst ${info.perMonth} Links pro Monat " +
                                "erstellen; diesen Monat noch ${info.remaining}. Ein Link gilt ${info.validDays} Tage.",
                            style = OasisType.Body,
                            color = OasisColors.Ink2
                        )
                        Text(
                            "Wird jemand über deinen Link Mitglied und bleibt es über die Widerrufsfrist hinaus, bekommst du einen " +
                                "Monat deiner Mitgliedschaft geschenkt – höchstens ${info.freeMonthsPerYear} im Jahr.",
                            style = OasisType.Body,
                            color = OasisColors.Ink2
                        )
                        Text(
                            "Freimonate gelten nur für Mitgliedschaften, die über die Website (Mollie) bezahlt werden: Wir verschieben " +
                                "deine nächste Abbuchung um einen Monat. Bei einem Abo über Google Play oder den App Store wartet der " +
                                "Monat, bis eine über die Website bezahlte Mitgliedschaft läuft. Geschenk-Mitgliedschaften der " +
                                "eingeladenen Person zählen nicht; eine Auszahlung gibt es nicht.",
                            style = OasisType.Small,
                            color = OasisColors.Ink2
                        )
                        // The conditions of the free months in the AGB (invitations.blade.php).
                        rememberBaseUrl()?.resolve("/agb#freimonate")?.toString()?.let { terms ->
                            UnderlinedLink("Bedingungen", { navigator.open(WebRoute(terms)) }, Modifier.testTag("invitations.terms"))
                        }
                    }
                }
                error?.message("invitation")?.let { message -> block("error") { FieldError(message) } }
                if (info.remaining > 0 && info.canCreate) {
                    block("create") {
                        AsyncOasisButton(
                            "Neuen Einladungslink erstellen",
                            viewModel::create,
                            fullWidth = false,
                            modifier = Modifier.testTag("invitations.create")
                        )
                    }
                }
                if (info.invitations.isEmpty()) block("empty") { DashedNote("Du hast noch keine Einladungslinks erstellt.") }
                info.invitations.forEach { link -> block("link.${link.id}") { InvitationRow(link) } }
                block("groups") {
                    GroupsSection(
                        info,
                        groupName,
                        groupTarget,
                        nameError = error?.message("name"),
                        targetError = error?.message("target"),
                        onName = viewModel::setGroupName,
                        onTarget = viewModel::setGroupTarget,
                        onCreate = viewModel::createGroup
                    )
                }
                block("side.month") {
                    SideCard("Diesen Monat", tone = PanelTone.Night) {
                        Text("${info.remaining}", style = OasisType.figure(40), color = OasisColors.OnNight)
                        Text("von ${info.perMonth} Einladungslinks übrig")
                    }
                }
                block("side.free") {
                    SideCard("Deine Freimonate", tone = PanelTone.Dune) {
                        Text("${info.freeMonthsThisYear}", style = OasisType.figure(40), color = OasisColors.Night)
                        Text("von ${info.freeMonthsPerYear} in diesem Jahr verdient")
                        if (info.freeMonthsWaiting > 0) {
                            Text(
                                "${info.freeMonthsWaiting} ${if (info.freeMonthsWaiting == 1) "wartet" else "warten"} auf eine laufende, über die Website bezahlte Mitgliedschaft."
                            )
                        }
                    }
                }
                block("side.invited") {
                    SideCard("Was Eingeladene bekommen", tone = PanelTone.Dune) {
                        Bullets(
                            listOf(
                                "Dieselbe Oase wie du, mit allen Funktionen, sobald sie ihren Beitrag gewählt haben.",
                                "Ihr folgt euch nach der Anmeldung automatisch.",
                                "Keine Werbung, kein Feed, der festhält."
                            )
                        )
                    }
                }
            }
        }
    }
}

/** One invitation link: accepted (with the free month), expired, or open with copy and share. */
@Composable
private fun InvitationRow(link: InvitationOverview.Link) {
    Column(
        Modifier.fillMaxWidth().background(OasisColors.Paper, RoundedCornerShape(OasisRadius.S.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val accepted = link.acceptedAt
        val url = link.url
        when {
            accepted != null -> {
                Text("Angenommen am ${longDate(accepted)}", style = OasisType.BodyBold, color = OasisColors.SpringText)
                link.reward?.let { reward ->
                    val applied = reward.appliedAt
                    Text(
                        if (applied != null) {
                            "Freimonat gutgeschrieben am ${longDate(applied)}"
                        } else {
                            "Freimonat verdient – wir verschieben deine nächste Abbuchung über die Website um einen Monat; bei einem Abo über Google Play wartet er."
                        },
                        style = OasisType.Small,
                        color = OasisColors.Ink2
                    )
                }
            }

            link.expired -> Text("Abgelaufen", style = OasisType.Body, color = OasisColors.Muted)

            url != null -> ShareableLink("Gültig bis ${dayMonth(link.expiresAt)} – Link zum Weitergeben:", url, "Einladungslink")
        }
    }
}

/** A link to pass on (invitation or group link): the label, the URL to select, and copy and share buttons. */
@Composable
private fun ShareableLink(label: String, url: String, clipboardLabel: String) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Text(label, style = OasisType.SmallBold, color = OasisColors.Ink)
    SelectionContainer {
        Text(url, style = OasisType.Digits.copy(fontSize = OasisType.Caption.fontSize), color = OasisColors.Ink)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OasisButton(
            if (copied) "Kopiert" else "Link kopieren",
            {
                copyToClipboard(context, clipboardLabel, url)
                copied = true
            },
            fullWidth = false
        )
        OasisButton(
            "Teilen",
            { share(context, url) },
            kind = OasisButtonKind.Secondary,
            fullWidth = false,
            leading = {
                Icon(Icons.Outlined.Share, contentDescription = null, tint = OasisColors.Night, modifier = Modifier.size(18.dp))
            }
        )
    }
}

/**
 * „Gemeinsam starten“ on the invitations page (invitations.blade.php): what a group is, the founding form while the
 * member may found one, and the own groups with link, progress and who joined.
 */
@Composable
private fun GroupsSection(
    info: InvitationOverview,
    groupName: String,
    groupTarget: String,
    nameError: String?,
    targetError: String?,
    onName: (String) -> Unit,
    onTarget: (String) -> Unit,
    onCreate: suspend () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().background(OasisColors.Paper, RoundedCornerShape(OasisRadius.M.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Gemeinsam starten", style = OasisType.heading(20), color = OasisColors.Night, modifier = Modifier.semantics { heading() })
            Text(
                "Gib eurer Gruppe einen Namen und teile einen Link für alle. Wer darüber Mitglied wird, folgt dir automatisch und du " +
                    "folgst zurück; du siehst, wie viele schon dabei sind. Ein Gruppenlink gilt ${info.groupValidDays} Tage und zählt " +
                    "nicht zu deinen ${info.perMonth} Einladungslinks im Monat. Freimonate gibt es wie bei persönlichen Einladungen, " +
                    "höchstens ${info.freeMonthsPerYear} im Jahr.",
                style = OasisType.Small,
                color = OasisColors.Ink2
            )
        }
        if (info.canCreateGroup) {
            OasisTextField(
                "Name der Gruppe",
                groupName,
                onName,
                modifier = Modifier.testTag("groups.name"),
                prompt = "z. B. WG Kastanienallee",
                error = nameError,
                maxLength = GROUP_NAME_MAX
            )
            OasisTextField(
                "Wie viele? (optional)",
                groupTarget,
                onTarget,
                modifier = Modifier.testTag("groups.target"),
                prompt = "z. B. 12",
                error = targetError,
                hint = "Höchstens ${info.groupMaxTarget}.",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            AsyncOasisButton("Gruppe anlegen", onCreate, modifier = Modifier.testTag("groups.create"), fullWidth = false)
        } else if (info.groups.isNotEmpty()) {
            Text(
                "Du hast $GROUP_MAX_OPEN offene Gruppen. Eine neue kannst du anlegen, sobald eine abgelaufen ist.",
                style = OasisType.Small,
                color = OasisColors.Muted
            )
        }
        info.groups.forEach { group -> GroupRow(group) }
    }
}

/** How many open groups a member may have (InvitationGroup::MAX_PER_MEMBER); the API does not send it. */
private const val GROUP_MAX_OPEN = 3

/** The web's `maxlength` of the group name (InvitationGroup::NAME_MAX). */
private const val GROUP_NAME_MAX = 60

/** One group start: name and progress, the target as slots, who joined, and the link, „Abgelaufen“ or „Vollständig“. */
@Composable
private fun GroupRow(group: InvitationOverview.Group) {
    Column(
        Modifier.fillMaxWidth().background(OasisColors.Sand, RoundedCornerShape(OasisRadius.S.dp)).padding(20.dp)
            .testTag("group.${group.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(group.name, style = OasisType.heading(18), color = OasisColors.Night)
        Text(group.progress, style = OasisType.Small, color = OasisColors.Muted)
        group.target?.let { target ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(minOf(target, GROUP_SLOTS_SHOWN)) { slot ->
                    Box(
                        Modifier.weight(1f).height(8.dp)
                            .background(if (slot < group.joined) OasisColors.Spring else OasisColors.DuneLine, RoundedCornerShape(4.dp))
                    )
                }
            }
        }
        if (group.members.isNotEmpty()) {
            Text("Dabei: ${group.members.joinToString(", ") { "@$it" }}", style = OasisType.Small, color = OasisColors.Ink2)
        }
        val url = group.url
        when {
            group.open && url != null ->
                ShareableLink("Gültig bis ${dayMonth(group.expiresAt)} – Gruppenlink zum Weitergeben:", url, "Gruppenlink")

            group.isComplete -> Text("Vollständig – alle sind dabei.", style = OasisType.BodyBold, color = OasisColors.SpringText)

            else -> Text("Abgelaufen", style = OasisType.Body, color = OasisColors.Muted)
        }
    }
}

/** The web draws at most 24 slots of a target. */
private const val GROUP_SLOTS_SHOWN = 24

private fun longDate(instant: Instant): String = GermanTime.format(instant, "d. MMMM yyyy")

/** The page behind an invitation link (/einladung/{token}) for a signed-in member. */
@Composable
fun InvitationScreen(
    token: String,
    viewModel: InvitationViewModel =
        hiltViewModel<InvitationViewModel, InvitationViewModel.Factory>(creationCallback = { it.create(token) })
) {
    val landing by viewModel.landing.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    LaunchedEffect(Unit) { viewModel.load() }
    OasisScreen(title = "Einladung") {
        LoadableView(landing, onRetry = viewModel.retry(viewModel::load)) { info ->
            NetworkPage {
                block("card") {
                    PaperBox(padding = 24, radius = OasisRadius.L, spacing = 16) {
                        val inviter = info.inviter
                        if (inviter != null) {
                            Eyebrow("Einladung")
                            Text("${inviter.displayName} lädt dich zu Mildcrowd ein.", style = OasisType.display(26), color = OasisColors.Night)
                            Text(
                                "Mildcrowd ist ein werbefreies soziales Netzwerk mit einem Feed, der aufhört. Wenn du dich jetzt anmeldest, " +
                                    "folgt ihr euch automatisch.",
                                style = OasisType.Body,
                                color = OasisColors.Ink2
                            )
                            Text("Du bist schon angemeldet.", style = OasisType.Body, color = OasisColors.Ink)
                            UnderlinedLink("Zum Profil von ${inviter.displayName}", {
                                navigator.open(NetworkRoute.Profile(inviter.username))
                            })
                        } else {
                            Text("Dieser Einladungslink gilt nicht mehr.", style = OasisType.display(26), color = OasisColors.Night)
                            Text("Er wurde schon benutzt oder ist abgelaufen.", style = OasisType.Body, color = OasisColors.Ink2)
                        }
                    }
                }
            }
        }
    }
}

/** The page behind a group link (/gemeinsam/{token}) for a signed-in member (group-landing.blade.php). */
@Composable
fun InvitationGroupScreen(
    token: String,
    viewModel: InvitationGroupViewModel =
        hiltViewModel<InvitationGroupViewModel, InvitationGroupViewModel.Factory>(creationCallback = { it.create(token) })
) {
    val landing by viewModel.landing.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    LaunchedEffect(Unit) { viewModel.load() }
    OasisScreen(title = "Gemeinsam starten") {
        LoadableView(landing, onRetry = viewModel.retry(viewModel::load)) { info ->
            NetworkPage {
                block("card") {
                    PaperBox(padding = 24, radius = OasisRadius.L, spacing = 16) {
                        val group = info.group
                        if (group != null) {
                            Eyebrow("Gemeinsam starten")
                            Text(
                                "${group.founder.displayName} lädt dich in die Gruppe „${group.name}“ ein.",
                                style = OasisType.display(26),
                                color = OasisColors.Night
                            )
                            Text(
                                group.progress,
                                style = OasisType.Small,
                                color = OasisColors.Muted,
                                modifier = Modifier.testTag("group.progress")
                            )
                            Text(
                                "Mildcrowd ist ein werbefreies soziales Netzwerk mit einem Feed, der aufhört. Wenn du dich jetzt anmeldest, " +
                                    "folgt ihr euch automatisch; deinen Beitrag wählst du selbst.",
                                style = OasisType.Body,
                                color = OasisColors.Ink2
                            )
                            Text("Du bist schon angemeldet.", style = OasisType.Body, color = OasisColors.Ink)
                            UnderlinedLink("Zum Profil von ${group.founder.displayName}", {
                                navigator.open(NetworkRoute.Profile(group.founder.username))
                            })
                        } else {
                            Text("Dieser Gruppenlink gilt nicht mehr.", style = OasisType.display(26), color = OasisColors.Night)
                            Text(
                                "Die Gruppe ist voll oder der Link ist abgelaufen. Mitglied werden kannst du trotzdem.",
                                style = OasisType.Body,
                                color = OasisColors.Ink2
                            )
                        }
                    }
                }
            }
        }
    }
}
