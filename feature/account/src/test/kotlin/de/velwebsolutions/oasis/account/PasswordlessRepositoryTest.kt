package de.velwebsolutions.oasis.account

import de.velwebsolutions.oasis.account.api.Passkey
import de.velwebsolutions.oasis.account.api.PhoneNumberStatus
import de.velwebsolutions.oasis.account.data.needsPasswordConfirmation
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.testing.StubTransport
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Passkeys and the mobile number: every endpoint with its answer, the field refusal, the password lock and the limit. */
class PasswordlessRepositoryTest {
    private val credential = OasisJson.verbatim.parseToJsonElement(PasswordlessFixtures.ATTESTATION).jsonObject

    private suspend fun failure(block: suspend () -> Unit): ApiError = try {
        block()
        fail("expected an error")
        error("unreachable")
    } catch (error: ApiError) {
        error
    }

    @Test
    fun readsThePasskeysInEitherShape() = runTest {
        val transport = StubTransport().stub("GET", "account/passkeys", PasswordlessFixtures.PASSKEYS)
        val api = repository(transport)
        val passkeys = api.passkeys()
        assertEquals(
            listOf(
                Passkey(
                    3,
                    "Pixel 9",
                    "Google Passwortmanager",
                    Instant.parse("2026-10-20T08:00:00Z"),
                    Instant.parse("2026-10-25T18:30:00Z")
                ),
                Passkey(1, "Laptop")
            ),
            passkeys
        )
        transport.add("GET", "account/passkeys", json = """[{"id":1,"name":"Laptop"}]""")
        assertEquals(listOf(Passkey(1, "Laptop")), api.passkeys())
    }

    @Test
    fun addsRenamesAndRemovesPasskeys() = runTest {
        val transport = StubTransport()
            .stub("POST", "account/passkeys/options", PasswordlessFixtures.CREATION)
            .stub("POST", "account/passkeys", """{"data":{"id":4,"name":"Pixel 9"}}""", status = 201)
            .stub("PATCH", "account/passkeys/4", """{"data":{"id":4,"name":"Handy"}}""")
            .stub("DELETE", "account/passkeys/4", "", status = 204)
        val api = repository(transport)
        val ceremony = api.passkeyOptions()
        assertEquals("reg-1", ceremony.challenge)
        assertEquals("localhost", ceremony.options["rp"]!!.jsonObject["id"].toString().trim('"'))
        api.addPasskey("reg-1", credential, "Pixel 9")
        assertEquals(
            """{"challenge":"reg-1","credential":${PasswordlessFixtures.ATTESTATION},"name":"Pixel 9"}""",
            transport.lastBody("account/passkeys").toString()
        )
        api.addPasskey("reg-1", credential, null)
        assertEquals(
            """{"challenge":"reg-1","credential":${PasswordlessFixtures.ATTESTATION}}""",
            transport.lastBody("account/passkeys").toString()
        )
        api.renamePasskey(4, "Handy")
        assertEquals("""{"name":"Handy"}""", transport.lastBody("account/passkeys/4").toString())
        api.removePasskey(4)
        assertEquals("DELETE", transport.requests.last().request.method)
    }

    @Test
    fun passkeyChangesAnswerWithTheLockTheFieldAndTheLimit() = runTest {
        val transport = StubTransport()
            .stub("POST", "account/passkeys/options", PasswordlessFixtures.LOCKED, status = 423)
            .stub("POST", "account/passkeys", PasswordlessFixtures.LOCKED, status = 423)
            .stub("DELETE", "account/passkeys/4", PasswordlessFixtures.LOCKED, status = 423)
            .stub(
                "PATCH",
                "account/passkeys/4",
                """{"message":"x","errors":{"name":["Bitte gib dem Passkey einen Namen."]}}""",
                status = 422
            )
            .stub("GET", "account/passkeys", "{}", status = 429)
        val api = repository(transport)
        assertTrue(failure { api.passkeyOptions() }.needsPasswordConfirmation)
        assertTrue(failure { api.addPasskey("reg-1", credential, null) }.needsPasswordConfirmation)
        assertTrue(failure { api.removePasskey(4) }.needsPasswordConfirmation)
        assertEquals("Bitte gib dem Passkey einen Namen.", failure { api.renamePasskey(4, "") }.message("name"))
        assertEquals(ApiError.TooManyRequests(null), failure { api.passkeys() })
        transport.add(
            "POST",
            "account/passkeys",
            422,
            """{"message":"x","errors":{"challenge":["Die Anfrage ist abgelaufen. Bitte versuch es noch einmal."]}}"""
        )
        assertEquals(
            "Die Anfrage ist abgelaufen. Bitte versuch es noch einmal.",
            failure {
                api.addPasskey("alt", credential, null)
            }.message("challenge")
        )
    }

    @Test
    fun theMobileNumberIsSentConfirmedAndRemoved() = runTest {
        val transport = StubTransport()
            .stub("GET", "account/phone", PasswordlessFixtures.phone())
            .stub("POST", "account/phone", PasswordlessFixtures.phone(pending = "+49 176 ••• 78"), status = 202)
            .stub("POST", "account/phone/confirm", PasswordlessFixtures.phone(number = "+49 176 ••• 78"))
            .stub("DELETE", "account/phone", "", status = 204)
        val api = repository(transport)
        assertEquals(PhoneNumberStatus(smsAvailable = true), api.phone())
        assertEquals("+49 176 ••• 78", api.requestPhoneCode("0176 12345678").pending)
        assertEquals("""{"phone_number":"0176 12345678"}""", transport.lastBody("account/phone").toString())
        val confirmed = api.confirmPhone("123 456")
        assertEquals("+49 176 ••• 78", confirmed.phoneNumber)
        assertEquals(Instant.parse("2026-10-26T09:00:00Z"), confirmed.verifiedAt)
        assertEquals("""{"code":"123 456"}""", transport.lastBody("account/phone/confirm").toString())
        api.removePhone()
        assertEquals("DELETE", transport.requests.last().request.method)
    }

    @Test
    fun theMobileNumberAnswersWithTheLockTheFieldsAndTheLimit() = runTest {
        val transport = StubTransport()
            .stub("POST", "account/phone", PasswordlessFixtures.LOCKED, status = 423)
            .stub("DELETE", "account/phone", PasswordlessFixtures.LOCKED, status = 423)
            .stub("POST", "account/phone/confirm", """{"message":"x","errors":{"code":["Der Code stimmt nicht."]}}""", status = 422)
            .stub("GET", "account/phone", "{}", status = 429)
        val api = repository(transport)
        assertTrue(failure { api.requestPhoneCode("0176") }.needsPasswordConfirmation)
        assertTrue(failure { api.removePhone() }.needsPasswordConfirmation)
        assertEquals("Der Code stimmt nicht.", failure { api.confirmPhone("1") }.message("code"))
        assertEquals(ApiError.TooManyRequests(null), failure { api.phone() })
        transport.add("POST", "account/phone", 422, """{"message":"x","errors":{"phone_number":["Bitte gib deine Handynummer ein."]}}""")
        assertEquals("Bitte gib deine Handynummer ein.", failure { api.requestPhoneCode("") }.message("phone_number"))
        transport.add("POST", "account/phone", 429, "{}")
        assertEquals(ApiError.TooManyRequests(null), failure { api.requestPhoneCode("0176") })
    }
}
