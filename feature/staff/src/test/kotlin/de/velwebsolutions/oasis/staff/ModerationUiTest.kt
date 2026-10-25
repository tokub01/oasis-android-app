package de.velwebsolutions.oasis.staff

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.design.navigation.StaffRoute
import de.velwebsolutions.oasis.staff.api.ModerationCaseDetail
import de.velwebsolutions.oasis.staff.api.ModerationOverview
import de.velwebsolutions.oasis.staff.api.QueueFilter
import de.velwebsolutions.oasis.staff.api.StatementOfReasons
import de.velwebsolutions.oasis.staff.data.StaffFiles
import de.velwebsolutions.oasis.staff.ui.moderation.CaseDetailActions
import de.velwebsolutions.oasis.staff.ui.moderation.CaseDetailContent
import de.velwebsolutions.oasis.staff.ui.moderation.CaseDetailScreen
import de.velwebsolutions.oasis.staff.ui.moderation.CaseDetailUiState
import de.velwebsolutions.oasis.staff.ui.moderation.CaseDetailViewModel
import de.velwebsolutions.oasis.staff.ui.moderation.ComplaintReviewContent
import de.velwebsolutions.oasis.staff.ui.moderation.ComplaintReviewScreen
import de.velwebsolutions.oasis.staff.ui.moderation.ComplaintReviewUiState
import de.velwebsolutions.oasis.staff.ui.moderation.ComplaintReviewViewModel
import de.velwebsolutions.oasis.staff.ui.moderation.ModerationOverviewContent
import de.velwebsolutions.oasis.staff.ui.moderation.ModerationOverviewScreen
import de.velwebsolutions.oasis.staff.ui.moderation.ModerationOverviewUiState
import de.velwebsolutions.oasis.staff.ui.moderation.ModerationOverviewViewModel
import de.velwebsolutions.oasis.staff.ui.moderation.RolesScreen
import de.velwebsolutions.oasis.staff.ui.moderation.RolesViewModel
import de.velwebsolutions.oasis.staff.ui.moderation.SuspensionsContent
import de.velwebsolutions.oasis.staff.ui.moderation.SuspensionsScreen
import de.velwebsolutions.oasis.staff.ui.moderation.SuspensionsUiState
import de.velwebsolutions.oasis.staff.ui.moderation.SuspensionsViewModel
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The moderation screens in each of their states, with a Roborazzi screenshot of each. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ModerationUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val ui = StaffUiHarness(compose)
    private val repository = FakeStaffRepository()
    private val files = StaffFiles(ui.context.cacheDir)
    private val statement = StatementOfReasons("no_action", "community_rules", "Regel 1", "Tatsachen", "Begründung")

    @Test
    fun `the start page with waiting locks, figures, areas, queue and activity`() {
        val overview = Decoded.overview.copy(
            waitingLocks = ModerationOverview.WaitingLocks(count = 2, oldestHours = 50, state = "overdue", reviewHours = 48)
        )
        val filters = mutableListOf<QueueFilter>()
        var state by mutableStateOf(ModerationOverviewUiState(Loadable.Loaded(overview), Decoded.cases.data, hasMore = true))
        var more = 0
        ui.show { ModerationOverviewContent(state, {}, { filters += it }, { more++ }) }
        compose.onNodeWithText("2 vorläufige Sperren warten auf eine Entscheidung").assertIsDisplayed()
        compose.onNodeWithText("Die älteste wartet seit 50 Std. – zugesagt sind höchstens 48 Std. Frist überschritten.").assertIsDisplayed()
        ui.shot("moderation")
        compose.onNodeWithTag("moderation.waitingLocks").performClick()
        assertEquals(StaffRoute.Suspensions, ui.top)
        compose.onNodeWithText("Ohne Zuständigkeit").performScrollTo().performClick()
        assertEquals(QueueFilter(status = "open"), filters.last())
        compose.onNodeWithTag("staff.nav.payments").performScrollTo().performClick()
        assertEquals(StaffRoute.AdminPayments, ui.top)
        compose.onNodeWithTag("queue.status").performScrollTo().performClick()
        compose.onAllNodesWithText("Offen")[0].performClick()
        compose.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextInput("drop")
        assertEquals("drop", filters.last().search)
        compose.onNode(isToggleable()).performScrollTo().performClick()
        assertTrue(filters.last().mine)
        compose.waitForIdle()
        assertTrue(more > 0)
        compose.onAllNodesWithText(
            "Fall #${overview.activity.first().caseId} · ${overview.activity.first().type.label}",
            substring = true
        )[0]
            .performScrollTo().performClick()
        assertEquals(StaffRoute.ModerationCase(overview.activity.first().caseId), ui.top)
        ui.shot("moderation-queue")

        state = ModerationOverviewUiState(
            Loadable.Loaded(overview.copy(waitingLocks = ModerationOverview.WaitingLocks(1, 3, "soon", 48), activity = emptyList())),
            emptyList()
        )
        compose.onNodeWithText("1 vorläufige Sperre wartet auf eine Entscheidung").assertExists()
        compose.onNodeWithText("Keine Fälle für diese Auswahl. Gute Arbeit.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Noch keine Aktivität.").performScrollTo().assertIsDisplayed()
        ui.shot("moderation-empty")
    }

    @Test
    fun `the start page for a moderator without admin pages and with nothing waiting`() {
        val overview = Decoded.overview.let {
            it.copy(navigation = it.navigation.copy(canManageRoles = false, canAdministrate = false))
        }
        val viewModel = ModerationOverviewViewModel(repository.apply { this.overview = Result.success(overview) })
        ui.show { ModerationOverviewScreen(viewModel) }
        compose.onNodeWithTag("moderation.waitingLocks").assertDoesNotExist()
        compose.onNodeWithTag("staff.nav.roles").assertDoesNotExist()
        compose.onNodeWithTag("staff.nav.admin").assertDoesNotExist()
        compose.onNodeWithTag("staff.nav.suspensions").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `staff without two-factor and members without access are told why`() {
        var state by mutableStateOf(ModerationOverviewUiState(Loadable.Failed(ApiError.Forbidden("Zwei-Faktor fehlt."))))
        ui.show { ModerationOverviewContent(state, {}, {}, {}) }
        ui.shot("moderation-twofactor")
        state = ModerationOverviewUiState(Loadable.Failed(ApiError.Forbidden("Nein.")))
        compose.onNodeWithText("Kein Zugang").assertIsDisplayed()
        ui.shot("moderation-forbidden")
        state = ModerationOverviewUiState(Loadable.Failed(ApiError.Server(500, "Server Error")))
        compose.onNodeWithText("Noch einmal versuchen").assertIsDisplayed()
        state = ModerationOverviewUiState(Loadable.Loading)
        ui.shot("moderation-loading")
    }

    @Test
    fun `a reported drop with its automated check, decisions, complaints and history`() {
        val check = Decoded.caseCheck.copy(
            decisions = Decoded.caseEscalated.decisions,
            complaints = Decoded.caseDetail.complaints.ifEmpty { Decoded.caseEscalated.complaints }
        )
        var assigned = 0
        val chosen = mutableListOf<Int>()
        ui.show {
            CaseDetailContent(
                check.id,
                CaseDetailUiState(Loadable.Loaded(check), fieldErrors = mapOf("assigned_to" to "Bitte jemanden wählen.")),
                CaseDetailActions(onAssign = { assigned++ }, onSelectAssignee = { chosen += it })
            )
        }
        compose.onNodeWithText("Einschätzung").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Vorläufig ausgeblendet seit", substring = true).performScrollTo().assertIsDisplayed()
        ui.shot("case-check")
        compose.onNodeWithText("Bitte jemanden wählen.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("case.assignee").performScrollTo().performClick()
        compose.onNodeWithText(check.staff.last().displayName, substring = true).performClick()
        assertEquals(listOf(check.staff.last().id), chosen)
        compose.onNodeWithTag("case.assign").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, assigned)
        compose.onNodeWithText("Der Verlauf lässt sich nicht ändern oder löschen.").performScrollTo().assertIsDisplayed()
        ui.shot("case-history")
    }

    @Test
    fun `a chat report shows the reported plain text and the link opens in the app`() {
        val chat = Decoded.caseDetail.let {
            it.copy(
                report = it.report.copy(kind = "chat_report", plaintext = "Hallo", reason = null),
                subjectUser = null,
                subjectUrl = "http://localhost/@mira"
            )
        }
        ui.show { CaseDetailContent(chat.id, CaseDetailUiState(Loadable.Loaded(chat)), CaseDetailActions()) }
        compose.onNodeWithText("Gemeldeter Klartext").assertIsDisplayed()
        compose.onNodeWithText("Nicht zugeordnet").performScrollTo().assertIsDisplayed()
        ui.shot("case-chat")
        compose.onNodeWithText("http://localhost/@mira").performClick()
        assertNotNull(ui.top ?: ui.web.lastOrNull())
    }

    @Test
    fun `escalating asks first, and an escalated case shows its steps for admins`() {
        val escalated = mutableListOf<String>()
        var state by mutableStateOf(CaseDetailUiState(Loadable.Loaded(Decoded.caseDetail)))
        var deleted = 0
        var lifted = 0
        var archived = 0
        var reported = 0
        ui.show {
            CaseDetailContent(
                2,
                state,
                CaseDetailActions(
                    onEscalate = { escalated += it },
                    onDeleteEvidence = { deleted++ },
                    onLiftQuarantine = { lifted++ },
                    onPrepareArchive = { archived++ },
                    onRecordReport = { reported++ }
                )
            )
        }
        val option = Decoded.caseDetail.escalationOptions.first()
        compose.onNodeWithTag("case.escalate.${option.value}").performScrollTo().performClick()
        ui.shotDialog("case-escalate")
        compose.onNodeWithTag("case.escalate.confirm").performClick()
        compose.waitForIdle()
        assertEquals(listOf(option.value), escalated)
        compose.onNodeWithTag("case.escalate.${option.value}").performScrollTo().performClick()
        compose.onNodeWithText("Abbrechen").performClick()
        assertEquals(1, escalated.size)

        state = CaseDetailUiState(Loadable.Loaded(Decoded.caseEscalated), reference = "LKA", fieldErrors = mapOf("reference" to "Fehlt."))
        compose.onNodeWithTag("case.escalated").performScrollTo().assertIsDisplayed()
        ui.shot("case-escalated")
        compose.onNodeWithTag("case.recordReport").performScrollTo().performClick()
        compose.onNodeWithTag("case.evidence.download").performScrollTo().performClick()
        compose.onNodeWithText("Beweise löschen").performScrollTo().performClick()
        compose.onAllNodesWithText("Beweise löschen")[1].performClick()
        compose.onNodeWithText("Quarantäne aufheben").performScrollTo().performClick()
        compose.onAllNodesWithText("Quarantäne aufheben")[1].performClick()
        compose.waitForIdle()
        assertEquals(listOf(1, 1, 1, 1), listOf(reported, archived, deleted, lifted))

        val archive = files.evidenceArchive(4, "PK".toByteArray())
        val reportedEscalation = Decoded.caseEscalated.escalation!!.copy(
            authorityReportedAt = Instant.parse("2026-10-23T10:00:00Z"),
            authorityReference = "LKA 1/26",
            authorityContact = "Polizei",
            escalatedByName = "Mo",
            quarantined = false
        )
        state = CaseDetailUiState(Loadable.Loaded(Decoded.caseEscalated.copy(escalation = reportedEscalation)), archiveFile = archive)
        compose.onNodeWithText("Vermerkt am", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("case.evidence.share").performScrollTo().performClick()
        assertNotNull(shadowOf(ui.context).nextStartedActivity)
        compose.onNodeWithText("Quarantäne aufheben").assertDoesNotExist()

        val withoutEvidence = reportedEscalation.copy(evidence = emptyList())
        state = CaseDetailUiState(
            Loadable.Loaded(
                Decoded.caseEscalated.copy(escalation = withoutEvidence, can = Decoded.caseEscalated.can.copy(administrate = false))
            )
        )
        compose.onNodeWithText("Keine Beweise (mehr) gespeichert.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Beweise löschen").assertDoesNotExist()
    }

    @Test
    fun `a decision is confirmed with its summary before it goes out`() {
        var decided = 0
        ui.show {
            CaseDetailContent(
                2,
                CaseDetailUiState(Loadable.Loaded(Decoded.caseDetail), statement = statement),
                CaseDetailActions(onDecide = {
                    decided++
                })
            )
        }
        compose.onNodeWithTag("case.decide").performScrollTo().performClick()
        ui.shotDialog("case-decide")
        compose.onNodeWithText("Abbrechen").performClick()
        assertEquals(0, decided)
        compose.onNodeWithTag("case.decide").performScrollTo().performClick()
        compose.onNodeWithTag("case.decide.confirm").performClick()
        compose.waitForIdle()
        assertEquals(1, decided)
    }

    @Test
    fun `a measure without ground goes straight to the server`() {
        var decided = 0
        ui.show {
            CaseDetailContent(
                2,
                CaseDetailUiState(Loadable.Loaded(Decoded.caseDetail), statement = statement.copy(ground = "")),
                CaseDetailActions(onDecide = { decided++ })
            )
        }
        compose.onNodeWithTag("case.decide").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, decided)
    }

    @Test
    fun `a case for staff who may not decide or assign`() {
        val readOnly = Decoded.caseDetail.copy(can = Decoded.caseDetail.can.copy(decide = false, assign = false))
        ui.show { CaseDetailContent(2, CaseDetailUiState(Loadable.Loaded(readOnly)), CaseDetailActions()) }
        compose.onNodeWithTag("case.decide").assertDoesNotExist()
        compose.onNodeWithTag("case.assign").assertDoesNotExist()
    }

    @Test
    fun `the case screen loads its case and its complaint links`() {
        val withComplaint = Decoded.caseEscalated
        repository.case = Result.success(withComplaint)
        val viewModel = CaseDetailViewModel(4, repository, files)
        ui.show { CaseDetailScreen(4, viewModel) }
        compose.onNodeWithTag("case.status").assertIsDisplayed()
        withComplaint.complaints.firstOrNull()?.let { complaint ->
            compose.onNodeWithTag("case.complaint.${complaint.id}").performScrollTo().performClick()
            assertEquals(StaffRoute.ComplaintReview(complaint.id), ui.top)
        }
    }

    @Test
    fun `reviewing a complaint, upheld or overturned, asks before it goes out`() {
        var state by mutableStateOf(ComplaintReviewUiState(Loadable.Loaded(Decoded.complaint)))
        var reviewed = 0
        val results = mutableListOf<String>()
        ui.show {
            ComplaintReviewContent(state, {}, { results += it }, {}, {}, { reviewed++ })
        }
        ui.shot("complaint")
        compose.onNodeWithText("← Fall #${Decoded.complaint.caseId}").performClick()
        assertEquals(StaffRoute.ModerationCase(Decoded.complaint.caseId), ui.top)
        compose.onNodeWithTag("complaint.result.upheld").performScrollTo().performClick()
        assertEquals(listOf("upheld"), results)
        compose.onNodeWithTag("complaint.save").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("no result yet: straight to the server", 1, reviewed)

        state = state.copy(result = "upheld")
        compose.onNodeWithTag("complaint.save").performScrollTo().performClick()
        compose.onNodeWithText("Die Entscheidung bleibt bestehen. Die Person, die widersprochen hat, bekommt die Begründung per E-Mail.")
            .assertIsDisplayed()
        compose.onNodeWithTag("complaint.save.confirm").performClick()
        compose.waitForIdle()
        assertEquals(2, reviewed)

        state = state.copy(result = "overturned")
        compose.onNodeWithText("Neue Entscheidung").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("complaint.save").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("an overturn without measure goes straight to the server", 3, reviewed)
        state = state.copy(statement = statement.copy(ground = ""))
        compose.onNodeWithTag("complaint.save").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(4, reviewed)
        state = state.copy(statement = statement)
        compose.onNodeWithTag("complaint.save").performScrollTo().performClick()
        ui.shotDialog("complaint-overturn")
        compose.onNodeWithText("Die Entscheidung wird geändert", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Abbrechen").performClick()
        assertEquals(4, reviewed)
    }

    @Test
    fun `a reviewed complaint shows the result, someone else's waits for another reviewer`() {
        val result = de.velwebsolutions.oasis.staff.api.ComplaintDetail.Result(null, Instant.parse("2026-10-23T10:00:00Z"), null)
        var state by mutableStateOf(ComplaintReviewUiState(Loadable.Loaded(Decoded.complaint.copy(result = result))))
        ui.show { ComplaintReviewContent(state, {}, {}, {}, {}, {}) }
        compose.onNodeWithTag("complaint.result").assertIsDisplayed()
        ui.shot("complaint-result")
        state = ComplaintReviewUiState(Loadable.Loaded(Decoded.complaint.copy(canReview = false)))
        compose.onNodeWithTag("complaint.otherReviewer").performScrollTo().assertIsDisplayed()
        state = ComplaintReviewUiState(Loadable.Loaded(Decoded.complaint))
        compose.onNodeWithTag("complaint.result.upheld").assertExists()
    }

    @Test
    fun `the complaint screen loads its complaint`() {
        val viewModel = ComplaintReviewViewModel(1, repository)
        ui.show { ComplaintReviewScreen(1, viewModel) }
        compose.onNodeWithText(Decoded.complaint.reason).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `roles are changed per person, notes and refusals show at the row`() {
        val roles = Decoded.roles
        val first = roles.data.first()
        repository.roles = Result.success(
            roles.copy(
                data = roles.data.mapIndexed { index, user ->
                    if (index ==
                        1
                    ) {
                        user.copy(note = "Betreiberkonto", editable = false)
                    } else {
                        user
                    }
                },
                meta = de.velwebsolutions.oasis.core.network.Page.Meta(nextCursor = "2")
            )
        )
        val viewModel = RolesViewModel(repository)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        repository.roles = Result.success(roles.copy(data = emptyList()))
        ui.show { RolesScreen(viewModel) }
        compose.onNodeWithText("Betreiberkonto").assertIsDisplayed()
        ui.shot("roles")
        compose.onNodeWithTag("roles.picker.${first.id}").performClick()
        compose.onAllNodesWithText("Moderation")[0].performClick()
        repository.changeRole = FakeStaffRepository.invalid("role" to "Nicht erlaubt.")
        compose.onNodeWithTag("roles.save.${first.id}").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Nicht erlaubt.").assertIsDisplayed()
        assertTrue(repository.calls.any { it.startsWith("roles 2") })
    }

    @Test
    fun `locks are released after confirming, ended ones are listed`() {
        val list = Decoded.suspensions
        val lock = list.active.first()
        val ended = lock.copy(id = 9, endedAt = Instant.parse("2026-10-23T10:00:00Z"), resultLabel = null)
        val waiting = lock.copy(
            id = 5,
            deadlineState = "overdue",
            waitingHours = 50,
            caseId = null,
            canRelease = false,
            excerpt = null,
            statement = null,
            user = lock.user.copy(activeStrikes = null)
        )
        var state by mutableStateOf(SuspensionsUiState(Loadable.Loaded(list.copy(active = list.active + waiting, ended = listOf(ended)))))
        val released = mutableListOf<Int>()
        ui.show { SuspensionsContent(state, {}, { released += it.id }) }
        compose.onNodeWithText("Zuletzt beendet").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Noch keine.").performScrollTo().assertIsDisplayed()
        ui.shot("suspensions")
        compose.onNodeWithText("Fall entscheiden").performScrollTo().performClick()
        assertEquals(StaffRoute.ModerationCase(lock.caseId!!), ui.top)
        compose.onNodeWithTag("suspension.release.${lock.id}").performScrollTo().performClick()
        ui.shotDialog("suspension-release")
        compose.onNodeWithText("Abbrechen").performClick()
        assertTrue(released.isEmpty())
        state = SuspensionsUiState(Loadable.Loaded(list.copy(active = emptyList())))
        compose.onNodeWithText("Gerade ist kein Konto gesperrt.").assertIsDisplayed()
        ui.shot("suspensions-empty")
    }

    @Test
    fun `the locks screen loads the list`() {
        val viewModel = SuspensionsViewModel(repository)
        ui.show { SuspensionsScreen(viewModel) }
        compose.onNodeWithText("Es tut mir leid.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Schließen").assertDoesNotExist()
    }

    @Test
    fun `decisions and complaints of a case, and the admin steps without evidence`() {
        val decision = de.velwebsolutions.oasis.staff.api.Decision(
            outcome = Decoded.complaint.outcomes.first(),
            ground = Decoded.complaint.grounds.first(),
            isComplaintReview = true,
            decidedAt = Instant.parse("2026-10-22T13:49:00Z"),
            notifiedAt = Instant.parse("2026-10-22T14:00:00Z"),
            ruleReference = "Regel 1",
            facts = "Tatsachen",
            explanation = "Begründung"
        )
        val complaints = listOf(
            ModerationCaseDetail.ComplaintSummary(1, Decoded.complaint.status, Instant.parse("2026-10-22T15:49:00Z")),
            ModerationCaseDetail.ComplaintSummary(2, Decoded.complaint.status, Instant.parse("2026-10-23T15:49:00Z"), "Mo")
        )
        val escalation = Decoded.caseEscalated.escalation!!.copy(evidence = emptyList(), quarantined = true)
        val detail = Decoded.caseEscalated.copy(
            decisions = listOf(decision, decision.copy(isComplaintReview = false, deciderName = "Mo", notifiedAt = null)),
            complaints = complaints,
            escalation = escalation
        )
        ui.show { CaseDetailContent(4, CaseDetailUiState(Loadable.Loaded(detail)), CaseDetailActions()) }
        compose.onNodeWithText("nach Widerspruch").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("noch nicht", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("geprüft von Mo", substring = true).performScrollTo().assertIsDisplayed()
        ui.shot("case-decisions")
        compose.onNodeWithTag("case.complaint.2").performScrollTo().performClick()
        assertEquals(StaffRoute.ComplaintReview(2), ui.top)
        compose.onNodeWithText("Beweise löschen").assertDoesNotExist()
        compose.onNodeWithText("Quarantäne aufheben").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `secured text opens and closes, links open in the app or the browser`() {
        val withLink = Decoded.caseEscalated.copy(subjectUrl = "https://example.org/")
        var detail by mutableStateOf(withLink)
        ui.show { CaseDetailContent(4, CaseDetailUiState(Loadable.Loaded(detail)), CaseDetailActions()) }
        compose.onNodeWithText("https://example.org/").performClick()
        assertEquals("https://example.org/", ui.web.last())
        compose.onNodeWithText("Text anzeigen").performScrollTo().performClick()
        compose.onNodeWithText("Gemeldeter Text").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Text anzeigen").performScrollTo().performClick()
        compose.onNodeWithText("Gemeldeter Text").assertDoesNotExist()
        // A new case state with the same link: the link is not drawn again.
        detail = withLink.copy(createdAt = Instant.parse("2026-10-24T10:00:00Z"))
        compose.waitForIdle()
        detail = withLink.copy(subjectUrl = "http://localhost/app/mitgliedschaft")
        compose.onNodeWithText("http://localhost/app/mitgliedschaft").performScrollTo().performClick()
        assertEquals(de.velwebsolutions.oasis.design.navigation.MembershipRoute.Overview, ui.top)
        assertEquals(1, ui.web.size)
    }

    @Test
    fun `the figure cards answer TalkBack's click and the page refreshes by pulling`() {
        val filters = mutableListOf<QueueFilter>()
        var refreshed = 0
        ui.show {
            ModerationOverviewContent(ModerationOverviewUiState(Loadable.Loaded(Decoded.overview)), { refreshed++ }, { filters += it }, {})
        }
        compose.onNodeWithText("Dringend").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(QueueFilter(priority = "urgent"), filters.last())
        compose.onRoot().performTouchInput { swipeDown(startY = height * 0.3f, endY = height * 0.9f) }
        compose.waitForIdle()
        assertTrue(refreshed > 0)
    }

    @Test
    fun `a lock without deadline, waiting time or accusation`() {
        val lock = Decoded.suspensions.active.first()
        val bare = lock.copy(deadlineState = null, accusation = null)
        val unknownWait = lock.copy(id = 7, waitingHours = null)
        ui.show {
            SuspensionsContent(SuspensionsUiState(Loadable.Loaded(Decoded.suspensions.copy(active = listOf(bare, unknownWait)))), {}, {})
        }
        compose.onNodeWithText("Wartet seit 0 Std. von höchstens 48").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Vorwurf: ", substring = true)[0].assertExists()
    }

    @Test
    fun `the operator account confirms addresses, sends links and sets a new password`() {
        val roles = Decoded.roles
        val unconfirmed = roles.data[0].copy(accessManageable = true, emailVerified = false)
        val confirmed = roles.data[2].copy(accessManageable = true, emailVerified = true)
        repository.roles = Result.success(roles.copy(data = listOf(unconfirmed, roles.data[1], confirmed)))
        val viewModel = RolesViewModel(repository)
        ui.show { RolesScreen(viewModel) }
        compose.onNodeWithText("Als Betreiberkonto kannst du E-Mail-Adressen bestätigen", substring = true).assertIsDisplayed()
        compose.onNodeWithText("E-Mail bestätigt").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("roles.confirmEmail.${roles.data[1].id}").assertDoesNotExist()
        ui.shot("roles-access")
        compose.onNodeWithTag("roles.confirmEmail.${unconfirmed.id}").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(repository.calls.contains("confirm ${unconfirmed.id}"))
        compose.onNodeWithTag("roles.passwordLink.${unconfirmed.id}").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(repository.calls.contains("link ${unconfirmed.id}"))
        compose.onNodeWithTag("roles.setPassword.${confirmed.id}").performScrollTo().assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("roles.password.${confirmed.id}"))).performScrollTo()
            .performTextInput("ein-langes-passwort")
        // The snackbar of the last action would cover the button.
        viewModel.dismissMessage()
        compose.waitForIdle()
        compose.onNodeWithTag("roles.setPassword.${confirmed.id}").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(repository.calls.contains("password ${confirmed.id} ein-langes-passwort"))
    }

    @Test
    fun `without operator rights there is no access section`() {
        val viewModel = RolesViewModel(repository)
        ui.show { RolesScreen(viewModel) }
        compose.onNodeWithText("Als Betreiberkonto kannst du E-Mail-Adressen bestätigen", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Link für neues Passwort senden").assertDoesNotExist()
    }
}
