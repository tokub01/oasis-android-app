package de.velwebsolutions.oasis.staff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.Confirmed
import de.velwebsolutions.oasis.staff.Fixtures.lastObject
import de.velwebsolutions.oasis.staff.Fixtures.lastQuery
import de.velwebsolutions.oasis.staff.api.QueueFilter
import de.velwebsolutions.oasis.staff.api.StatementOfReasons
import de.velwebsolutions.oasis.staff.data.StaffFiles
import de.velwebsolutions.oasis.staff.ui.admin.AdminDashboardViewModel
import de.velwebsolutions.oasis.staff.ui.admin.AdminPaymentsViewModel
import de.velwebsolutions.oasis.staff.ui.admin.DataBreachesViewModel
import de.velwebsolutions.oasis.staff.ui.components.StaffDate
import de.velwebsolutions.oasis.staff.ui.moderation.CaseDetailViewModel
import de.velwebsolutions.oasis.staff.ui.moderation.ComplaintReviewViewModel
import de.velwebsolutions.oasis.staff.ui.moderation.ModerationOverviewViewModel
import de.velwebsolutions.oasis.staff.ui.moderation.RolesViewModel
import de.velwebsolutions.oasis.staff.ui.moderation.SuspensionsViewModel
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import de.velwebsolutions.oasis.testing.StubTransport.Stub
import java.nio.file.Files
import java.time.Instant
import kotlin.reflect.KClass
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Clears a view model the way leaving its screen does (`onCleared`). */
private fun clear(model: ViewModel) {
    val store = ViewModelStore()
    ViewModelProvider.create(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T = model as T
        }
    )[model::class]
    store.clear()
}

class StaffViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `overview loads and filters the queue like the web`() = runTest {
        val (repository, transport) = Fixtures.repository(
            Stub("GET", "staff/moderation/overview", json = Fixtures.overview),
            Stub("GET", "staff/moderation/cases", json = Fixtures.cases)
        )
        val viewModel = ModerationOverviewViewModel(repository)
        viewModel.load()
        assertNotNull(viewModel.state.value.overview.valueOrNull)
        assertTrue(viewModel.state.value.cases.isNotEmpty())
        assertEquals("active", transport.lastQuery()["status"])

        val requests = transport.requests.size
        viewModel.apply(QueueFilter(priority = "urgent", mine = true, search = "tropfen"))
        assertEquals("the queue reloads once typing pauses", requests, transport.requests.size)
        viewModel.reloadQueue()
        assertEquals(viewModel.state.value.filter, viewModel.state.value.loadedFilter)
        val sent = transport.lastQuery()
        assertEquals("urgent", sent["prioritaet"])
        assertEquals("1", sent["meine"])
        assertEquals("tropfen", sent["suche"])
        assertEquals("1", sent["page"])
    }

    @Test
    fun `staff without two-factor see the guidance`() = runTest {
        val message = "Bitte aktiviere und bestätige die Zwei-Faktor-Authentifizierung für die Moderation."
        val (repository, _) = Fixtures.repository(
            Stub("GET", "staff/moderation/overview", status = 403, json = """{"message":"$message"}""")
        )
        val viewModel = ModerationOverviewViewModel(repository)
        viewModel.load()
        assertEquals(ApiError.Forbidden(message), (viewModel.state.value.overview as Loadable.Failed).error)
    }

    @Test
    fun `decision errors are shown at their fields and the form clears after saving`() = runTest {
        val (repository, transport) = Fixtures.repository(
            Stub("GET", "staff/moderation/cases/2", json = Fixtures.caseDetail),
            Stub(
                "POST",
                "staff/moderation/cases/2/decision",
                status = 422,
                json = """{"message":"invalid","errors":{"facts":["Bitte fülle das Feld Tatsachen aus."]}}"""
            )
        )
        val viewModel = CaseDetailViewModel(2, repository, StaffFiles(Files.createTempDirectory("staff").toFile()))
        viewModel.load()
        assertEquals(viewModel.state.value.detail.valueOrNull?.assignDefault, viewModel.state.value.assignTo)
        viewModel.updateStatement(StatementOfReasons("content_removed", "community_rules", "Regel 1", "", "x"))
        viewModel.decide()
        assertEquals("Bitte fülle das Feld Tatsachen aus.", viewModel.state.value.fieldErrors["facts"])
        assertNull("field errors stay at their fields", viewModel.state.value.error)
        assertEquals(JsonPrimitive("Regel 1"), transport.lastObject("staff/moderation/cases/2/decision")?.get("rule_reference"))
        assertEquals("empty parts are sent too", JsonPrimitive(""), transport.lastObject("staff/moderation/cases/2/decision")?.get("facts"))

        transport.add(
            "POST",
            "staff/moderation/cases/2/decision",
            json = Fixtures.confirmed(Fixtures.caseDetail, "Die Entscheidung wurde gespeichert und mitgeteilt.")
        )
        viewModel.decide()
        assertEquals("Die Entscheidung wurde gespeichert und mitgeteilt.", viewModel.state.value.message)
        assertEquals(StatementOfReasons(), viewModel.state.value.statement)
        assertTrue(viewModel.state.value.fieldErrors.isEmpty())
    }

    @Test
    fun `admins download the evidence archive as a file`() = runTest {
        val (repository, transport) = Fixtures.repository(
            Stub("GET", "staff/moderation/cases/4", json = Fixtures.caseEscalated),
            Stub("GET", "staff/moderation/cases/4/evidence", json = "PK")
        )
        val viewModel = CaseDetailViewModel(4, repository, StaffFiles(Files.createTempDirectory("staff").toFile()))
        viewModel.prepareArchive()
        val file = viewModel.state.value.archiveFile
        assertEquals("oasis-fall-4-beweise.zip", file?.name)
        assertEquals("PK", file?.readText())
        assertTrue(transport.requests.any { it.path == "staff/moderation/cases/4/evidence" })
    }

    @Test
    fun `the evidence archive is gone once the evidence is deleted and when the page closes`() = runTest {
        val repository = FakeStaffRepository().apply { case = Result.success(Decoded.caseEscalated) }
        val viewModel = CaseDetailViewModel(4, repository, StaffFiles(Files.createTempDirectory("staff").toFile()))
        viewModel.prepareArchive()
        val archive = viewModel.state.value.archiveFile!!

        repository.caseAction = FakeStaffRepository.failure("Konflikt")
        viewModel.deleteEvidence()
        assertTrue("a refused deletion keeps the archive", archive.exists())
        assertEquals(archive, viewModel.state.value.archiveFile)
        repository.caseAction = Result.success(Confirmed(Decoded.caseEscalated, "Die Beweise sind gelöscht."))
        viewModel.deleteEvidence()
        assertFalse(archive.exists())
        assertNull(viewModel.state.value.archiveFile)

        viewModel.prepareArchive()
        assertTrue(archive.exists())
        clear(viewModel)
        assertFalse("leaving the page deletes the archive", archive.exists())
    }

    @Test
    fun `the evidence archive goes once the case shows no evidence left`() = runTest {
        val repository = FakeStaffRepository().apply { case = Result.success(Decoded.caseEscalated) }
        val viewModel = CaseDetailViewModel(4, repository, StaffFiles(Files.createTempDirectory("staff").toFile()))
        viewModel.prepareArchive()
        val archive = viewModel.state.value.archiveFile!!
        viewModel.load()
        assertTrue("while the evidence is kept, so is the archive", archive.exists())

        // Deleted by another admin, or its retention ended: the next answer lists no evidence.
        val gone = Decoded.caseEscalated.escalation!!.copy(evidence = emptyList())
        repository.case = Result.success(Decoded.caseEscalated.copy(escalation = gone))
        viewModel.load()
        assertFalse(archive.exists())
        assertNull(viewModel.state.value.archiveFile)
    }

    @Test
    fun `a reported file with a long name in Cyrillic, CJK or emoji still fits the file system`() {
        val files = StaffFiles(Files.createTempDirectory("staff").toFile())
        val cjk = files.reportedAttachment(2, 3, "%PDF".toByteArray(), "中".repeat(116) + ".pdf")
        assertTrue("the name is cut before the extension", cjk.name.startsWith("fall-2-anhang-4-中") && cjk.name.endsWith("中.pdf"))
        assertEquals("%PDF", cjk.readText())
        val emoji = files.reportedAttachment(2, 4, byteArrayOf(1), "😀".repeat(120))
        assertTrue("whole characters only", emoji.name.removePrefix("fall-2-anhang-5-").all { it.isSurrogate() })
        assertEquals(0, emoji.name.removePrefix("fall-2-anhang-5-").length % 2)
        val dated = files.reportedAttachment(2, 5, byteArrayOf(1), "Bericht vom 1." + "中".repeat(106))
        assertTrue("no extension after the last dot, just text", dated.name.startsWith("fall-2-anhang-6-Bericht vom 1.中"))
        assertTrue(dated.name.endsWith("中"))
        val cyrillic = files.reportedAttachment(2, 6, byteArrayOf(1), "Ж".repeat(120))
        listOf(cjk, emoji, dated, cyrillic).forEach { file ->
            assertTrue(file.name, file.name.toByteArray().size <= 255)
            assertTrue(file.exists())
        }
        val short = files.reportedAttachment(2, 7, byteArrayOf(1), "Rechnung.pdf")
        assertEquals("a short name stays whole", "fall-2-anhang-8-Rechnung.pdf", short.name)
    }

    @Test
    fun `a reported voice message or video is loaded with the token into a file, and a failure can be retried`() = runTest {
        val (repository, transport) = Fixtures.repository(
            Stub("GET", "staff/moderation/cases/2", json = Fixtures.caseChat),
            Stub("GET", "staff/moderation/cases/2/chat-attachments/1", json = "ID3"),
            Stub("GET", "staff/moderation/cases/2/chat-attachments/2", status = 404, json = """{"message":"Nicht gefunden."}"""),
            Stub("GET", "staff/moderation/cases/2/chat-attachments/3", json = "%PDF")
        )
        val directory = Files.createTempDirectory("staff").toFile()
        val files = StaffFiles(directory)
        val viewModel = CaseDetailViewModel(2, repository, files)
        viewModel.load()
        assertEquals(
            listOf("image", "audio", "video", "file"),
            viewModel.state.value.detail.valueOrNull?.report?.attachments?.map {
                it.kind
            }
        )

        viewModel.loadAttachment(1)
        val file = viewModel.state.value.attachmentFiles[1]
        assertEquals("ID3", file?.readText())
        assertEquals("fall-2-anhang-2", file?.name)
        assertTrue(transport.requests.any { it.path == "staff/moderation/cases/2/chat-attachments/1" })

        viewModel.loadAttachment(2)
        assertEquals(setOf(2), viewModel.state.value.failedAttachments)
        assertNotNull(viewModel.state.value.error)
        viewModel.loadAttachment(3)
        assertEquals(
            "a file keeps its name for the app that opens it",
            "fall-2-anhang-4-Rechnung.pdf",
            viewModel.state.value.attachmentFiles[3]?.name
        )
        assertEquals("%PDF", viewModel.state.value.attachmentFiles[3]?.readText())
        assertEquals("fall-2-anhang-1-a_b_c.pdf", files.reportedAttachment(2, 0, byteArrayOf(1), "a/b\\c.pdf").name)
        viewModel.loadAttachment(9)
        assertEquals(setOf(1, 3), viewModel.state.value.attachmentFiles.keys)

        // Leaving the page deletes the loaded files; without any, there is nothing to delete.
        clear(viewModel)
        assertTrue(file?.exists() == false)
        StaffFiles(Files.createTempDirectory("leer").toFile()).deleteReportedAttachments(2)
    }

    @Test
    fun `complaint review sends only the chosen branch`() = runTest {
        val confirmed = Fixtures.confirmed(Fixtures.complaint, "Die Beschwerde wurde geprüft und das Ergebnis mitgeteilt.")
        val (repository, transport) = Fixtures.repository(
            Stub("GET", "staff/moderation/complaints/1", json = Fixtures.complaint),
            Stub("POST", "staff/moderation/complaints/1/review", json = confirmed)
        )
        val viewModel = ComplaintReviewViewModel(1, repository)
        viewModel.selectResult("upheld")
        viewModel.updateExplanation("Bleibt.")
        viewModel.updateStatement(StatementOfReasons(facts = "nicht senden"))
        viewModel.review()
        val upheld = transport.lastObject("staff/moderation/complaints/1/review")!!
        assertEquals(JsonPrimitive("upheld"), upheld["result"])
        assertEquals(JsonPrimitive("Bleibt."), upheld["explanation"])
        assertNull(upheld["facts"])

        viewModel.selectResult("overturned")
        viewModel.updateStatement(StatementOfReasons("no_action", "community_rules", "R", "F", "E"))
        viewModel.review()
        val overturned = transport.lastObject("staff/moderation/complaints/1/review")!!
        assertEquals(JsonPrimitive("no_action"), overturned["outcome"])
        assertEquals(JsonPrimitive("E"), overturned["explanation"])
        assertEquals("Die Beschwerde wurde geprüft und das Ergebnis mitgeteilt.", viewModel.state.value.message)
    }

    @Test
    fun `roles show the server's refusal at the row`() = runTest {
        val (repository, _) = Fixtures.repository(
            Stub("GET", "staff/moderation/roles", json = Fixtures.roles),
            Stub("PUT", "staff/moderation/roles/3", status = 403, json = """{"message":"This action is unauthorized."}""")
        )
        val viewModel = RolesViewModel(repository)
        viewModel.load()
        val user = viewModel.state.value.users.valueOrNull!!.first { it.id == 3 }
        viewModel.choose(3, "admin")
        viewModel.save(user)
        assertEquals("Das darfst du leider nicht.", viewModel.state.value.errors[3])
    }

    @Test
    fun `a failed refresh keeps the list and says why`() = runTest {
        val (repository, transport) = Fixtures.repository(Stub("GET", "staff/moderation/suspensions", json = Fixtures.suspensions))
        val viewModel = SuspensionsViewModel(repository)
        viewModel.load()
        val loaded = viewModel.state.value.list.valueOrNull!!
        transport.add("GET", "staff/moderation/suspensions", status = 500, json = """{"message":"Server Error"}""")
        viewModel.load()
        assertEquals(loaded.active.map { it.id }, viewModel.state.value.list.valueOrNull?.active?.map { it.id })
        assertEquals("Server Error", viewModel.state.value.error)
    }

    @Test
    fun `a failed sync is an error not a confirmation`() = runTest {
        val (repository, transport) = Fixtures.repository(
            Stub("GET", "staff/admin/payments", json = Fixtures.payments),
            Stub("POST", "staff/admin/payments/2/sync", status = 502, json = """{"message":"Mollie ist gerade nicht erreichbar."}""")
        )
        val viewModel = AdminPaymentsViewModel(repository)
        viewModel.load()
        val record = viewModel.state.value.records.first { it.id == 2 }
        viewModel.sync(record)
        assertNull(viewModel.state.value.message)
        assertEquals("Mollie ist gerade nicht erreichbar.", viewModel.state.value.error)
        assertEquals("POST", transport.requests.last().request.method)
    }

    @Test
    fun `dashboard asks for period and month`() = runTest {
        val (repository, transport) = Fixtures.repository(Stub("GET", "staff/admin/dashboard", json = Fixtures.dashboard))
        val viewModel = AdminDashboardViewModel(repository)
        viewModel.selectDays(7)
        assertEquals("7", transport.lastQuery()["tage"])
        viewModel.showMonth("2026-09")
        assertEquals("2026-09", transport.lastQuery()["monat"])
        viewModel.showMonth("")
        assertNull(transport.lastQuery()["monat"])
    }

    @Test
    fun `refund uses the prefilled amount and reports the refusal`() = runTest {
        val (repository, transport) = Fixtures.repository(
            Stub("GET", "staff/admin/payments", json = Fixtures.payments),
            Stub(
                "POST",
                "staff/admin/payments/2/refund",
                status = 422,
                json = """{"message":"x","errors":{"amount":["Erstatten lassen sich höchstens 5,00 €."]}}"""
            )
        )
        val viewModel = AdminPaymentsViewModel(repository)
        viewModel.load()
        val record = viewModel.state.value.records.first { it.id == 2 }
        viewModel.startRefund(record)
        assertEquals("5,00", viewModel.state.value.refundAmount)
        viewModel.editRefund(reason = "Doppelt bezahlt")
        viewModel.refund(record)
        assertEquals("Erstatten lassen sich höchstens 5,00 €.", viewModel.state.value.refundErrors["amount"])
        assertEquals(JsonPrimitive("Doppelt bezahlt"), transport.lastObject("staff/admin/payments/2/refund")?.get("reason"))
        assertEquals(2, viewModel.state.value.refundFor)
    }

    @Test
    fun `a new breach sends the web's local time`() = runTest {
        val (repository, transport) = Fixtures.repository(
            Stub("POST", "staff/admin/breaches", status = 201, json = Fixtures.breachStore),
            Stub("GET", "staff/admin/breaches", json = Fixtures.breaches)
        )
        val viewModel = DataBreachesViewModel(repository)
        val discovered = Instant.ofEpochSecond(1_793_000_000)
        viewModel.edit { it.copy(discoveredAt = discovered, form = it.form.copy(title = "Panne")) }
        viewModel.record()
        val post = transport.requests.first { it.request.method == "POST" }.body!!
        val body = kotlinx.serialization.json.Json.parseToJsonElement(post) as kotlinx.serialization.json.JsonObject
        assertEquals(JsonPrimitive(StaffDate.localInput(discovered)), body["discovered_at"])
        assertEquals("the untouched risk is sent", JsonPrimitive("risk"), body["risk"])
        assertTrue(viewModel.state.value.message.orEmpty().startsWith("Erfasst."))
        assertTrue(viewModel.state.value.form.title.isEmpty())
    }

    @Test
    fun `a date becomes the web's datetime-local value in German time`() {
        assertEquals("1970-01-01T01:00", StaffDate.localInput(Instant.EPOCH))
    }
}
