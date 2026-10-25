package de.velwebsolutions.oasis.account

import de.velwebsolutions.oasis.account.api.PrivacySettingItem
import de.velwebsolutions.oasis.account.api.PrivacyValue
import de.velwebsolutions.oasis.account.data.needsPasswordConfirmation
import de.velwebsolutions.oasis.account.ui.auth.ForgotPasswordViewModel
import de.velwebsolutions.oasis.account.ui.auth.RegisterViewModel
import de.velwebsolutions.oasis.account.ui.auth.SignInViewModel
import de.velwebsolutions.oasis.account.ui.auth.TwoFactorRecoveryViewModel
import de.velwebsolutions.oasis.account.ui.auth.TwoFactorViewModel
import de.velwebsolutions.oasis.account.ui.auth.VerifyEmailViewModel
import de.velwebsolutions.oasis.account.ui.member.ComplaintViewModel
import de.velwebsolutions.oasis.account.ui.member.GlossaryViewModel
import de.velwebsolutions.oasis.account.ui.member.SuspensionViewModel
import de.velwebsolutions.oasis.account.ui.member.VotesViewModel
import de.velwebsolutions.oasis.account.ui.privacy.HiddenWordsViewModel
import de.velwebsolutions.oasis.account.ui.privacy.PrivacyUiState
import de.velwebsolutions.oasis.account.ui.privacy.PrivacyViewModel
import de.velwebsolutions.oasis.account.ui.safety.DevicesViewModel
import de.velwebsolutions.oasis.account.ui.safety.ReportViewModel
import de.velwebsolutions.oasis.account.ui.safety.SafetyViewModel
import de.velwebsolutions.oasis.account.ui.settings.ProtectedAction
import de.velwebsolutions.oasis.account.ui.settings.SettingsViewModel
import de.velwebsolutions.oasis.account.ui.welcome.WelcomeViewModel
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import de.velwebsolutions.oasis.testing.StubTransport
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The paths of the view models a screen rarely takes: reload failures, refusals without a field, empty input. */
class AccountEdgeCasesTest {
    @get:Rule val main = MainDispatcherRule()

    private val serverError = """{"message":"Server Error"}"""

    @Test
    fun cancellationPassesThroughAndOnlyA423AsksForThePassword() = runTest {
        val cancelled = runCatching { catching { throw CancellationException("weg") } }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)
        assertTrue(ApiError.Server(423, null).needsPasswordConfirmation)
        assertFalse(ApiError.Server(500, null).needsPasswordConfirmation)
        assertFalse(ApiError.NotFound.needsPasswordConfirmation)
    }

    @Test
    fun listsShowTheFirstFailureAsScreenAndLaterOnesAsToast() = runTest {
        val transport = StubTransport()
            .stub("GET", "privacy", serverError, status = 500)
            .stub("GET", "privacy/words", serverError, status = 500)
            .stub("GET", "privacy/devices", serverError, status = 500)
            .stub("GET", "votes", serverError, status = 500)
            .stub("GET", "glossary", serverError, status = 500)
            .stub("GET", "complaints/1", serverError, status = 500)
            .stub("GET", "onboarding", serverError, status = 500)
            .stub("GET", "account", serverError, status = 500)
            .stub("GET", "suspension", serverError, status = 500)
            .stub("GET", "report", serverError, status = 500)
        val repository = repository(transport)
        val session = session(transport, backgroundScope)
        val privacy = PrivacyViewModel(repository)
        val words = HiddenWordsViewModel(repository)
        val devices = DevicesViewModel(repository)
        val votes = VotesViewModel(repository)
        val glossary = GlossaryViewModel(repository)
        val complaint = ComplaintViewModel(1, repository)
        val welcome = WelcomeViewModel(repository, session)
        val settings = SettingsViewModel(repository, session)
        val suspension = SuspensionViewModel(repository, session)
        val report = ReportViewModel("http://localhost/x", repository)
        listOf(
            privacy::load, words::load, devices::load, votes::load, glossary::load, complaint::load, welcome::load, settings::load,
            suspension::load, report::load
        ).forEach { it() }
        assertTrue(privacy.ui.value.state is Loadable.Failed)
        assertNull("the first failure is the screen, not a toast", privacy.ui.value.error)
        assertTrue(glossary.state.value is Loadable.Failed)
        assertTrue(complaint.ui.value.state is Loadable.Failed)
        assertTrue(welcome.ui.value.state is Loadable.Failed)
        assertTrue(report.ui.value.state is Loadable.Failed)

        transport.stub("GET", "privacy", Fixtures.privacy).stub("GET", "privacy/words", Fixtures.hiddenWords)
            .stub("GET", "privacy/devices", Fixtures.devices).stub("GET", "votes", Fixtures.votes)
            .stub("GET", "account", Fixtures.account).stub("GET", "onboarding", Fixtures.onboarding)
            .stub("GET", "suspension", SUSPENSION)
        listOf(privacy::load, words::load, devices::load, votes::load, settings::load, welcome::load, suspension::load).forEach { it() }
        suspension.setStatement("Getippt")
        transport.stub("GET", "privacy", serverError, status = 500).stub("GET", "privacy/words", serverError, status = 500)
            .stub("GET", "privacy/devices", serverError, status = 500).stub("GET", "votes", serverError, status = 500)
            .stub("GET", "account", serverError, status = 500).stub("GET", "suspension", serverError, status = 500)
        listOf(privacy::load, words::load, devices::load, votes::load, settings::load, suspension::load).forEach { it() }
        listOf(
            privacy.ui.value.error,
            words.ui.value.error,
            devices.ui.value.error,
            votes.ui.value.error,
            settings.ui.value.error,
            suspension.ui.value.error
        )
            .forEach { assertNotNull("a failed reload keeps the page and says so", it) }
        transport.stub("GET", "suspension", SUSPENSION)
        welcome.load()
        suspension.load()
        assertEquals("the typed statement stays on a reload", "Getippt", suspension.ui.value.statement)

        privacy.dismissMessage()
        privacy.dismissError()
        words.dismissMessage()
        words.dismissError()
        devices.dismissMessage()
        devices.dismissError()
        votes.dismissMessage()
        votes.dismissError()
        settings.dismissMessage()
        settings.dismissError()
        settings.showError("x")
        suspension.dismissMessage()
        suspension.dismissError()
        suspension.showError("x")
        complaint.dismissError()
        report.dismissError()
        assertNull(privacy.ui.value.error)
    }

    @Test
    fun refusalsWithoutAFieldBecomeToasts() = runTest {
        val transport = StubTransport()
            .stub("GET", "votes", Fixtures.votes)
            .stub("POST", "votes/1", serverError, status = 500)
            .stub("GET", "privacy", Fixtures.privacy)
            .stub("PUT", "privacy/settings/searchable", serverError, status = 500)
            .stub("GET", "privacy/words", Fixtures.hiddenWords)
            .stub("POST", "privacy/words", serverError, status = 500)
            .stub("GET", "account", Fixtures.account)
            .stub("PUT", "account/profile", """{"message":"x","errors":{"email":["Diese Adresse ist vergeben."]}}""", status = 422)
            .stub("PUT", "account/password", """{"message":"Dein Passwort wurde geändert."}""")
            .stub("POST", "account/two-factor", serverError, status = 500)
            .stub("DELETE", "account/two-factor", """{"message":"x","errors":{"code":["Falsch."]}}""", status = 422)
            .stub("POST", "account/confirm-password", """{"data":{"display_name":"Mira Sol","username":"mira","email":"m@x.tld"}}""")
        val repository = repository(transport)
        val votes = VotesViewModel(repository)
        votes.load()
        votes.cast(votes.ui.value.state.valueOrNull!!.open.first())
        assertEquals("Server Error", votes.ui.value.errors[1])

        val privacy = PrivacyViewModel(repository)
        privacy.load()
        privacy.save("searchable", PrivacyValue.Flag(false), PrivacyValue.Flag(true))
        assertEquals("Server Error", privacy.ui.value.errors["searchable"])
        val words = HiddenWordsViewModel(repository)
        words.load()
        words.setWord("spoiler")
        words.add()
        assertEquals("Server Error", words.ui.value.error)

        val settings = SettingsViewModel(repository, session(transport, backgroundScope))
        settings.load()
        settings.saveProfile()
        assertEquals("Diese Adresse ist vergeben.", settings.ui.value.fieldErrors["email"])
        settings.changePassword()
        assertEquals("Dein Passwort wurde geändert.", settings.ui.value.message)
        assertFalse(settings.run(ProtectedAction.EnableTwoFactor))
        assertEquals("Server Error", settings.ui.value.error)
        settings.run(ProtectedAction.DisableTwoFactor)
        assertEquals("Falsch.", settings.ui.value.fieldErrors["code"])
        assertTrue("nothing waits for the password", settings.confirmPassword("geheim"))
        assertFalse(settings.canSeeWelcome)
    }

    @Test
    fun theWelcomeTourHandlesShortLongAndRefusedTexts() = runTest {
        val transport = StubTransport()
            .stub("GET", "onboarding", Fixtures.onboarding)
            .stub("PUT", "onboarding/profile", """{"message":"x","errors":{"location":["Zu lang."]}}""", status = 422)
            .stub("PUT", "onboarding/visibility", """{"message":"x","errors":{"profile_audience":["Nicht erlaubt."]}}""", status = 422)
            .stub("POST", "onboarding/oases/1", serverError, status = 500)
        val repository = repository(transport)
        val notLoaded = WelcomeViewModel(repository(StubTransport()), session(transport, backgroundScope))
        notLoaded.toggle(Fixtures.starterOasis)
        assertEquals("a tour that is not there is onboarded", true, run { notLoaded.ui.value.state.valueOrNull?.onboarded ?: true })
        val welcome = WelcomeViewModel(repository, session(transport, backgroundScope))
        welcome.load()
        welcome.edit { it.copy(bio = "", location = "") }
        welcome.textChanged()
        assertNull(welcome.ui.value.toneHint)
        welcome.edit { it.copy(bio = "x".repeat(401)) }
        welcome.textChanged()
        assertNull(welcome.ui.value.toneHint)
        welcome.next()
        assertEquals("Zu lang.", welcome.ui.value.fieldErrors["location"])
        assertFalse(welcome.ui.value.toneQuestion)
        welcome.skip()
        welcome.next()
        assertEquals("Nicht erlaubt.", welcome.ui.value.fieldErrors["profile_audience"])
        welcome.toggle(welcome.ui.value.state.valueOrNull!!.oases.first())
        assertEquals("Server Error", welcome.ui.value.error)
    }

    @Test
    fun formsDoNothingWithoutInputAndSignInWithoutSecondFactor() = runTest {
        val transport = StubTransport()
            .stub("GET", "auth/register", serverError, status = 500)
            .stub("POST", "auth/login", """{"token":"t","data":${Fixtures.ME}}""")
            .stub("POST", "auth/logout", "{}")
        val session = session(transport, backgroundScope)
        val auth = de.velwebsolutions.oasis.core.auth.RemoteAuthApi(transport.client)
        val signIn = SignInViewModel(session, auth)
        assertNull(signIn.signIn())
        signIn.setLogin("mira")
        signIn.setPassword("geheim")
        assertNull("no second factor needed", signIn.signIn())
        TwoFactorViewModel(session).submit("chal")
        TwoFactorRecoveryViewModel(auth, repository(transport)).submit()
        ForgotPasswordViewModel(auth).send()
        val register = RegisterViewModel(session, auth)
        register.submit()
        assertTrue("without a form nothing is sent", transport.requests.none { it.path == "auth/register" && it.request.method == "POST" })
        val verify = VerifyEmailViewModel(session, auth, repository(transport))
        assertEquals("mira@demo.oasis.tld", verify.email)
        verify.signOut()
        assertNull(verify.email)
        verify.check()
        assertFalse(verify.ui.value.checked)
    }

    @Test
    fun aReportNeedsALinkAndALoadedForm() = runTest {
        val transport = StubTransport()
        val empty = ReportViewModel("", repository(transport))
        assertEquals(Loadable.Failed(ApiError.NotFound), empty.ui.value.state)
        empty.send()
        assertTrue(transport.requests.isEmpty())
        val safety = SafetyViewModel(repository(transport.stub("GET", "safety", Fixtures.safety)))
        safety.load()
        assertTrue(safety.ui.value.state is Loadable.Loaded)
    }

    @Test
    fun aStatementSavedBeforeTheLockIsLoadedAndPrivacyWithoutCentre() = runTest {
        val transport = StubTransport()
            .stub("PUT", "suspension/statement", """{"message":"Angekommen.","statement_at":"2026-10-22T11:00:00.000000Z"}""")
        val suspension = SuspensionViewModel(repository(transport), session(transport, backgroundScope))
        suspension.setStatement("Hallo")
        suspension.save()
        assertEquals("Angekommen.", suspension.ui.value.message)
        val privacy = PrivacyViewModel(repository(transport))
        privacy.show("searchable", PrivacyValue.Flag(true))
        assertTrue(PrivacyUiState().checkupSections.isEmpty())
    }

    @Test
    fun privacyValuesReadEveryShapeAndLabelThemselves() {
        val json = de.velwebsolutions.oasis.core.network.OasisJson.snake
        fun item(value: String) = json.decodeFromString<PrivacySettingItem>("""{"key":"k","title":"T","kind":"choice","value":$value}""")
        assertEquals(PrivacyValue.Flag(true), item("true").value)
        assertEquals(PrivacyValue.Choice("true"), item("\"true\"").value)
        assertEquals(PrivacyValue.Choice("5"), item("5").value)
        assertEquals(PrivacyValue.Choice("""{"a":1}"""), item("""{"a":1}""").value)
        assertEquals("Nein", item("false").valueLabel)
        assertEquals("ohne Bezeichnung", item("\"ohne Bezeichnung\"").valueLabel)
        assertFalse(PrivacyValue.Choice("x").flag)
        assertNull(PrivacyValue.Flag(true).choice)
        val written = json.encodeToString(PrivacySettingItem.serializer(), item("true").copy(value = PrivacyValue.Choice("members")))
        assertTrue(written.contains("\"value\":\"members\""))
        assertTrue(json.encodeToString(PrivacySettingItem.serializer(), item("true")).contains("\"value\":true"))
    }

    @Test
    fun theWelcomeTourWithoutTourAndWithBothToneAnswers() = runTest {
        val transport = StubTransport()
            .stub("GET", "onboarding", serverError, status = 500)
            .stub("POST", "onboarding/oases/1", """{"joined":true}""")
            .stub("POST", "onboarding/finish", "{}")
            .stub("POST", "network/tone", """{"data":{"hint":null,"nudge":false}}""")
        val welcome = WelcomeViewModel(repository(transport), session(transport, backgroundScope))
        assertTrue(welcome.ui.value.steps.isEmpty())
        assertEquals("profil", welcome.ui.value.stepKey)
        welcome.toggle(Fixtures.starterOasis)
        assertEquals("a tour that never loaded counts as onboarded", true, welcome.finish())
        welcome.edit { it.copy(bio = "Hallo") }
        welcome.textChanged()
        assertFalse(welcome.ui.value.toneQuestion)
        transport.stub("POST", "network/tone", """{"data":{"hint":"Alter","nudge":true}}""")
        welcome.textChanged()
        assertTrue(welcome.ui.value.toneQuestion)
    }

    @Test
    fun aWrongPasswordWithoutFieldIsShownAtThePassword() = runTest {
        val transport = StubTransport().stub("POST", "account/confirm-password", serverError, status = 500)
        val settings = SettingsViewModel(repository(transport), session(transport, backgroundScope))
        assertFalse(settings.confirmPassword("x"))
        assertEquals("Server Error", settings.ui.value.fieldErrors["password"])
    }

    companion object {
        const val SUSPENSION =
            """{"data":{"type":"temporary","label":"Gesperrt","started_at":"2026-10-22T10:00:00.000000Z","accusation":"Beleidigung","statement":"Alt"}}"""
    }
}
