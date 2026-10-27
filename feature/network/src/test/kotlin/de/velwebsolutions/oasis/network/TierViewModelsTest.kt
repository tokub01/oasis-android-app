package de.velwebsolutions.oasis.network

import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.DataEnvelope
import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.network.api.NamedOasis
import de.velwebsolutions.oasis.network.api.TimeCapsule
import de.velwebsolutions.oasis.network.api.TimeCapsuleInput
import de.velwebsolutions.oasis.network.api.TimeCapsules
import de.velwebsolutions.oasis.network.api.Yearbook
import de.velwebsolutions.oasis.network.ui.profiles.CapsuleDraft
import de.velwebsolutions.oasis.network.ui.profiles.TimeCapsulesViewModel
import de.velwebsolutions.oasis.network.ui.profiles.YearbookViewModel
import de.velwebsolutions.oasis.network.ui.profiles.yearbookTotals
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Answers of the tier endpoints (TimeCapsuleController, YearbookController) for a member with Oase. */
object TierFixtures {
    val capsules = """
        {"data":{"available":true,"locked_message":null,"max_length":1000,"max_waiting":50,"max_years":10,
        "oases":[{"id":2,"slug":"stadtgarten","name":"Stadtgarten"}],
        "waiting":[{"id":5,"body":"Alles Gute zum Geburtstag!","opens_at":"2026-12-24T17:00:00.000000Z","oasis":null,"opened_at":null,"post_id":null,"failed_reason":null},
        {"id":6,"body":"Ernte im Garten","opens_at":"2027-06-01T08:00:00.000000Z","oasis":{"slug":"stadtgarten","name":"Stadtgarten"},"opened_at":null,"post_id":null,"failed_reason":null}],
        "past":[{"id":3,"body":"Ein Jahr Oasis","opens_at":"2026-10-01T08:00:00.000000Z","oasis":null,"opened_at":"2026-10-01T08:00:00.000000Z","post_id":44,"failed_reason":null},
        {"id":4,"body":"Ohne Mitgliedschaft","opens_at":"2026-09-01T08:00:00.000000Z","oasis":null,"opened_at":null,"post_id":null,"failed_reason":"Deine Mitgliedschaft lief nicht."}]}}
    """.trimIndent()

    val lockedCapsules = """
        {"data":{"available":false,"locked_message":"Das gibt es ab der Mitgliedschaft Oase.","max_length":500,"max_waiting":50,"max_years":10,"oases":[],"waiting":[],"past":[]}}
    """.trimIndent()

    val yearbook = """
        {"data":{"year":2026,"years":[2026,2025],"totals":{"posts":2,"images":1,"oases":1},"print_url":"http://localhost/app/jahrbuch/2026",
        "months":[{"name":"Oktober","posts":[{"id":12,"body":"Werkstatt im Hinterhof","created_at":"2026-10-20T21:32:17.000000Z",
        "oasis":{"slug":"rad-werkstatt","name":"Rad & Werkstatt"},"images":[{"id":4,"url":"http://localhost/api/v1/network/posts/12/images/4",
        "alt":"Ein Fahrrad","width":1200,"height":800}],"reactions":1,"replies":2},
        {"id":13,"body":"Stiller Tag","created_at":"2026-10-21T08:00:00.000000Z","oasis":null,"images":[],"reactions":0,"replies":0}]}]}}
    """.trimIndent()

    val emptyYearbook = """
        {"data":{"year":2025,"years":[2026,2025],"totals":{"posts":0,"images":0,"oases":0},"print_url":"http://localhost/app/jahrbuch/2025","months":[]}}
    """.trimIndent()

    const val LOCKED = """{"message":"Das gibt es ab der Mitgliedschaft Oase."}"""
}

/** Zeitkapseln and the Jahrbuch: the requests, the view models and the cover line. */
class TierViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val models = ViewModelKeeper()

    @After
    fun clear() = models.clear()

    @Test
    fun `the repository reads and writes the Zeitkapseln and reads the Jahrbuch of a year`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/time-capsules", TierFixtures.capsules),
            stub("POST", "network/time-capsules", """{"message":"Deine Zeitkapsel ist versiegelt.","data":{}}""", 201),
            stub("POST", "network/time-capsules/5/open", """{"message":"Deine Zeitkapsel ist aufgegangen.","data":{}}"""),
            stub("DELETE", "network/time-capsules/5", """{"message":"Die Zeitkapsel ist gelöscht."}"""),
            stub("GET", "network/yearbook", TierFixtures.yearbook),
            stub("GET", "network/yearbook/2025", TierFixtures.emptyYearbook)
        )
        val repository = network.repository
        val capsules = repository.timeCapsules()
        assertEquals(listOf(5, 6), capsules.waiting.map { it.id })
        assertEquals("Stadtgarten", capsules.waiting[1].oasis?.name)
        assertEquals(44, capsules.past[0].postId)
        assertEquals("Deine Mitgliedschaft lief nicht.", capsules.past[1].failedReason)

        assertEquals(
            "Deine Zeitkapsel ist versiegelt.",
            repository.sealTimeCapsule(TimeCapsuleInput("Hallo", "2026-12-24T18:00:00+01:00", 2))
        )
        val body = network.transport.lastBody("network/time-capsules")!!.jsonObject
        assertEquals("2026-12-24T18:00:00+01:00", body["opens_at"]?.jsonPrimitive?.content)
        assertEquals(2, body["oasis_id"]?.jsonPrimitive?.int)
        repository.sealTimeCapsule(TimeCapsuleInput("Ohne Oase", "2026-12-24T18:00:00+01:00"))
        assertFalse("oasis_id" in network.transport.lastBody("network/time-capsules")!!.jsonObject)
        assertEquals("Deine Zeitkapsel ist aufgegangen.", repository.openTimeCapsule(5))
        assertEquals("Die Zeitkapsel ist gelöscht.", repository.deleteTimeCapsule(5))

        val book = repository.yearbook(null)
        assertEquals(2026, book.year)
        assertEquals("Rad & Werkstatt", book.months[0].posts[0].oasis?.name)
        assertEquals(1.5f, book.months[0].posts[0].images[0].aspectRatio)
        assertEquals(2025, repository.yearbook(2025).year)
    }

    @Test
    fun `a capsule opens tomorrow at the full hour and goes out with the device's offset`() {
        val now = Instant.parse("2026-10-27T13:42:10Z")
        assertEquals(Instant.parse("2026-10-28T13:00:00Z"), CapsuleDraft.suggestedOpening(now))
        val christmasEve = Instant.parse("2026-12-24T17:00:00Z")
        assertEquals("2026-12-24T18:00:00+01:00", CapsuleDraft.wireTime(christmasEve, ZoneId.of("Europe/Berlin")))
        assertEquals("2026-12-24T17:00:00+00:00", CapsuleDraft.wireTime(christmasEve, ZoneId.of("UTC")))
        assertTrue(CapsuleDraft().opensAt.isAfter(Instant.now()))
    }

    @Test
    fun `sealing sends the draft, starts a fresh one and reloads`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/time-capsules", TierFixtures.capsules),
            stub("POST", "network/time-capsules", """{"message":"Deine Zeitkapsel ist versiegelt.","data":{}}""", 201)
        )
        val model = models.keep(TimeCapsulesViewModel(network.repository))
        model.loadIfNeeded()
        model.loadIfNeeded()
        assertEquals(1, network.transport.requests.count { it.path == "network/time-capsules" })
        val opening = Instant.parse("2026-12-24T17:00:00Z")
        model.updateBody("Frohe Weihnachten")
        model.updateOpensAt(opening)
        model.updateOasis(2)
        model.seal()

        assertEquals("Deine Zeitkapsel ist versiegelt.", model.status.value)
        assertEquals("", model.draft.value.body)
        assertNull(model.draft.value.oasisId)
        val posted = network.transport.requests.last { it.request.method == "POST" }.body!!
        val sent = Json.parseToJsonElement(posted).jsonObject
        assertEquals("Frohe Weihnachten", sent["body"]?.jsonPrimitive?.content)
        assertEquals(CapsuleDraft.wireTime(opening), sent["opens_at"]?.jsonPrimitive?.content)
        assertEquals(2, network.transport.requests.count { it.request.method == "GET" })
    }

    @Test
    fun `a refused capsule keeps the draft and its field errors, refused actions keep the list`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/time-capsules", TierFixtures.capsules),
            stub(
                "POST",
                "network/time-capsules",
                """{"message":"Zu früh.","errors":{"opens_at":["Frühestens in einer Stunde."]}}""",
                422
            ),
            stub("POST", "network/time-capsules/5/open", """{"message":"Nicht deine."}""", 403),
            stub("DELETE", "network/time-capsules/5", """{"message":"Nicht deine."}""", 403)
        )
        val model = models.keep(TimeCapsulesViewModel(network.repository))
        model.load()
        model.updateBody("Zu bald")
        model.seal()
        assertEquals("Zu bald", model.draft.value.body)
        assertEquals("Frühestens in einer Stunde.", model.error.value?.message("opens_at"))

        model.openNow(5)
        assertEquals(ApiError.Forbidden("Nicht deine."), model.error.value)
        model.delete(5)
        assertEquals(ApiError.Forbidden("Nicht deine."), model.error.value)
        assertEquals(1, network.transport.requests.count { it.request.method == "GET" })
    }

    @Test
    fun `opening early and deleting confirm and reload`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/time-capsules", TierFixtures.capsules),
            stub("POST", "network/time-capsules/5/open", """{"message":"Deine Zeitkapsel ist aufgegangen.","data":{}}"""),
            stub("DELETE", "network/time-capsules/6", """{"message":"Die Zeitkapsel ist gelöscht."}""")
        )
        val model = models.keep(TimeCapsulesViewModel(network.repository))
        model.openNow(5)
        assertEquals("Deine Zeitkapsel ist aufgegangen.", model.status.value)
        model.delete(6)
        assertEquals("Die Zeitkapsel ist gelöscht.", model.status.value)
        assertEquals(2, network.transport.requests.count { it.request.method == "GET" })
    }

    @Test
    fun `the Jahrbuch loads once, switches the year and reports the tier`() = runTest {
        // Answers on the calling thread, so show() returns without suspending, too.
        immediate = true
        val network = try {
            StubbedNetwork(
                stub("GET", "network/yearbook/2026", TierFixtures.yearbook),
                stub("GET", "network/yearbook/2025", TierFixtures.emptyYearbook)
            )
        } finally {
            immediate = false
        }
        val model = models.keep(YearbookViewModel(2026, network.repository))
        model.loadIfNeeded()
        model.loadIfNeeded()
        assertEquals(1, network.transport.requests.size)
        model.show(2025)
        assertEquals(2025, model.book.value.valueOrNull?.year)
        assertEquals("network/yearbook/2025", network.transport.requests.last().path)

        val locked = StubbedNetwork(stub("GET", "network/yearbook", TierFixtures.LOCKED, 403))
        val below = models.keep(YearbookViewModel(null, locked.repository))
        below.load()
        assertEquals(Loadable.Failed(ApiError.Forbidden("Das gibt es ab der Mitgliedschaft Oase.")), below.book.value)
    }

    @Test
    fun `the cover counts photos and oases in German`() {
        assertEquals("2 Tropfen · 1 Foto · in 1 Oase", yearbookTotals(Yearbook.Totals(2, 1, 1)))
        assertEquals("5 Tropfen · 3 Fotos · in 2 Oasen", yearbookTotals(Yearbook.Totals(5, 3, 2)))
        assertEquals("1 Tropfen · 0 Fotos", yearbookTotals(Yearbook.Totals(1, 0, 0)))
    }

    @Test
    fun `the tier models write what they read`() {
        val json = OasisJson.snake
        val capsules = json.decodeFromString(DataEnvelope.serializer(TimeCapsules.serializer()), TierFixtures.capsules).data
        assertEquals(capsules, json.decodeFromString(TimeCapsules.serializer(), json.encodeToString(TimeCapsules.serializer(), capsules)))
        val book = json.decodeFromString(DataEnvelope.serializer(Yearbook.serializer()), TierFixtures.yearbook).data
        assertEquals(book, json.decodeFromString(Yearbook.serializer(), json.encodeToString(Yearbook.serializer(), book)))
        val now = Instant.parse("2026-10-22T10:00:00Z")
        assertEquals("garten", TimeCapsules.Oasis(2, "garten", "Garten").slug)
        assertEquals("garten", NamedOasis("garten", "Garten").slug)
        assertEquals(TimeCapsules(), TimeCapsules(available = false, oases = emptyList()))
        assertNull(TimeCapsule(1, "b", now).oasis)
        assertEquals(Yearbook(2026, printUrl = "u"), Yearbook(2026, totals = Yearbook.Totals(), printUrl = "u"))
        assertEquals(0, Yearbook.Entry(1, "b", now).reactions)
        assertEquals(emptyList<Yearbook.Entry>(), Yearbook.Month("Mai").posts)
        assertNull(TimeCapsuleInput("b", "t").oasisId)
    }
}
