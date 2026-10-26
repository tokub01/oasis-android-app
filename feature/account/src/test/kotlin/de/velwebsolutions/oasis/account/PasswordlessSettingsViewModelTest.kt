package de.velwebsolutions.oasis.account

import de.velwebsolutions.oasis.account.api.Passkey
import de.velwebsolutions.oasis.account.platform.PasskeyAuthenticator
import de.velwebsolutions.oasis.account.platform.PasskeyFailure
import de.velwebsolutions.oasis.account.ui.auth.LoginCodeViewModel
import de.velwebsolutions.oasis.account.ui.auth.SignInViewModel
import de.velwebsolutions.oasis.account.ui.settings.PasskeysViewModel
import de.velwebsolutions.oasis.account.ui.settings.PasswordGate
import de.velwebsolutions.oasis.account.ui.settings.PasswordPrompt
import de.velwebsolutions.oasis.account.ui.settings.PhoneNumberViewModel
import de.velwebsolutions.oasis.core.auth.LoginCodeChannel
import de.velwebsolutions.oasis.core.auth.RemoteAuthApi
import de.velwebsolutions.oasis.core.auth.SessionState
import de.velwebsolutions.oasis.core.auth.SignInMethods
import de.velwebsolutions.oasis.core.di.DeviceName
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import de.velwebsolutions.oasis.testing.StubTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The passkey and mobile-number blocks of the settings, with the password confirmation in between. */
class PasswordlessSettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private class RefusingRepository(base: de.velwebsolutions.oasis.account.data.AccountRepository) :
        de.velwebsolutions.oasis.account.data.AccountRepository by base {
        private val limit = ApiError.TooManyRequests(null)

        override suspend fun passkeyOptions() = throw limit

        override suspend fun addPasskey(challenge: String, credential: kotlinx.serialization.json.JsonObject, name: String?) = throw limit

        override suspend fun renamePasskey(id: Int, name: String) = throw limit

        override suspend fun removePasskey(id: Int) = throw limit

        override suspend fun requestPhoneCode(phoneNumber: String) = throw limit

        override suspend fun confirmPhone(code: String) = throw limit

        override suspend fun removePhone() = throw limit
    }

    @Test
    fun refusalsWithoutARequestAreReportedToo() = runTest {
        val transport = StubTransport()
            .stub("GET", "account/passkeys", PasswordlessFixtures.PASSKEYS)
            .stub("GET", "account/phone", PasswordlessFixtures.phone())
        val refusing = RefusingRepository(repository(transport))
        val limit = "Zu viele Versuche. Bitte warte einen Moment."
        val keys = PasskeysViewModel(refusing, DeviceName("Pixel 9"))
        val first = keys.ui.first { it.state is Loadable.Loaded }.state.valueOrNull!!.first()
        keys.add()
        assertEquals(limit, keys.ui.value.error)
        keys.startRename(first)
        keys.rename("Neu")
        keys.remove(first)
        val phone = PhoneNumberViewModel(refusing)
        phone.ui.first { it.state is Loadable.Loaded }
        phone.sendCode()
        phone.confirm()
        phone.remove()
        assertEquals(limit, phone.ui.value.error)
        val naming = PasskeysViewModel(
            object : de.velwebsolutions.oasis.account.data.AccountRepository by refusing {
                override suspend fun passkeyOptions() =
                    repository(transport.stub("POST", "account/passkeys/options", PasswordlessFixtures.CREATION)).passkeyOptions()
            },
            DeviceName("Pixel 9")
        )
        naming.attach(FakePasskeys(answer = PasswordlessFixtures.ATTESTATION))
        naming.add()
        naming.saveNew("Handy")
        assertEquals(limit, naming.ui.value.error)
    }

    private fun passkeys(transport: StubTransport) = PasskeysViewModel(repository(transport), DeviceName("Pixel 9"))

    @Test
    fun aPasskeyIsAddedAfterThePasswordAndNamed() = runTest {
        val transport = StubTransport()
            .stub("GET", "account/passkeys", """{"data":[]}""")
            .stub("POST", "account/passkeys/options", PasswordlessFixtures.LOCKED, status = 423)
            .stub(
                "POST",
                "account/confirm-password",
                """{"message":"x","errors":{"password":["Das Passwort stimmt nicht."]}}""",
                status = 422
            )
        val model = passkeys(transport)
        model.ui.first { it.state is Loadable.Loaded }
        assertEquals("Pixel 9", model.suggestedName)
        val device = FakePasskeys(answer = PasswordlessFixtures.ATTESTATION)
        model.attach(device)
        model.add()
        assertEquals(PasswordPrompt(), model.prompt.value)
        assertFalse(model.confirmPassword("falsch"))
        assertEquals(PasswordPrompt("Das Passwort stimmt nicht."), model.prompt.value)
        transport.add("POST", "account/confirm-password", json = Fixtures.account)
        transport.add("POST", "account/passkeys/options", json = PasswordlessFixtures.CREATION)
        assertTrue(model.confirmPassword("richtig"))
        assertNull(model.prompt.value)
        assertTrue(device.requests.single().contains("\"excludeCredentials\":[]"))
        assertEquals("reg-1", model.ui.value.naming?.challenge)

        transport.add("POST", "account/passkeys", 201, """{"data":{"id":4,"name":"Handy"}}""")
        transport.add("GET", "account/passkeys", json = """[{"id":4,"name":"Handy"}]""")
        model.saveNew("  Handy ")
        val added = transport.requests.last { it.path == "account/passkeys" && it.request.method == "POST" }.body.orEmpty()
        assertTrue(added, added.contains("\"name\":\"Handy\""))
        assertNull(model.ui.value.naming)
        assertEquals("Der Passkey ist eingerichtet.", model.ui.value.message)
        assertEquals(listOf(Passkey(4, "Handy")), model.ui.value.state.valueOrNull)
        model.saveNew("ohne Dialog")
        assertEquals(
            "nothing waits for a name",
            1,
            transport.requests.count {
                it.path == "account/passkeys" && it.request.method == "POST"
            }
        )
        model.dismissMessage()
        assertNull(model.ui.value.message)
    }

    @Test
    fun aNewPasskeyWithoutANameAndTheRefusalsOfItsName() = runTest {
        val transport = StubTransport()
            .stub("GET", "account/passkeys", """{"data":[]}""")
            .stub("POST", "account/passkeys/options", PasswordlessFixtures.CREATION)
            .stub("POST", "account/passkeys", """{"message":"x","errors":{"name":["Der Name ist zu lang."]}}""", status = 422)
        val model = passkeys(transport)
        model.attach(FakePasskeys(answer = PasswordlessFixtures.ATTESTATION))
        model.add()
        model.saveNew("x".repeat(80))
        assertEquals("Der Name ist zu lang.", model.ui.value.fieldErrors["name"])
        assertTrue("the dialog stays open", model.ui.value.naming != null)
        transport.add("POST", "account/passkeys", 422, """{"message":"x","errors":{"challenge":["Die Anfrage ist abgelaufen."]}}""")
        model.saveNew("")
        assertNull(transport.lastBody("account/passkeys")!!.jsonObject["name"])
        assertEquals("Die Anfrage ist abgelaufen.", model.ui.value.error)
        assertNull("an expired ceremony closes the dialog", model.ui.value.naming)
        model.dismissError()
        assertNull(model.ui.value.error)
    }

    @Test
    fun theSystemSheetDecidesWhetherAPasskeyIsCreated() = runTest {
        val transport = StubTransport()
            .stub("GET", "account/passkeys", "{}", status = 500)
            .stub("POST", "account/passkeys/options", PasswordlessFixtures.CREATION)
        val model = passkeys(transport)
        model.ui.first { it.state is Loadable.Failed }
        model.add()
        assertNull("without a screen nothing opens", model.ui.value.naming)
        val cancelled = FakePasskeys(failure = PasskeyFailure.Cancelled())
        model.attach(cancelled)
        model.add()
        assertNull(model.ui.value.error)
        model.detach(FakePasskeys())
        model.add()
        assertEquals("another screen's provider does not detach this one", 2, cancelled.requests.size)
        model.detach(cancelled)
        model.attach(FakePasskeys(failure = PasskeyFailure.AlreadyRegistered()))
        model.add()
        assertEquals("Auf diesem Gerät gibt es schon einen Passkey für dein Konto.", model.ui.value.error)
        transport.add("POST", "account/passkeys/options", 429, "{}")
        model.add()
        assertEquals("Zu viele Versuche. Bitte warte einen Moment.", model.ui.value.error)
        model.attach(FakePasskeys(answer = "kaputt"))
        transport.add("POST", "account/passkeys/options", json = PasswordlessFixtures.CREATION)
        model.add()
        assertEquals(PasskeyFailure.Failed().userMessage, model.ui.value.error)
    }

    @Test
    fun passkeysAreRenamedAndRemoved() = runTest {
        val transport = StubTransport()
            .stub("GET", "account/passkeys", PasswordlessFixtures.PASSKEYS)
            .stub(
                "PATCH",
                "account/passkeys/3",
                """{"message":"x","errors":{"name":["Bitte gib dem Passkey einen Namen."]}}""",
                status = 422
            )
            .stub("DELETE", "account/passkeys/3", PasswordlessFixtures.LOCKED, status = 423)
            .stub("POST", "account/confirm-password", Fixtures.account)
        val model = passkeys(transport)
        val first = model.ui.first { it.state is Loadable.Loaded }.state.valueOrNull!!.first()
        model.rename("ohne Dialog")
        assertTrue(transport.requests.none { it.request.method == "PATCH" })

        model.startRename(first)
        model.rename(" ")
        assertEquals("Bitte gib dem Passkey einen Namen.", model.ui.value.fieldErrors["name"])
        model.cancelRename()
        assertNull(model.ui.value.renaming)
        assertTrue(model.ui.value.fieldErrors.isEmpty())
        model.startRename(first)
        transport.add("PATCH", "account/passkeys/3", json = """{"data":{"id":3,"name":"Handy"}}""")
        model.rename(" Handy ")
        assertEquals("""{"name":"Handy"}""", transport.lastBody("account/passkeys/3").toString())
        assertEquals("Der Name ist gespeichert.", model.ui.value.message)
        assertNull(model.ui.value.renaming)
        model.startRename(first)
        transport.add("PATCH", "account/passkeys/3", 404, "{}")
        model.rename("Weg")
        assertNull("a passkey that is gone closes the dialog", model.ui.value.renaming)

        model.askRemove(first)
        model.cancelRemove()
        assertNull(model.ui.value.removing)
        model.askRemove(first)
        model.remove(first)
        assertNull(model.ui.value.removing)
        assertEquals(PasswordPrompt(), model.prompt.value)
        model.cancelPasswordConfirmation()
        assertNull(model.prompt.value)
        model.remove(first)
        assertTrue("still locked: the sheet stays", model.confirmPassword("richtig"))
        assertEquals(PasswordPrompt(), model.prompt.value)
        transport.add("DELETE", "account/passkeys/3", 204, "")
        transport.add("GET", "account/passkeys", json = """{"data":[]}""")
        assertTrue(model.confirmPassword("richtig"))
        assertEquals("Der Passkey ist entfernt.", model.ui.value.message)
        assertEquals(emptyList<Passkey>(), model.ui.value.state.valueOrNull)
    }

    @Test
    fun theGateRunsNothingWithoutAWaitingAction() = runTest {
        val transport = StubTransport().stub("POST", "account/confirm-password", Fixtures.account)
        val gate = PasswordGate(repository(transport))
        assertTrue(gate.confirm("richtig"))
        assertNull(gate.prompt.value)
        transport.add("POST", "account/confirm-password", 429, "{}")
        assertFalse(gate.confirm("schnell"))
        assertEquals(PasswordPrompt("Zu viele Versuche. Bitte warte einen Moment."), gate.prompt.value)
    }

    @Test
    fun theMobileNumberIsSentConfirmedAndDeleted() = runTest {
        val transport = StubTransport()
            .stub("GET", "account/phone", PasswordlessFixtures.phone())
            .stub("POST", "account/phone", PasswordlessFixtures.LOCKED, status = 423)
            .stub("POST", "account/confirm-password", Fixtures.account)
        val model = PhoneNumberViewModel(repository(transport))
        model.ui.first { it.state is Loadable.Loaded }
        assertTrue(model.ui.value.showsNumberField)
        model.setNumber(" 0176 12345678 ")
        model.sendCode()
        assertEquals(PasswordPrompt(), model.prompt.value)
        transport.add("POST", "account/phone", 202, PasswordlessFixtures.phone(pending = "+49 176 ••• 78"))
        assertTrue(model.confirmPassword("richtig"))
        assertEquals("""{"phone_number":"0176 12345678"}""", transport.lastBody("account/phone").toString())
        assertEquals("+49 176 ••• 78", model.ui.value.status?.pending)
        assertEquals("Wir haben dir einen Code per SMS geschickt.", model.ui.value.message)
        assertFalse("the number waits for its code", model.ui.value.showsNumberField)

        transport.add("POST", "account/phone/confirm", 422, """{"message":"x","errors":{"code":["Der Code stimmt nicht."]}}""")
        model.setCode("111 111")
        model.confirm()
        assertEquals("Der Code stimmt nicht.", model.ui.value.fieldErrors["code"])
        transport.add("POST", "account/phone/confirm", json = PasswordlessFixtures.phone(number = "+49 176 ••• 78"))
        model.setCode(" 123 456 ")
        model.confirm()
        assertEquals("""{"code":"123 456"}""", transport.lastBody("account/phone/confirm").toString())
        assertEquals("+49 176 ••• 78", model.ui.value.status?.phoneNumber)
        assertEquals("Deine Handynummer ist bestätigt.", model.ui.value.message)
        assertEquals("", model.ui.value.code)
        assertFalse(model.ui.value.showsNumberField)
        model.startEditing()
        assertTrue(model.ui.value.showsNumberField)
        model.setNumber("0")
        model.cancelEditing()
        assertFalse(model.ui.value.showsNumberField)
        assertEquals("", model.ui.value.number)

        model.askRemoval()
        assertTrue(model.ui.value.confirmingRemoval)
        model.cancelRemoval()
        assertFalse(model.ui.value.confirmingRemoval)
        transport.add("DELETE", "account/phone", 204, "")
        transport.add("GET", "account/phone", json = PasswordlessFixtures.phone())
        model.askRemoval()
        model.remove()
        assertFalse(model.ui.value.confirmingRemoval)
        assertEquals("Deine Handynummer ist gelöscht.", model.ui.value.message)
        assertNull(model.ui.value.status?.phoneNumber)
        model.dismissMessage()
        assertNull(model.ui.value.message)
    }

    @Test
    fun withoutSmsThereIsNothingToTypeAndErrorsAreTold() = runTest {
        val transport = StubTransport()
            .stub("GET", "account/phone", "{}", status = 500)
            .stub("DELETE", "account/phone", PasswordlessFixtures.LOCKED, status = 423)
        val model = PhoneNumberViewModel(repository(transport))
        model.ui.first { it.state is Loadable.Failed }
        assertFalse("nothing loaded yet", model.ui.value.showsNumberField)
        transport.add("GET", "account/phone", json = PasswordlessFixtures.phone(available = false, number = "+49 176 ••• 78"))
        model.load()
        assertFalse(model.ui.value.showsNumberField)
        model.startEditing()
        assertFalse("without SMS not even when changing", model.ui.value.showsNumberField)
        transport.add("GET", "account/phone", json = PasswordlessFixtures.phone(pending = "+49 176 ••• 78"))
        model.load()
        model.cancelEditing()
        assertFalse(model.ui.value.showsNumberField)
        transport.add("POST", "account/phone", 429, "{}")
        model.sendCode()
        assertEquals("Zu viele Versuche. Bitte warte einen Moment.", model.ui.value.error)
        model.dismissError()
        assertNull(model.ui.value.error)
        model.remove()
        assertEquals(PasswordPrompt(), model.prompt.value)
        model.cancelPasswordConfirmation()
        assertNull(model.prompt.value)
    }
}
