package de.velwebsolutions.oasis.account

import de.velwebsolutions.oasis.account.platform.PasskeyAuthenticator

/** A passkey provider that answers with [answer] or fails with [failure]; it records the options it got. */
class FakePasskeys(
    var answer: String = PasswordlessFixtures.ASSERTION,
    var failure: Exception? = null,
    override val isAvailable: Boolean = true
) : PasskeyAuthenticator {
    val requests = mutableListOf<String>()

    override suspend fun authenticate(requestJson: String): String = respond(requestJson)

    override suspend fun register(requestJson: String): String = respond(requestJson)

    private fun respond(requestJson: String): String {
        requests += requestJson
        failure?.let { throw it }
        return answer
    }
}
