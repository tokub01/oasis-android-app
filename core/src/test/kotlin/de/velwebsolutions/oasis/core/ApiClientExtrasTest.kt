package de.velwebsolutions.oasis.core

import de.velwebsolutions.oasis.core.auth.InMemoryTokenStore
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.auth.SessionState
import de.velwebsolutions.oasis.core.format.RelativeTime
import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.core.network.ApiConfig
import de.velwebsolutions.oasis.core.network.KeyCoding
import de.velwebsolutions.oasis.core.network.TokenProvider
import de.velwebsolutions.oasis.testing.StubTransport
import java.time.Instant
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import retrofit2.Call
import retrofit2.http.GET

class ApiClientExtrasTest {
    @Serializable
    data class Envelope(val keepAsIs: String)

    interface Probe {
        @GET("probe")
        fun probe(): Call<Envelope>

        @GET("probe")
        suspend fun suspended(): Envelope
    }

    @Test
    fun `verbatim services keep camelCase keys and calls can be cloned`() = runTest {
        val stub = StubTransport(StubTransport.Stub("GET", "probe", json = """{"keepAsIs":"ja"}"""))
        val probe = stub.client.create<Probe>(KeyCoding.Verbatim)
        assertEquals(Envelope("ja"), probe.suspended())
        val call = probe.probe()
        assertNotSame(call, call.clone())
        assertEquals("ja", call.execute().body()?.keepAsIs)
    }

    @Test
    fun `a client without its own OkHttp builds one`() {
        val tokens = object : TokenProvider {
            override fun currentToken(): String? = null

            override fun onUnauthenticated() = Unit
        }
        val client = ApiClient(ApiConfig("https://get-oasis.com", "OasisApp/1.0"), tokens)
        assertEquals("https://get-oasis.com/api/v1/auth/me", client.url("auth/me").toString())
    }

    @Test
    fun `without a stored token the app starts signed out`() = runTest {
        val session = Session(InMemoryTokenStore(), "Pixel", TestScope())
        session.restore()
        assertEquals(SessionState.SignedOut, session.state.value)
    }

    @Test
    fun `relative time is measured against now`() {
        assertEquals("gerade eben", RelativeTime.format(Instant.now()))
    }
}
