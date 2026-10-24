package de.velwebsolutions.oasis.account

import de.velwebsolutions.oasis.account.api.PrivacyValue
import de.velwebsolutions.oasis.account.api.TwoFactorState
import de.velwebsolutions.oasis.account.ui.components.QrCode
import de.velwebsolutions.oasis.account.ui.member.VotesViewModel
import de.velwebsolutions.oasis.account.ui.privacy.HiddenWordsViewModel
import de.velwebsolutions.oasis.account.ui.privacy.PrivacyViewModel
import de.velwebsolutions.oasis.account.ui.safety.ReportViewModel
import de.velwebsolutions.oasis.account.ui.settings.ProtectedAction
import de.velwebsolutions.oasis.account.ui.settings.SettingsViewModel
import de.velwebsolutions.oasis.account.ui.welcome.WelcomeViewModel
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import de.velwebsolutions.oasis.testing.StubTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** View models of the account area against stubbed answers: password confirmation, saving, errors (iOS AccountModelTests). */
class AccountViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val pending =
        """{"message":"Scanne jetzt den QR-Code und bestätige mit einem Code.","data":{"display_name":"Mira Sol","username":"mira","email":"mira@demo.oasis.tld","email_verified":true,"two_factor":{"state":"pending","setup_key":"ABCDEFGHIJKLMNOP","otpauth_url":"otpauth://totp/Oasis:mira%40demo.oasis.tld?secret=ABCDEFGHIJKLMNOP&issuer=Oasis"},"password_confirmed":true},"recovery_codes":null}"""

    private fun loaded(state: Loadable<*>) = state !is Loadable.Loading && state !is Loadable.Idle

    @Test
    fun asksForThePasswordAndResumesTheAction() = runTest {
        val transport = StubTransport()
            .stub("GET", "account", Fixtures.account)
            .stub("POST", "account/two-factor", """{"message":"Bitte bestätige zuerst dein Passwort."}""", status = 423)
            .stub(
                "POST",
                "account/confirm-password",
                """{"message":"Das Passwort stimmt nicht.","errors":{"password":["Das Passwort stimmt nicht."]}}""",
                status = 422
            )
        val model = SettingsViewModel(repository(transport), session(transport, backgroundScope))
        model.ui.first { loaded(it.state) }
        assertEquals("Mira Sol", model.ui.value.displayName)
        model.run(ProtectedAction.EnableTwoFactor)
        assertEquals(ProtectedAction.EnableTwoFactor, model.ui.value.pendingAction)
        assertNull(model.ui.value.error)

        assertFalse(model.confirmPassword("falsch"))
        assertEquals("Das Passwort stimmt nicht.", model.ui.value.fieldErrors["password"])
        assertEquals(ProtectedAction.EnableTwoFactor, model.ui.value.pendingAction)

        transport.add("POST", "account/confirm-password", json = Fixtures.account)
        transport.add("POST", "account/two-factor", json = pending)
        assertTrue(model.confirmPassword("password"))
        val ui = model.ui.value
        assertNull(ui.pendingAction)
        assertEquals(TwoFactorState.Phase.Pending, ui.settings?.twoFactor?.state)
        assertEquals("ABCDEFGHIJKLMNOP", ui.settings?.twoFactor?.setupKey)
        assertEquals("Scanne jetzt den QR-Code und bestätige mit einem Code.", ui.message)
        assertNotNull(QrCode.encode(ui.settings?.twoFactor?.otpauthUrl.orEmpty()))
    }

    @Test
    fun showsRecoveryCodesOnlyRightAfterCreatingThem() = runTest {
        val confirmed =
            """{"message":"Die Zwei-Faktor-Anmeldung ist aktiv.","data":{"display_name":"Mira Sol","username":"mira","email":"mira@demo.oasis.tld","email_verified":true,"two_factor":{"state":"active","setup_key":null,"otpauth_url":null},"password_confirmed":true},"recovery_codes":["aaaa-bbbb","cccc-dddd"]}"""
        val disabled =
            """{"message":"Die Zwei-Faktor-Anmeldung ist ausgeschaltet.","data":{"display_name":"Mira Sol","username":"mira","email":"mira@demo.oasis.tld","email_verified":true,"two_factor":{"state":"off","setup_key":null,"otpauth_url":null},"password_confirmed":true},"recovery_codes":null}"""
        val transport = StubTransport()
            .stub("GET", "account", Fixtures.account)
            .stub("POST", "account/two-factor/confirm", confirmed)
            .stub("DELETE", "account/two-factor", disabled)
        val model = SettingsViewModel(repository(transport), session(transport, backgroundScope))
        model.ui.first { loaded(it.state) }
        model.edit { it.copy(twoFactorCode = " 123456 ") }
        model.run(ProtectedAction.ConfirmTwoFactor)
        assertEquals(listOf("aaaa-bbbb", "cccc-dddd"), model.ui.value.recoveryCodes)
        assertTrue(model.ui.value.twoFactorCode.isEmpty())
        assertEquals(JsonObject(mapOf("code" to JsonPrimitive("123456"))), transport.lastBody("account/two-factor/confirm"))
        model.run(ProtectedAction.DisableTwoFactor)
        assertNull(model.ui.value.recoveryCodes)
        assertEquals(TwoFactorState.Phase.Off, model.ui.value.settings?.twoFactor?.state)
    }

    @Test
    fun clearsPasswordFieldsAndShowsFieldErrors() = runTest {
        val transport = StubTransport()
            .stub("GET", "account", Fixtures.account)
            .stub(
                "PUT",
                "account/password",
                """{"message":"x","errors":{"current_password":["Das angegebene Passwort ist falsch."]}}""",
                status = 422
            )
        val model = SettingsViewModel(repository(transport), session(transport, backgroundScope))
        model.ui.first { loaded(it.state) }
        model.edit { it.copy(currentPassword = "alt", newPassword = "neu-neu-neu", newPasswordConfirmation = "neu-neu-neu") }
        model.changePassword()
        val ui = model.ui.value
        assertEquals("Das angegebene Passwort ist falsch.", ui.fieldErrors["current_password"])
        assertTrue(ui.currentPassword.isEmpty() && ui.newPassword.isEmpty() && ui.newPasswordConfirmation.isEmpty())
    }

    @Test
    fun deletesTheAccountOnlyAfterTheConsentAndThenSignsOut() = runTest {
        val transport = StubTransport()
            .stub("GET", "account", Fixtures.account)
            .stub("DELETE", "account", "{}")
            .stub("POST", "auth/logout", "{}")
        val model = SettingsViewModel(repository(transport), session(transport, backgroundScope, token = "token"))
        model.ui.first { loaded(it.state) }
        model.edit { it.copy(deleteConfirmed = true) }
        assertTrue(model.run(ProtectedAction.DeleteAccount))
        assertTrue(model.ui.value.accountDeleted)
        assertEquals(JsonObject(mapOf("confirm" to JsonPrimitive(true))), transport.lastBody("account"))
    }

    @Test
    fun keepsTheStoredPrivacyValueWhenTheServerRefuses() = runTest {
        val transport = StubTransport()
            .stub("GET", "privacy", Fixtures.privacy)
            .stub(
                "PUT",
                "privacy/settings/profile_audience",
                """{"message":"x","errors":{"value":["Diese Auswahl gibt es nicht. Deine bisherige Einstellung bleibt."]}}""",
                status = 422
            )
        val model = PrivacyViewModel(repository(transport))
        model.ui.first { loaded(it.state) }
        val setting = model.ui.value.state.valueOrNull!!.sections.first().settings.first()
        model.show(setting.key, PrivacyValue.Choice("members"))
        assertEquals(
            "the choice shows at once",
            PrivacyValue.Choice("members"),
            model.ui.value.state.valueOrNull!!.sections.first().settings.first().value
        )
        model.save(setting.key, PrivacyValue.Choice("members"), previous = setting.value)
        assertEquals("Diese Auswahl gibt es nicht. Deine bisherige Einstellung bleibt.", model.ui.value.errors["profile_audience"])
        assertEquals(setting.value, model.ui.value.state.valueOrNull!!.sections.first().settings.first().value)
        assertNull(model.ui.value.message)
        assertFalse(model.ui.value.isSaving)
    }

    @Test
    fun savesAPrivacySwitchWithTheWebsConfirmation() = runTest {
        val transport = StubTransport()
            .stub("GET", "privacy", Fixtures.privacy)
            .stub(
                "PUT",
                "privacy/settings/searchable",
                """{"message":"Gespeichert: In der Oasis-Suche auffindbar – Nein","data":{"key":"searchable","title":"In der Oasis-Suche auffindbar","help":"Hilfe","kind":"switch","value":false,"choices":[],"limited":false}}"""
            )
        val model = PrivacyViewModel(repository(transport))
        model.ui.first { loaded(it.state) }
        val setting = model.ui.value.state.valueOrNull!!.sections.flatMap { it.settings }.first { it.key == "searchable" }
        model.show(setting.key, PrivacyValue.Flag(false))
        model.save(setting.key, PrivacyValue.Flag(false), previous = setting.value)
        assertEquals(JsonObject(mapOf("value" to JsonPrimitive(false))), transport.lastBody("privacy/settings/searchable"))
        assertEquals("Gespeichert: In der Oasis-Suche auffindbar – Nein", model.ui.value.message)
        assertEquals(
            PrivacyValue.Flag(false),
            model.ui.value.state.valueOrNull!!.sections.flatMap { it.settings }.first {
                it.key ==
                    "searchable"
            }.value
        )
    }

    @Test
    fun walksTheCheckupAndStopsAtTheSummary() = runTest {
        val transport = StubTransport().stub("GET", "privacy", Fixtures.privacy)
        val model = PrivacyViewModel(repository(transport))
        model.ui.first { loaded(it.state) }
        repeat(10) { model.next() }
        assertEquals(5, model.ui.value.step)
        assertNull("the summary", model.ui.value.checkupSection)
        model.back()
        assertEquals(4, model.ui.value.step)
        assertEquals("sicherheit", model.ui.value.checkupSection?.key)
    }

    @Test
    fun showsTheHiddenWordError() = runTest {
        val transport = StubTransport()
            .stub("GET", "privacy/words", Fixtures.hiddenWords)
            .stub("POST", "privacy/words", """{"message":"x","errors":{"word":["Ein Wort braucht mindestens 2 Zeichen."]}}""", status = 422)
        val model = HiddenWordsViewModel(repository(transport))
        model.ui.first { loaded(it.state) }
        model.setWord("a")
        model.add()
        assertEquals("Ein Wort braucht mindestens 2 Zeichen.", model.ui.value.wordError)
        assertNull(model.ui.value.error)
        assertEquals("a", model.ui.value.word)
    }

    @Test
    fun asksTheToneQuestionInTheWelcomeTour() = runTest {
        val transport = StubTransport()
            .stub("GET", "onboarding", Fixtures.onboarding)
            .stub(
                "PUT",
                "onboarding/profile",
                """{"message":"Nochmal lesen?","errors":{"bio":["Nochmal lesen? Deine Beschreibung klingt verletzend."]},"tone_question":true}""",
                status = 422
            )
        val model = WelcomeViewModel(repository(transport), session(transport, backgroundScope))
        model.ui.first { loaded(it.state) }
        model.edit { it.copy(bio = "Ihr seid alle Idioten") }
        model.next()
        assertEquals(1, model.ui.value.step)
        assertTrue(model.ui.value.toneQuestion)
        assertNotNull(model.ui.value.fieldErrors["bio"])

        transport.add("PUT", "onboarding/profile", json = """{"message":null}""")
        model.edit { it.copy(toneConfirmed = true) }
        model.next()
        assertEquals(2, model.ui.value.step)
        assertEquals(JsonPrimitive(true), transport.lastBody("onboarding/profile")?.jsonObject?.get("tone_confirmed"))
        repeat(3) { model.skip() }
        assertEquals(4, model.ui.value.step)
    }

    @Test
    fun reportsWithoutALinkAreNotFound() = runTest {
        val model = ReportViewModel(null, repository(StubTransport()))
        assertEquals(ApiError.NotFound, model.ui.value.state.errorOrNull)
    }

    @Test
    fun reportsWithTheChosenCategoryAndThanks() = runTest {
        val transport = StubTransport().stub("GET", "report", Fixtures.report).stub("POST", "report", "{}")
        val model = ReportViewModel("http://localhost/profil/jonas", repository(transport))
        model.ui.first { loaded(it.state) }
        val category = model.ui.value.state.valueOrNull!!.categories.first().value
        model.choose(category)
        model.setComment("Das geht schon länger so.")
        model.send()
        assertTrue(model.ui.value.sent)
        assertEquals(JsonPrimitive(category), transport.lastBody("report")?.jsonObject?.get("category"))
    }

    @Test
    fun showsTheBallotError() = runTest {
        val transport = StubTransport()
            .stub("GET", "votes", Fixtures.votes)
            .stub("POST", "votes/1", """{"message":"x","errors":{"option":["Bitte wähle eine Antwort aus."]}}""", status = 422)
        val model = VotesViewModel(repository(transport))
        model.ui.first { loaded(it.state) }
        val vote = model.ui.value.state.valueOrNull!!.open.first()
        model.cast(vote)
        assertEquals("Bitte wähle eine Antwort aus.", model.ui.value.errors[vote.id])
    }
}
