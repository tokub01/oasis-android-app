package de.velwebsolutions.oasis.network.ui.oases

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.core.format.GermanTime
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.ConsentCheck
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.SideCard
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.navigation.ChatRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.network.api.Meetup
import de.velwebsolutions.oasis.network.api.Member
import de.velwebsolutions.oasis.network.api.OasisMeetups
import de.velwebsolutions.oasis.network.api.OasisWelcome
import de.velwebsolutions.oasis.network.ui.components.ButtonFlow
import de.velwebsolutions.oasis.network.ui.components.ConfirmDialog
import de.velwebsolutions.oasis.network.ui.components.DateTimeField
import de.velwebsolutions.oasis.network.ui.components.Disclosure
import de.velwebsolutions.oasis.network.ui.components.MemberAvatar
import de.velwebsolutions.oasis.network.ui.components.SectionTitle
import java.time.Instant

/** „Mittwoch, 4. November 2026, 18:00 Uhr“ (`Meetup::whenText`, German time). */
fun meetupWhen(instant: Instant): String = GermanTime.format(instant, "EEEE, d. MMMM yyyy, HH:mm") + " Uhr"

/** „3 kommen · 1 vielleicht · 0 nicht“. */
fun meetupCounts(counts: Meetup.Counts): String =
    "${counts.yes} ${if (counts.yes == 1) "kommt" else "kommen"} · ${counts.maybe} vielleicht · ${counts.no} nicht"

/** What the crowd page can do with its meetups and Willkommenspaten. */
class OasisMeetupActions(
    val onOpenForm: (Meetup?) -> Unit,
    val onEditForm: ((MeetupForm) -> MeetupForm) -> Unit,
    val onCloseForm: () -> Unit,
    val onSave: suspend () -> Unit,
    val onAnswer: (Meetup, String) -> Unit,
    val onCancel: (Meetup) -> Unit,
    val onAllowMembers: (Boolean) -> Unit,
    val onVolunteer: (Boolean) -> Unit
) {
    companion object {
        val None = OasisMeetupActions({}, {}, {}, {}, { _, _ -> }, {}, {}, {})
    }
}

/** The meetup part of the crowd page: the list and its form, for members only. */
class OasisMeetupsUi(val meetups: OasisMeetups?, val form: MeetupForm?, val actions: OasisMeetupActions) {
    companion object {
        val None = OasisMeetupsUi(null, null, OasisMeetupActions.None)
    }
}

/**
 * „Treffen“: „Treffen anlegen“ for those allowed, the form, the coming meetups (or „Gerade ist kein Treffen geplant.“),
 * „Vergangene Treffen“ and – for hosts – „Mitglieder dürfen Treffen anlegen“.
 */
@Composable
internal fun MeetupsSection(meetups: OasisMeetups, form: MeetupForm?, isHost: Boolean, actions: OasisMeetupActions) {
    var showPast by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("oasis.meetups"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Treffen", Modifier.weight(1f))
            if (meetups.canCreate && form == null) {
                OasisButton(
                    "Treffen anlegen",
                    { actions.onOpenForm(null) },
                    kind = OasisButtonKind.Secondary,
                    fullWidth = false,
                    modifier = Modifier.testTag("meetup.create")
                )
            }
        }
        form?.let { MeetupFormView(it, actions) }
        if (meetups.upcoming.isEmpty()) {
            Text("Gerade ist kein Treffen geplant.", style = OasisType.Body, color = OasisColors.Muted)
        }
        meetups.upcoming.forEach { MeetupCard(it, actions) }
        if (meetups.past.isNotEmpty()) {
            Disclosure("Vergangene Treffen", showPast, {
                showPast = !showPast
            }, Modifier.testTag("meetup.past"), border = OasisColors.DuneLine) {
                meetups.past.forEach { MeetupCard(it, actions) }
            }
        }
        if (isHost) {
            ConsentCheck(
                "Mitglieder dürfen Treffen anlegen",
                meetups.membersCreateMeetups,
                actions.onAllowMembers,
                Modifier.testTag("meetup.allowMembers")
            )
        }
    }
}

/** One meetup: when, title, place, link, description, the answers with their counts and names, and its changes. */
@Composable
internal fun MeetupCard(meetup: Meetup, actions: OasisMeetupActions) {
    var showPeople by rememberSaveable(meetup.id) { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    val navigator = LocalNavigator.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(OasisColors.Paper, RoundedCornerShape(OasisRadius.M.dp))
            .padding(20.dp)
            .testTag("meetup.${meetup.id}"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val state = when {
            meetup.cancelled -> " · Abgesagt"
            meetup.past -> " · Vorbei"
            else -> ""
        }
        Text(
            meetupWhen(meetup.startsAt) + state,
            style = OasisType.SmallBold,
            color = if (meetup.cancelled) OasisColors.Danger else OasisColors.Ink2
        )
        Text(
            meetup.title,
            style = OasisType.heading(19).copy(textDecoration = if (meetup.cancelled) TextDecoration.LineThrough else TextDecoration.None),
            color = OasisColors.Night
        )
        meetup.place?.let { Text(it, style = OasisType.Body, color = OasisColors.Ink2) }
        meetup.link?.let { link ->
            Text(
                link,
                style = OasisType.Body.copy(textDecoration = TextDecoration.Underline),
                color = OasisColors.Night,
                modifier = Modifier.pressable({ navigator.openWeb(link) }).padding(vertical = 4.dp).testTag("meetup.link")
            )
        }
        meetup.description?.let { Text(it, style = OasisType.Body, color = OasisColors.Ink) }
        Text(meetupCounts(meetup.counts), style = OasisType.Body, color = OasisColors.Ink2)
        if (meetup.canAnswer) {
            ButtonFlow(Modifier.testTag("meetup.answers")) {
                Meetup.ANSWERS.forEach { (value, label) ->
                    val chosen = meetup.myAnswer == value
                    OasisButton(
                        label,
                        { actions.onAnswer(meetup, value) },
                        kind = if (chosen) OasisButtonKind.Primary else OasisButtonKind.Secondary,
                        fullWidth = false,
                        modifier = Modifier.semantics { selected = chosen }.testTag("meetup.answer.$value")
                    )
                }
            }
        }
        if (meetup.people.isNotEmpty()) {
            Disclosure("Wer geantwortet hat", showPeople, { showPeople = !showPeople }, border = OasisColors.DuneLine) {
                meetup.people.sortedBy { person -> Meetup.ANSWERS.indexOfFirst { it.first == person.answer } }.forEach { person ->
                    Text(
                        "${person.member.displayName} · ${Meetup.answerLabel(person.answer)}",
                        style = OasisType.Body,
                        color = OasisColors.Ink
                    )
                }
            }
        }
        if (meetup.canEdit) {
            ButtonFlow {
                OasisButton("Ändern", {
                    actions.onOpenForm(meetup)
                }, kind = OasisButtonKind.Secondary, fullWidth = false, modifier = Modifier.testTag("meetup.edit"))
                OasisButton("Treffen absagen", {
                    confirmCancel = true
                }, kind = OasisButtonKind.Secondary, fullWidth = false, modifier = Modifier.testTag("meetup.cancel"))
            }
        }
    }
    if (confirmCancel) {
        ConfirmDialog(
            "Treffen absagen?",
            "Absagen",
            onConfirm = { actions.onCancel(meetup) },
            onDismiss = { confirmCancel = false },
            message = "Wer zugesagt hat, bekommt einen Hinweis."
        )
    }
}

/** „Neues Treffen“ / „Treffen ändern“. */
@Composable
internal fun MeetupFormView(form: MeetupForm, actions: OasisMeetupActions) {
    val creating = form.editing == null
    Column(
        Modifier
            .fillMaxWidth()
            .background(OasisColors.Paper, RoundedCornerShape(OasisRadius.M.dp))
            .padding(20.dp)
            .testTag("meetup.form"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(if (creating) "Neues Treffen" else "Treffen ändern", style = OasisType.heading(17), color = OasisColors.Night)
        OasisTextField(
            "Titel",
            form.title,
            { value -> actions.onEditForm { it.copy(title = value) } },
            Modifier.testTag("meetup.title"),
            error = form.error?.message("title"),
            maxLength = 120
        )
        DateTimeField(
            "Datum und Uhrzeit",
            form.startsAt,
            { value -> actions.onEditForm { it.copy(startsAt = value) } },
            Modifier.testTag("meetup.startsAt"),
            error = form.error?.message("starts_at"),
            zone = GermanTime.zone,
            earliest = Instant.now()
        )
        OasisTextField(
            "Ort (optional)",
            form.place,
            { value -> actions.onEditForm { it.copy(place = value) } },
            Modifier.testTag("meetup.place"),
            error = form.error?.message("place"),
            maxLength = 200
        )
        OasisTextField(
            "Link (optional, z. B. zum Videoraum)",
            form.link,
            { value -> actions.onEditForm { it.copy(link = value) } },
            Modifier.testTag("meetup.linkField"),
            prompt = "https://",
            error = form.error?.message("link"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            maxLength = 500
        )
        OasisTextField(
            "Beschreibung (optional)",
            form.description,
            { value -> actions.onEditForm { it.copy(description = value) } },
            Modifier.testTag("meetup.description"),
            error = form.error?.message("description"),
            lines = 3..8,
            maxLength = 2000
        )
        // A refusal without a field (no right any more, the server failed) stands under the form.
        form.error?.takeIf {
            it !is ApiError.Validation
        }?.let { Text(it.userMessage, style = OasisType.Small, color = OasisColors.Danger) }
        ButtonFlow {
            AsyncOasisButton(
                if (creating) "Treffen anlegen" else "Änderung speichern",
                actions.onSave,
                fullWidth = false,
                modifier = Modifier.testTag("meetup.save")
            )
            OasisButton(
                "Abbrechen",
                actions.onCloseForm,
                kind = OasisButtonKind.Secondary,
                fullWidth = false,
                modifier = Modifier.testTag("meetup.close")
            )
        }
    }
}

/** „Deine Willkommenspatin/dein Willkommenspate: {Name}“ with „Nachricht schreiben“ (a new chat with the pate). */
@Composable
internal fun PateCard(pate: Member) {
    val navigator = LocalNavigator.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(OasisColors.Dune, RoundedCornerShape(OasisRadius.M.dp))
            .padding(20.dp)
            .testTag("oasis.pate"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MemberAvatar(pate)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Deine Willkommenspatin/dein Willkommenspate: ${pate.displayName}", style = OasisType.Body, color = OasisColors.Night)
            ButtonFlow {
                OasisButton(
                    "Nachricht schreiben",
                    { navigator.open(ChatRoute.NewConversationWith(pate.username)) },
                    kind = OasisButtonKind.Primary,
                    fullWidth = false,
                    modifier = Modifier.testTag("oasis.pate.message")
                )
                OasisButton(
                    "Profil",
                    { navigator.open(NetworkRoute.Profile(pate.username)) },
                    kind = OasisButtonKind.Secondary,
                    fullWidth = false
                )
            }
        }
    }
}

/** „Willkommenspaten“: the own switch, and for hosts the volunteers („Freiwillige“). */
@Composable
internal fun VolunteerCard(welcome: OasisWelcome, onVolunteer: (Boolean) -> Unit) {
    SideCard("Willkommenspaten") {
        Text("Neue Mitglieder sehen eine Patin oder einen Paten, bei der oder dem sie sich mit Fragen melden können.")
        ConsentCheck("Ich bin gern Willkommenspate oder -patin", welcome.volunteer, onVolunteer, Modifier.testTag("oasis.volunteer"))
        welcome.volunteers?.let { volunteers ->
            Text("Freiwillige", style = OasisType.SmallBold, color = OasisColors.Night)
            if (volunteers.isEmpty()) Text("Noch niemand.", style = OasisType.Body, color = OasisColors.Muted)
            volunteers.forEach { Text(it.displayName, style = OasisType.Body, color = OasisColors.Ink) }
        }
    }
}
