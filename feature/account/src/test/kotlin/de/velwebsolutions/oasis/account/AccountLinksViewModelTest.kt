package de.velwebsolutions.oasis.account

import de.velwebsolutions.oasis.account.hilt.HiltScreenTest
import de.velwebsolutions.oasis.account.ui.auth.ResetPasswordViewModel
import de.velwebsolutions.oasis.core.auth.RemoteAuthApi
import de.velwebsolutions.oasis.design.navigation.IncomingLink
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import de.velwebsolutions.oasis.testing.StubTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Links from mails: the confirmation link while signed in, „Neues Passwort“ while signed out. */
class AccountLinksViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val link = IncomingLink.VerifyEmail("5", "a1b2", "1790000000", "sig")

    @Test
    fun theConfirmationLinkConfirmsTheAddressOrSaysWhyNot() = runTest {
        val transport = StubTransport().stub(
            "POST",
            "auth/email/verify-link",
            """{"message":"x","errors":{"link":["Der Link ist abgelaufen oder gehört zu einem anderen Konto. Lass dir einen neuen schicken."]}}""",
            status = 422
        )
        val session = session(transport, backgroundScope, token = "t").also { it.update(HiltScreenTest.MIRA.copy(emailVerified = false)) }
        val model = AccountLinksViewModel(repository(transport), session)
        model.verify(link)
        assertEquals("Der Link ist abgelaufen oder gehört zu einem anderen Konto. Lass dir einen neuen schicken.", model.notice.value)
        assertEquals(
            """{"id":"5","hash":"a1b2","expires":"1790000000","signature":"sig"}""",
            transport.lastBody("auth/email/verify-link").toString()
        )
        transport.add("POST", "auth/email/verify-link", 429, "{}")
        model.verify(link)
        assertEquals("Zu viele Versuche. Bitte warte einen Moment.", model.notice.value)
        transport.add("POST", "auth/email/verify-link", json = """{"message":"Danke!","data":${Fixtures.ME}}""")
        model.verify(link)
        assertEquals(AccountLinksViewModel.CONFIRMED, model.notice.value)
        assertEquals(true, session.me?.emailVerified)
        model.dismiss()
        assertNull(model.notice.value)
    }

    @Test
    fun aNewPasswordIsSavedWithTheLinksToken() = runTest {
        val transport = StubTransport().stub(
            "POST",
            "auth/reset-password",
            """{"message":"x","errors":{"email":["Dieser Link ist ungültig."]}}""",
            status = 422
        )
        val model = ResetPasswordViewModel("tok", "", RemoteAuthApi(transport.client))
        model.save()
        model.setEmail(" mira@demo.oasis.tld ")
        model.save()
        model.setPassword("ein-langes-passwort")
        model.save()
        assertTrue("nothing is sent without both passwords", transport.requests.isEmpty())
        model.setConfirmation("ein-langes-passwort")
        model.save()
        assertEquals("Dieser Link ist ungültig.", model.ui.value.error?.message("email"))
        assertEquals(
            """{"token":"tok","email":"mira@demo.oasis.tld","password":"ein-langes-passwort","password_confirmation":"ein-langes-passwort"}""",
            transport.lastBody("auth/reset-password").toString()
        )
        transport.add("POST", "auth/reset-password", json = """{"message":"Dein Passwort wurde zurückgesetzt."}""")
        model.save()
        val ui = model.ui.value
        assertEquals("Dein Passwort wurde zurückgesetzt.", ui.done)
        assertNull(ui.error)
        assertEquals("", ui.password)
        assertEquals("", ui.confirmation)
    }
}
