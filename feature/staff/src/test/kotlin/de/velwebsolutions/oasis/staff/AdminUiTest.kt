package de.velwebsolutions.oasis.staff

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import de.velwebsolutions.oasis.core.network.Page
import de.velwebsolutions.oasis.design.navigation.StaffRoute
import de.velwebsolutions.oasis.staff.api.AdminDashboard
import de.velwebsolutions.oasis.staff.api.MollieCustomer
import de.velwebsolutions.oasis.staff.api.MollieLookup
import de.velwebsolutions.oasis.staff.api.MolliePayment
import de.velwebsolutions.oasis.staff.api.PaymentRecord
import de.velwebsolutions.oasis.staff.api.SystemStatus
import de.velwebsolutions.oasis.staff.ui.admin.AdminDashboardScreen
import de.velwebsolutions.oasis.staff.ui.admin.AdminDashboardViewModel
import de.velwebsolutions.oasis.staff.ui.admin.AdminPaymentsScreen
import de.velwebsolutions.oasis.staff.ui.admin.AdminPaymentsViewModel
import de.velwebsolutions.oasis.staff.ui.admin.AdminVotesScreen
import de.velwebsolutions.oasis.staff.ui.admin.AdminVotesViewModel
import de.velwebsolutions.oasis.staff.ui.admin.DataBreachesScreen
import de.velwebsolutions.oasis.staff.ui.admin.DataBreachesViewModel
import de.velwebsolutions.oasis.staff.ui.admin.MollieFormat
import de.velwebsolutions.oasis.staff.ui.admin.SystemScreen
import de.velwebsolutions.oasis.staff.ui.admin.SystemViewModel
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
import org.robolectric.shadows.ShadowLooper

/** The admin screens with their view models on a fake repository, with a Roborazzi screenshot of each state. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class AdminUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val ui = StaffUiHarness(compose)
    private val repository = FakeStaffRepository()

    @Test
    fun `the statistics browse months and periods and link to the admin pages`() {
        val dashboard = Decoded.dashboard
        repository.dashboard = Result.success(
            dashboard.copy(revenue = dashboard.revenue.copy(nextMonth = "2026-11"), ai = dashboard.ai.copy(note = "Nur geschätzt."))
        )
        val viewModel = AdminDashboardViewModel(repository)
        ui.show { AdminDashboardScreen(viewModel) }
        compose.onNodeWithTag("revenue.amount").assertIsDisplayed()
        ui.shot("dashboard")
        compose.onNodeWithTag("revenue.previous").performClick()
        compose.onNodeWithText("Nächster Monat ›").performClick()
        compose.onNodeWithText("Dieser Monat").performClick()
        compose.onNodeWithText("90 Tage").performClick()
        compose.waitForIdle()
        assertTrue(repository.calls.containsAll(listOf("dashboard 30 2026-09", "dashboard 30 2026-11", "dashboard 30 ", "dashboard 90 ")))
        compose.onNodeWithText("Nur geschätzt.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Die letzten 12 Wochen").performScrollTo().assertIsDisplayed()
        ui.shot("dashboard-weeks")
        compose.onNodeWithTag("staff.nav.votes").performScrollTo().performClick()
        assertEquals(StaffRoute.AdminVotes, ui.top)
    }

    @Test
    fun `the statistics without tiers, notes and next month`() {
        val dashboard = Decoded.dashboard
        repository.dashboard = Result.success(
            dashboard.copy(members = AdminDashboard.Members(dashboard.members.tiles), ai = AdminDashboard.Ai(dashboard.ai.tiles))
        )
        val viewModel = AdminDashboardViewModel(repository)
        ui.show { AdminDashboardScreen(viewModel) }
        compose.onNodeWithText("Nächster Monat ›").assertDoesNotExist()
        compose.onNodeWithText("Oase: ", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a vote is created with its dates and answers, results show after the end`() {
        val viewModel = AdminVotesViewModel(repository)
        ui.show { AdminVotesScreen(viewModel) }
        ui.shot("votes")
        compose.onNodeWithText("Ja: ", substring = true).performScrollTo().assertIsDisplayed()
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("Neue Stufe?")
        fields[1].performTextInput("Warum")
        fields[2].performScrollTo().performTextInput("Ja")
        compose.onNodeWithContentDescription("Beginn:", substring = true).performScrollTo().performClick()
        ui.shotDialog("votes-date")
        compose.onNodeWithText("Weiter").performClick()
        compose.onNodeWithText("Fertig").performClick()
        compose.onNodeWithContentDescription("Ende:", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Abbrechen").performClick()
        compose.onNodeWithContentDescription("Ende:", substring = true).performClick()
        compose.onNodeWithText("Weiter").performClick()
        compose.onNodeWithText("Abbrechen").performClick()
        compose.onNodeWithContentDescription("Ende:", substring = true).performClick()
        compose.onNodeWithText("Weiter").performClick()
        compose.onNodeWithText("Fertig").performClick()
        repository.createVote = FakeStaffRepository.invalid("starts_at" to "Beginn fehlt.", "options" to "Mindestens zwei Antworten.")
        compose.onNodeWithTag("vote.create").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Mindestens zwei Antworten.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Beginn fehlt.").performScrollTo().assertIsDisplayed()
        ui.shot("votes-errors")
        assertEquals("Neue Stufe?", viewModel.state.value.title)
    }

    @Test
    fun `no votes yet`() {
        repository.votes = Result.success(emptyList())
        val viewModel = AdminVotesViewModel(repository)
        ui.show { AdminVotesScreen(viewModel) }
        compose.onNodeWithText("Noch keine Abstimmungen.").performScrollTo().assertIsDisplayed()
    }

    private val mollieRecord: PaymentRecord get() = Decoded.payments.data.first().copy(
        canSync = true,
        canRefund = true,
        accountDeleted = true,
        refunded = true,
        refunds = listOf(PaymentRecord.Refund("1,00 €", Instant.parse("2026-10-23T10:00:00Z"), null, "Doppelt", null))
    )

    @Test
    fun `payments search, look up at Mollie, sync and refund`() {
        repository.payments = Result.success(
            Decoded.payments.copy(
                data = listOf(mollieRecord) + Decoded.payments.data.drop(1).map {
                    it.copy(molliePaymentId = null, mollieCustomerId = null)
                },
                filters = Decoded.payments.filters.copy(mollieId = true, retentionYears = 10)
            )
        )
        val viewModel = AdminPaymentsViewModel(repository)
        ui.show { AdminPaymentsScreen(viewModel) }
        compose.onNodeWithText("Konto gelöscht – Beleg gesperrt", substring = true).performScrollTo().assertIsDisplayed()
        ui.shot("payments")
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("tr_1")
        compose.onNodeWithText("Direkt bei Mollie suchen").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Nichts gefunden.").assertIsDisplayed()
        compose.onNodeWithText("Schließen").performClick()

        val payment = MolliePayment(
            "tr_1", "paid", "5.00", "1.00", "creditcard", "Oase", "2026-10-19T15:39:37+00:00", null, "cst_1", "sub_1",
            "https://my.mollie.com/tr_1"
        )
        repository.mollie = Result.success(MollieLookup(MollieLookup.Data("payment", payment = payment)))
        compose.onAllNodesWithText("Zahlung bei Mollie prüfen")[0].performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("5,00 € · erstattet 1,00 €").performScrollTo().assertIsDisplayed()
        ui.shot("payments-mollie-payment")
        compose.onNodeWithText("Im Mollie-Dashboard öffnen").performScrollTo().performClick()
        assertEquals("https://my.mollie.com/tr_1", ui.web.last())

        val customer = MollieCustomer("cst_1", "Mia", "mia@example.test", "2026-10-01T10:00:00+00:00", null)
        repository.mollie = Result.success(
            MollieLookup(
                MollieLookup.Data(
                    "customer",
                    customer = customer,
                    payments = listOf(payment, payment.copy(id = "tr_2", method = null, subscription = null))
                )
            )
        )
        compose.onAllNodesWithText("Alle Zahlungen der Person bei Mollie")[0].performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Zahlungen dieser Kundin / dieses Kunden bei Mollie (2)").performScrollTo().assertIsDisplayed()
        ui.shot("payments-mollie-customer")
        repository.mollie = Result.success(MollieLookup(MollieLookup.Data("customer", customer = MollieCustomer("cst_2"))))
        compose.onAllNodesWithText("Alle Zahlungen der Person bei Mollie")[0].performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Keine Zahlungen bei Mollie.").performScrollTo().assertIsDisplayed()
        repository.mollie = Result.success(MollieLookup(MollieLookup.Data("payment")))
        compose.onAllNodesWithText("Zahlung bei Mollie prüfen")[0].performScrollTo().performClick()
        compose.waitForIdle()

        compose.onAllNodesWithText("Erstatten")[0].performScrollTo().performClick()
        compose.onNodeWithText("Jetzt erstatten").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Betrag in Euro", substring = true).performScrollTo()
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("1")
        compose.onAllNodes(hasSetTextAction())[2].performTextInput("Doppelt")
        ui.shot("payments-refund")
        compose.onNodeWithText("Abbrechen").performScrollTo().performClick()
        compose.onAllNodesWithText("Erstatten")[0].performScrollTo().performClick()
        compose.onNodeWithText("Jetzt erstatten").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(repository.calls.any { it.startsWith("refund") })
        compose.onAllNodesWithText("Jetzt mit Mollie abgleichen")[0].performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Status").performScrollTo()
        compose.onAllNodesWithText("Alle")[0].performScrollTo().performClick()
        compose.onNodeWithText(Decoded.payments.filters.statuses.first().label).performClick()
        compose.waitForIdle()
        assertTrue(repository.calls.any { it.contains("'${Decoded.payments.filters.statuses.first().value}'") })
    }

    @Test
    fun `no payments, more pages and Mollie's raw values`() {
        repository.payments =
            Result.success(Decoded.payments.copy(data = emptyList(), filters = Decoded.payments.filters.copy(mollieId = false)))
        val viewModel = AdminPaymentsViewModel(repository)
        ui.show { AdminPaymentsScreen(viewModel) }
        compose.onNodeWithText("Keine Belege gefunden.").assertIsDisplayed()
        compose.onNodeWithText("Direkt bei Mollie suchen").assertDoesNotExist()
        assertEquals("–", MollieFormat.amount(null))
        assertEquals("–", MollieFormat.amount("abc"))
        assertEquals("–", MollieFormat.date(null))
        assertEquals("–", MollieFormat.date("kein Datum"))
        assertEquals("unknown", MollieFormat.status("unknown"))
        assertEquals("sofort", MollieFormat.method("sofort"))
        assertEquals(null, MollieFormat.method(null))
    }

    @Test
    fun `payments page on and an empty search keeps the Mollie hint`() {
        repository.payments = Result.success(
            Decoded.payments.copy(
                data = emptyList(),
                meta = Page.Meta(nextCursor = "2"),
                filters = Decoded.payments.filters.copy(mollieId = true)
            )
        )
        val viewModel = AdminPaymentsViewModel(repository)
        ShadowLooper.idleMainLooper()
        repository.payments = Result.success(Decoded.payments)
        ui.show { AdminPaymentsScreen(viewModel) }
        compose.waitForIdle()
        assertTrue(repository.calls.any { it.endsWith(" 2") })
    }

    @Test
    fun `a breach is recorded, its report drafted, its steps confirmed`() {
        // Without confirmations, so no snackbar covers the register while the test taps through it.
        repository.breachAction = Result.success(de.velwebsolutions.oasis.core.network.Confirmed(Decoded.breaches.data.first()))
        val viewModel = DataBreachesViewModel(repository)
        ui.show { DataBreachesScreen(viewModel) }
        ui.shot("breaches")
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("Panne")
        compose.onNodeWithTag("breach.risk.${Decoded.breaches.risks.first().value}").performScrollTo().performClick()
        viewModel.edit { it.copy(discoveredAt = Instant.now().plusSeconds(3 * 86_400)) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Bekannt geworden am:", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Weiter").performClick()
        compose.onNodeWithText("Fertig").performClick()
        val discovered = viewModel.state.value.discoveredAt
        compose.onNodeWithContentDescription("Bekannt geworden am:", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Weiter").performClick()
        compose.onNodeWithText("Fertig").performClick()
        assertTrue("a time before now stays", !viewModel.state.value.discoveredAt.isAfter(discovered))
        assertTrue(
            "never later than now: $discovered",
            !discovered.isAfter(Instant.now()) && discovered.isAfter(Instant.now().minusSeconds(3600))
        )
        compose.onNodeWithTag("breach.record").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Vorbereitete Meldung an die Aufsichtsbehörde").performScrollTo().performClick()
        compose.onNodeWithText("Text teilen oder kopieren").performScrollTo().performClick()
        assertNotNull(shadowOf(ui.context).nextStartedActivity)
        ui.shot("breaches-report")
        compose.onNodeWithText("Verlauf", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Benachrichtigung der Betroffenen vermerken").performScrollTo().performClick()
        ui.shotDialog("breaches-notified")
        compose.onNodeWithText("Benachrichtigung vermerken").performClick()
        compose.onNodeWithText("Abschließen").performScrollTo().performClick()
        compose.onAllNodesWithText("Abschließen")[1].performClick()
        compose.onNodeWithText("Meldung vermerken").performScrollTo().performClick()
        compose.onNodeWithText("Nachtrag speichern").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(repository.calls.containsAll(listOf("notified 1", "close 1")))
    }

    @Test
    fun `an overdue, a reported and a closed breach`() {
        val open = Decoded.breaches.data.first()
        val overdue = open.copy(id = 2, hoursLeft = -3, reportText = null)
        val reported = open.copy(
            id = 3,
            reportOutstanding = false,
            authorityReportedAt = Instant.parse("2026-10-23T10:00:00Z"),
            authorityReference = null,
            peopleNotifiedAt = Instant.parse("2026-10-23T11:00:00Z"),
            canRecordReport = false,
            canRecordNotified = false
        )
        val closed = open.copy(
            id = 4,
            closedAt = Instant.parse("2026-10-24T10:00:00Z"),
            mustReport = false,
            mustNotifyPeople = false,
            reportOutstanding = false
        )
        val notNeeded = open.copy(id = 5, mustReport = false, mustNotifyPeople = false, reportOutstanding = false)
        repository.breaches = Result.success(Decoded.breaches.copy(data = listOf(overdue, reported, closed, notNeeded), authority = null))
        repository.breachAction = FakeStaffRepository.failure("Schon abgeschlossen.")
        val viewModel = DataBreachesViewModel(repository)
        ui.show { DataBreachesScreen(viewModel) }
        compose.onNodeWithText("Die 72 Stunden sind abgelaufen", substring = true).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Abgeschlossen")[0].performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Meldung an die Behörde: nicht nötig")[0].performScrollTo().assertIsDisplayed()
        ui.shot("breaches-states")
        compose.onAllNodesWithText("Nachtrag speichern")[0].performScrollTo().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Schon abgeschlossen.")[0].performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `no breaches yet`() {
        repository.breaches = Result.success(Decoded.breaches.copy(data = emptyList()))
        val viewModel = DataBreachesViewModel(repository)
        ui.show { DataBreachesScreen(viewModel) }
        compose.onNodeWithText("Noch keine Datenpannen erfasst.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the system status with problems, runs and bot refusals`() {
        val system = Decoded.system.copy(
            botRefusals = listOf(SystemStatus.BotRefusal("Honigtopf", 3)),
            heartbeats = listOf(SystemStatus.Heartbeat("schedule", Instant.parse("2026-10-23T10:00:00Z")))
        )
        repository.system = Result.success(system)
        val viewModel = SystemViewModel(repository)
        ui.show { SystemScreen(viewModel) }
        compose.onNodeWithText("Es gibt Probleme").assertIsDisplayed()
        ui.shot("system")
        compose.onNodeWithTag("system.check").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Honigtopf").performScrollTo().assertIsDisplayed()
        repository.system = Result.success(Decoded.system.copy(healthy = true))
        compose.onNodeWithTag("system.check").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Alles in Ordnung").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Heute keine.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Noch keine Läufe aufgezeichnet.").performScrollTo().assertIsDisplayed()
        ui.shot("system-healthy")
    }
}
