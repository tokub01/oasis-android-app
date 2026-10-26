package de.velwebsolutions.oasis.live

import de.velwebsolutions.oasis.live.api.HintSettings
import de.velwebsolutions.oasis.testing.StubTransport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Live endpoints decode the real answers and send the web's wire names. */
class LiveRepositoryTest {
    @Test
    fun decodesHintsAndAsksForTheNextPage() = runTest {
        val transport = StubTransport(StubTransport.Stub("GET", "hints", json = LiveJson.HINTS))
        val page = repository(transport).hints(1)
        assertEquals(listOf("Heute", "Diese Woche", "Früher"), page.data.map { it.group })
        assertEquals("Neue Person in deiner Karawane · Emil Novak", page.data.first().label)
        assertEquals("http://localhost/profil/emil", page.data.first().url)
        assertNull(page.data.last().url)
        assertEquals("2", page.nextCursor)

        transport.add("GET", "hints", json = LiveJson.LAST_PAGE)
        assertNull(repository(transport).hints(2).nextCursor)
        assertEquals("page=2", transport.requests.last().request.url.query)
    }

    @Test
    fun savesTheSettingsWithTheKindKeysAsTheyAre() = runTest {
        val transport = StubTransport(
            StubTransport.Stub("GET", "hints/settings", json = LiveJson.SETTINGS),
            StubTransport.Stub("PUT", "hints/settings", json = LiveJson.saved("Gespeichert."))
        )
        val settings: HintSettings = repository(transport).settings()
        assertEquals(listOf("follow", "reply", "friend_request"), settings.kinds.map { it.key })
        assertEquals(listOf(1, 3, 7), settings.pauseDays)
        assertTrue(settings.push.available)
        assertEquals("app-4", settings.push.devices.first().listId)

        val answer = repository(transport).save(settings.copy(digest = true))
        assertEquals("Gespeichert.", answer.message)
        assertEquals(
            JsonObject(
                mapOf(
                    "kinds" to
                        JsonObject(
                            mapOf(
                                "follow" to JsonPrimitive(true),
                                "reply" to JsonPrimitive(true),
                                "friend_request" to JsonPrimitive(true)
                            )
                        ),
                    "digest" to JsonPrimitive(true),
                    "quiet_enabled" to JsonPrimitive(false),
                    "quiet_from" to JsonPrimitive("22:00"),
                    "quiet_until" to JsonPrimitive("07:00")
                )
            ),
            transport.lastBody("hints/settings")
        )
    }

    @Test
    fun removesBrowsersAndAppsAtTheirOwnPathsAndReadsTheCounts() = runTest {
        val transport = StubTransport(
            StubTransport.Stub("GET", "hints/settings", json = LiveJson.SETTINGS),
            StubTransport.Stub("DELETE", "push/browsers/9", json = """{"message":"Das Gerät bekommt keine Push-Hinweise mehr."}"""),
            StubTransport.Stub("DELETE", "push/devices/4", json = """{"message":"Das Gerät bekommt keine Push-Hinweise mehr."}"""),
            StubTransport.Stub("GET", "live/counts", json = LiveJson.COUNTS),
            StubTransport.Stub("GET", "app-config", json = LiveJson.CONFIG)
        )
        val repository = repository(transport)
        val devices = repository.settings().push.devices
        assertEquals("Das Gerät bekommt keine Push-Hinweise mehr.", repository.removeDevice(devices.last()))
        repository.removeDevice(devices.first())
        assertEquals(
            listOf("push/browsers/9", "push/devices/4"),
            transport.requests.filter {
                it.request.method == "DELETE"
            }.map { it.path }
        )
        val counts = repository.counts()
        assertEquals(2, counts.chats)
        assertEquals(3, counts.hints)
        assertTrue(repository.appConfig().push.available)
        assertEquals(
            "the server answers for Android",
            "platform=android",
            transport.requests.last {
                it.path == "app-config"
            }.request.url.query
        )
    }
}
