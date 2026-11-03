package de.velwebsolutions.oasis.account

import de.velwebsolutions.oasis.account.ui.auth.RegisterPage
import de.velwebsolutions.oasis.account.ui.auth.RegisterViewModel
import de.velwebsolutions.oasis.account.ui.auth.UsernameCheck
import de.velwebsolutions.oasis.account.ui.auth.UsernameRules
import de.velwebsolutions.oasis.core.auth.RemoteAuthApi
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import de.velwebsolutions.oasis.testing.StubTransport
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Step 1 of the registration in three small pages (docs/oasis-accounts.md, „Registrierung: Schritt 1 in drei kleinen
 * Seiten“): the username suggestion and its live check, the checks of each page before „Weiter“, and „Zurück“.
 */
class RegistrationPagesTest {
    @get:Rule val main = MainDispatcherRule()

    private val form = """{"data":{"honeypot_field":"website_url","token_field":"form_started","form_token":"abc"}}"""

    private fun TestScope.model(transport: StubTransport) =
        RegisterViewModel(session(transport, backgroundScope), RemoteAuthApi(transport.client))

    @Test
    fun suggestsAUsernameFromTheName() {
        assertEquals("lenabuerger", UsernameRules.suggestion("Lena Bürger"))
        assertEquals("joerg_strass", UsernameRules.suggestion("Jörg_Straß!"))
        assertEquals("aeoe", UsernameRules.suggestion("ÄÖ"))
        assertEquals("letters beyond z are dropped", "rene", UsernameRules.suggestion("Renée"))
        assertEquals("shorter than three: no suggestion", "", UsernameRules.suggestion("Al"))
        assertEquals("a".repeat(30), UsernameRules.suggestion("A".repeat(40)))
        assertNull(UsernameRules.formatProblem("mira_2"))
        assertEquals("Der Benutzername ist erforderlich.", UsernameRules.formatProblem(""))
        assertEquals("Der Benutzername muss mindestens 3 Zeichen lang sein.", UsernameRules.formatProblem("mi"))
        assertEquals("Der Benutzername darf höchstens 30 Zeichen lang sein.", UsernameRules.formatProblem("m".repeat(31)))
        assertEquals(
            "Der Benutzername darf nur Buchstaben von a bis z, Zahlen und Unterstriche enthalten.",
            UsernameRules.formatProblem("Mira-")
        )
        assertEquals(RegisterPage.Name, RegisterPage.of(setOf("display_name")))
        assertEquals(RegisterPage.SignIn, RegisterPage.of(setOf("password", "terms")))
        assertEquals(RegisterPage.Finish, RegisterPage.of(setOf("terms")))
        assertEquals(RegisterPage.Finish, RegisterPage.of(setOf("form")))
    }

    @Test
    fun theUsernameFollowsTheNameAndIsCheckedAfterAPause() = runTest {
        val transport = StubTransport()
            .stub("GET", "auth/register", form)
            .stub("GET", "auth/username-available", """{"available":false,"suggestion":"mira2"}""")
        val model = model(transport)
        model.setDisplayName("Mira")
        assertEquals("mira", model.ui.value.registration.username)
        advanceTimeBy(300)
        assertTrue("nothing asked while typing", transport.requests.none { it.path == "auth/username-available" })
        model.ui.first { it.usernameCheck != null }
        assertEquals(UsernameCheck.Taken("mira", "mira2"), model.ui.value.usernameCheck)
        assertEquals("mira", transport.requests.last { it.path == "auth/username-available" }.request.url.queryParameter("username"))

        transport.add("GET", "auth/username-available", json = """{"available":true}""")
        model.takeSuggestion("mira2")
        model.ui.first { it.usernameCheck is UsernameCheck.Free }
        assertEquals(UsernameCheck.Free("mira2"), model.ui.value.usernameCheck)
        assertTrue(model.ui.value.usernameEdited)
        model.setDisplayName("Mira Sol")
        assertEquals("an edited username stays", "mira2", model.ui.value.registration.username)

        model.setUsername("Mi")
        assertEquals(
            "a wrong form is said without asking",
            UsernameCheck.Invalid("Mi", "Der Benutzername muss mindestens 3 Zeichen lang sein."),
            model.ui.value.usernameCheck
        )
        model.setUsername("")
        assertNull(model.ui.value.usernameCheck)
    }

    @Test
    fun theCheckShowsTheServersRefusalAndNothingWhenOffline() = runTest {
        val transport = StubTransport()
            .stub("GET", "auth/register", form)
            .stub(
                "GET",
                "auth/username-available",
                """{"message":"x","errors":{"username":["Dieser Benutzername ist reserviert."]}}""",
                status = 422
            )
        val model = model(transport)
        model.setUsername("admin")
        model.ui.first { it.usernameCheck != null }
        assertEquals(UsernameCheck.Invalid("admin", "Dieser Benutzername ist reserviert."), model.ui.value.usernameCheck)

        transport.add("GET", "auth/username-available", status = 503, json = """{"message":"Gleich wieder da."}""")
        model.checkUsername("admin")
        assertNull("a failed check shows nothing", model.ui.value.usernameCheck)

        transport.add("GET", "auth/username-available", json = """{"available":false,"suggestion":null}""")
        model.checkUsername("admin")
        assertEquals(UsernameCheck.Taken("admin", null), model.ui.value.usernameCheck)
        model.checkUsername("someone_else")
        assertEquals("an answer for an older name is dropped", UsernameCheck.Taken("admin", null), model.ui.value.usernameCheck)
    }

    @Test
    fun eachPageChecksItsFieldsBeforeWeiterAndZurueckKeepsThem() = runTest {
        val transport = StubTransport()
            .stub("GET", "auth/register", form)
            .stub("GET", "auth/username-available", """{"available":false,"suggestion":"lena2"}""")
        val model = model(transport)
        model.next()
        assertEquals(RegisterPage.Name, model.ui.value.page)
        assertEquals("Der Anzeigename ist erforderlich.", model.ui.value.fieldError("display_name"))
        assertEquals("Der Benutzername ist erforderlich.", model.ui.value.fieldError("username"))
        model.setDisplayName("Lena")
        assertNull("typing clears the field's message", model.ui.value.fieldError("display_name"))
        model.ui.first { it.usernameCheck != null }
        model.next()
        assertEquals("a taken username keeps page 1a", RegisterPage.Name, model.ui.value.page)
        transport.add("GET", "auth/username-available", json = """{"available":true}""")
        model.takeSuggestion("lena2")
        model.ui.first { it.usernameCheck is UsernameCheck.Free }
        model.next()
        assertEquals(RegisterPage.SignIn, model.ui.value.page)

        model.next()
        assertEquals("Die E-Mail-Adresse ist erforderlich.", model.ui.value.fieldError("email"))
        assertEquals("Das Passwort ist erforderlich.", model.ui.value.fieldError("password"))
        model.edit { it.copy(email = "lena@", password = "kurz") }
        model.next()
        assertEquals("Bitte gib eine gültige E-Mail-Adresse ein.", model.ui.value.fieldError("email"))
        assertEquals("Das Passwort muss mindestens 10 Zeichen lang sein.", model.ui.value.fieldError("password"))
        assertFalse(model.ui.value.passwordLongEnough)
        model.edit { it.copy(email = "lena@beispiel.tld", password = "ein-langes-passwort") }
        assertTrue(model.ui.value.passwordLongEnough)
        model.next()
        assertEquals(RegisterPage.Finish, model.ui.value.page)

        model.today = { LocalDate.of(2026, 11, 3) }
        model.setBirthDate(LocalDate.of(2008, 11, 4))
        model.submit()
        assertEquals("Du musst mindestens 18 Jahre alt sein, um Oasis zu nutzen.", model.ui.value.fieldError("birth_date"))
        assertEquals(RegisterViewModel.TERMS_REQUIRED, model.ui.value.fieldError("terms"))
        assertTrue(transport.requests.none { it.request.method == "POST" })
        model.setBirthDate(LocalDate.of(2008, 11, 3))
        assertNull(model.ui.value.fieldError("birth_date"))

        model.back()
        model.back()
        model.back()
        assertEquals(RegisterPage.Name, model.ui.value.page)
        assertEquals("lena2", model.ui.value.registration.username)
        assertEquals("lena@beispiel.tld", model.ui.value.registration.email)
    }
}
