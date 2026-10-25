package de.velwebsolutions.oasis.account

import de.velwebsolutions.oasis.account.api.AccountSettings
import de.velwebsolutions.oasis.account.api.ComplaintDecision
import de.velwebsolutions.oasis.account.api.DeviceOverview
import de.velwebsolutions.oasis.account.api.EndedVote
import de.velwebsolutions.oasis.account.api.GlossaryTerm
import de.velwebsolutions.oasis.account.api.HiddenWords
import de.velwebsolutions.oasis.account.api.OasisJoined
import de.velwebsolutions.oasis.account.api.OpenVote
import de.velwebsolutions.oasis.account.api.PrivacyCentre
import de.velwebsolutions.oasis.account.api.PrivacyChoice
import de.velwebsolutions.oasis.account.api.PrivacyCounts
import de.velwebsolutions.oasis.account.api.PrivacySection
import de.velwebsolutions.oasis.account.api.PrivacySettingItem
import de.velwebsolutions.oasis.account.api.PrivacyValue
import de.velwebsolutions.oasis.account.api.ProfileChange
import de.velwebsolutions.oasis.account.api.ReportForm
import de.velwebsolutions.oasis.account.api.SafetyAccount
import de.velwebsolutions.oasis.account.api.SafetyLists
import de.velwebsolutions.oasis.account.api.SettingsChange
import de.velwebsolutions.oasis.account.api.StarterOasis
import de.velwebsolutions.oasis.account.api.StatementSaved
import de.velwebsolutions.oasis.account.api.Suspension
import de.velwebsolutions.oasis.account.api.TwoFactorState
import de.velwebsolutions.oasis.account.api.VoteOption
import de.velwebsolutions.oasis.account.api.VotesOverview
import de.velwebsolutions.oasis.account.api.WelcomeStep
import de.velwebsolutions.oasis.account.api.WelcomeTour
import de.velwebsolutions.oasis.account.ui.auth.PastDates
import de.velwebsolutions.oasis.account.ui.auth.SignedOutStep
import de.velwebsolutions.oasis.core.model.Me
import de.velwebsolutions.oasis.core.network.OasisJson
import java.time.Instant
import kotlinx.serialization.KSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The account models read back what they write, with and without defaults, and the small helpers around them. */
class AccountModelsTest {
    private val withDefaults = kotlinx.serialization.json.Json(OasisJson.snake) { encodeDefaults = true }
    private val at: Instant = Instant.parse("2026-10-22T10:00:00Z")

    private fun <T> roundTrip(serializer: KSerializer<T>, vararg values: T) = values.forEach { value ->
        listOf(OasisJson.snake, withDefaults).forEach { json ->
            assertEquals(value, json.decodeFromString(serializer, json.encodeToString(serializer, value)))
        }
    }

    @Test
    fun settingsLockAndComplaints() {
        roundTrip(
            AccountSettings.serializer(),
            AccountSettings("Mira", "mira", "m@x.tld"),
            AccountSettings("Mira", "mira", "m@x.tld", false, TwoFactorState(TwoFactorState.Phase.Pending, "KEY", "otpauth://x"), true)
        )
        val me = Me(5, "mira", "Mira", "MS", "m@x.tld")
        roundTrip(SettingsChange.serializer(), SettingsChange("ok", AccountSettings("M", "m", "e"), listOf("a-b")))
        roundTrip(ProfileChange.serializer(), ProfileChange(data = me), ProfileChange("ok", me))
        roundTrip(
            Suspension.serializer(),
            Suspension("ban", "Gesperrt", at, "Beleidigung"),
            Suspension("provisional", "Vorläufig", at, "x", "y", "z", at, 1, 2, 3, 4)
        )
        roundTrip(StatementSaved.serializer(), StatementSaved("ok", at))
        roundTrip(
            ComplaintDecision.serializer(),
            ComplaintDecision(7, "Entfernt", "Spam", deadline = at),
            ComplaintDecision(7, "a", "b", "c", "d", at)
        )
        assertEquals(7, ComplaintDecision(7, "a", "b", deadline = at).id)
    }

    @Test
    fun privacyDevicesAndSafety() {
        val switch = PrivacySettingItem("k", "T", kind = "switch", value = PrivacyValue.Flag(true))
        roundTrip(
            PrivacyCentre.serializer(),
            PrivacyCentre(emptyList()),
            PrivacyCentre(
                listOf(
                    PrivacySection(
                        "s",
                        "S",
                        true,
                        listOf(
                            switch,
                            PrivacySettingItem("c", "C", "h", "choice", PrivacyValue.Choice("a"), listOf(PrivacyChoice("a", "A")), true)
                        )
                    )
                ),
                PrivacyCounts(1, 2, 3, 4, 5)
            )
        )
        roundTrip(HiddenWords.serializer(), HiddenWords(listOf("x"), 50))
        roundTrip(
            DeviceOverview.serializer(),
            DeviceOverview(DeviceOverview.Current(device = "App", ip = "1")),
            DeviceOverview(
                DeviceOverview.Current("Pixel", "App", "1"),
                listOf(DeviceOverview.AppSignIn(1, "A"), DeviceOverview.AppSignIn(2, "B", at, at)),
                true,
                listOf(DeviceOverview.BrowserSession("k", "Chrome", "1", at)),
                listOf(DeviceOverview.KnownDevice(1, "Chrome", at)),
                true,
                listOf(DeviceOverview.ChatDevice(1, "Pixel", at)),
                true
            )
        )
        roundTrip(
            SafetyLists.serializer(),
            SafetyLists(),
            SafetyLists(listOf(SafetyAccount(1, "a", "A", "A")), listOf(SafetyAccount(2, "b", "B", "B", "sun", "http://x")), emptyList())
        )
        roundTrip(ReportForm.serializer(), ReportForm("u", emptyList()), ReportForm("u", listOf(PrivacyChoice("spam", "Spam")), 48))
    }

    @Test
    fun welcomeVotesAndGlossary() {
        roundTrip(
            WelcomeTour.serializer(),
            WelcomeTour(listOf(WelcomeStep("profil", "Über dich"))),
            WelcomeTour(
                listOf(
                    WelcomeStep("oasen", "Oasen")
                ),
                true, "Bio", "Ort", "spring", listOf(PrivacyChoice("sun", "Sonne")), "everyone", "public",
                listOf(PrivacyChoice("everyone", "Alle")), listOf(PrivacyChoice("public", "Öffentlich")),
                listOf(StarterOasis(1, "g", "Garten"), StarterOasis(2, "l", "Lesen", "d", "sun", 3, "http://b", true))
            )
        )
        roundTrip(OasisJoined.serializer(), OasisJoined(true))
        roundTrip(
            VotesOverview.serializer(),
            VotesOverview(),
            VotesOverview(
                true,
                listOf(OpenVote(1, "Frage", endsAt = at), OpenVote(2, "F", "d", at, true, listOf(VoteOption(1, "Ja")))),
                listOf(EndedVote(3, "Alt", at), EndedVote(4, "A", at, 9, listOf(VoteOption(2, "Nein", 40, false))))
            )
        )
        assertEquals(listOf(3, 3), listOf(EndedVote(3, "Alt", at).id, OpenVote(3, "F", endsAt = at).id))
        val term = GlossaryTerm("Tropfen", "tropfen", "Ein Beitrag")
        roundTrip(GlossaryTerm.serializer(), term)
        assertEquals("tropfen", term.slug)
    }

    @Test
    fun signedOutStepsAreKeptByTheirCode() {
        val steps =
            listOf(SignedOutStep.Register, SignedOutStep.ForgotPassword, SignedOutStep.TwoFactorRecovery, SignedOutStep.TwoFactor("c-1"))
        assertEquals(steps, steps.map { SignedOutStep.decode(it.code) })
        assertNull(SignedOutStep.decode("unbekannt"))
    }

    @Test
    fun aBirthDateLiesInThePast() {
        val dates = PastDates(today = 1_000)
        assertTrue(dates.isSelectableDate(1_000))
        assertFalse(dates.isSelectableDate(1_001))
    }
}
