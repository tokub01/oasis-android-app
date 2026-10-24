package de.velwebsolutions.oasis.account

import de.velwebsolutions.oasis.account.data.DefaultAccountRepository
import de.velwebsolutions.oasis.core.auth.InMemoryTokenStore
import de.velwebsolutions.oasis.core.auth.RemoteAuthApi
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.network.RemoteCommonApi
import de.velwebsolutions.oasis.testing.StubTransport
import kotlinx.coroutines.CoroutineScope

/** Real answers of the Laravel API (captured from the dev server, demo member mira – the iOS Fixtures). */
object Fixtures {
    fun json(name: String): String =
        requireNotNull(Fixtures::class.java.classLoader?.getResource("fixtures/$name.json")) { name }.readText()

    val account get() = json("account")
    val privacy get() = json("privacy")
    val hiddenWords get() = json("hidden-words")
    val devices get() = json("devices")
    val safety get() = json("safety")
    val onboarding get() = json("onboarding")
    val votes get() = json("votes")
    val glossary get() = json("glossary")
    val report get() = json("report")

    /** A starter oasis of the welcome tour. */
    val starterOasis = de.velwebsolutions.oasis.account.api.StarterOasis(id = 1, slug = "korn-licht", name = "Korn & Licht")

    const val ME =
        """{"id":5,"username":"mira","display_name":"Mira Sol","initials":"MS","email":"mira@demo.oasis.tld","email_verified":true,"onboarded":true}"""
}

fun repository(transport: StubTransport) = DefaultAccountRepository(transport.client, RemoteCommonApi(transport.client))

/** A signed-out session that signs in through the stubbed API. */
fun session(transport: StubTransport, scope: CoroutineScope, token: String? = null) =
    Session(InMemoryTokenStore(token), "Pixel 9 von Mira", scope).also { it.auth = RemoteAuthApi(transport.client) }

fun StubTransport.stub(method: String, path: String, json: String, status: Int = 200) = apply { add(method, path, status, json) }
