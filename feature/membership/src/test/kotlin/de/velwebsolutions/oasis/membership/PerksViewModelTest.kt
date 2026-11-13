package de.velwebsolutions.oasis.membership

import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.membership.api.CompanionLinkInfo
import de.velwebsolutions.oasis.membership.api.MembershipOverview
import de.velwebsolutions.oasis.membership.api.MembershipPerks
import de.velwebsolutions.oasis.membership.api.SupporterInput
import de.velwebsolutions.oasis.membership.data.MembershipEvents
import de.velwebsolutions.oasis.membership.ui.CompanionLinkViewModel
import de.velwebsolutions.oasis.membership.ui.PerksViewModel
import de.velwebsolutions.oasis.membership.ui.attempt
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import de.velwebsolutions.oasis.testing.StubTransport.Stub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/** Deine Vorteile and the companion link: the requests and the view models. */
class PerksViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val token = "a".repeat(40)
    private val repository = FakeMembershipRepository()
    private val events = MembershipEvents()

    @Test
    fun `the repository reads the perks and sends the companion and supporter calls`() = runTest {
        val (remote, transport) = Fixtures.repository(
            Stub("GET", "membership/perks", json = Fixtures.maezenPerks),
            Stub(
                "POST",
                "membership/companion-seats",
                201,
                """{"message":"Dein Link für einen Platz ist bereit.","data":{"id":3,"url":"x"}}"""
            ),
            Stub("DELETE", "membership/companion-seats/2", json = """{"message":"Der Platz ist wieder frei."}"""),
            Stub("GET", "membership/companion-links/$token", json = """{"data":{"sponsor":"Mia Mäzen"}}"""),
            Stub("POST", "membership/companion-links/$token", json = """{"message":"Willkommen in Mildcrowd! Dein Platz ist bereit."}"""),
            Stub("DELETE", "membership/companion-seat", json = """{"message":"Du hast den Platz zurückgegeben."}"""),
            Stub(
                "PATCH",
                "membership/supporter",
                json = """{"message":"Gespeichert.","data":{"supporter_badge":true,"thank_you_list":false}}"""
            )
        )
        val perks = remote.perks()
        assertEquals("Mäzen", perks.tierName)
        assertEquals(2, perks.features.companionSeats)
        assertEquals("Jonas Weber", perks.seats[0].member?.displayName)
        assertTrue(perks.seats[1].url!!.endsWith("/mitkommen/$token"))
        assertEquals("Dein Link für einen Platz ist bereit.", remote.createSeat())
        assertEquals("Der Platz ist wieder frei.", remote.releaseSeat(2))
        assertEquals("Mia Mäzen", remote.companionSponsor(token))
        assertEquals("Willkommen in Mildcrowd! Dein Platz ist bereit.", remote.acceptCompanionLink(token))
        assertEquals("Du hast den Platz zurückgegeben.", remote.leaveSeat())
        assertEquals("Gespeichert.", remote.updateSupporter(SupporterInput(supporterBadge = true)))
        val body = transport.lastBody("membership/supporter")!!.jsonObject
        assertEquals(true, body["supporter_badge"]?.jsonPrimitive?.boolean)
        assertFalse("thank_you_list" in body)

        transport.add("GET", "membership/companion-links/$token", json = """{"data":{"sponsor":null}}""")
        assertNull(remote.companionSponsor(token))
        val withFeatures = Fixtures.subscriptionOverview.replace(
            "\"is_founding_member\":true",
            "\"features\":{\"companion_seats\":2,\"supporter_badge\":true},\"is_founding_member\":true"
        )
        transport.add("GET", "membership", json = withFeatures)
        assertEquals(2, remote.overview().features.companionSeats)
    }

    @Test
    fun `a cancelled call ends the caller instead of failing`() = runTest {
        try {
            attempt { throw CancellationException("weg") }
            fail("cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("weg", expected.message)
        }
        assertEquals(ApiError.NotFound, attempt { throw ApiError.NotFound }.exceptionOrNull())
    }

    @Test
    fun `perks load, act, reload and report failures`() = runTest {
        val model = PerksViewModel(repository, events, Fixtures.session())
        model.load()
        assertEquals("Mäzen", model.state.value.perks.valueOrNull?.tierName)

        model.createSeat()
        model.releaseSeat(2)
        model.updateSupporter(SupporterInput(supporterBadge = false))
        model.updateSupporter(SupporterInput(thankYouList = true))
        assertEquals("Gespeichert.", model.state.value.message)
        assertEquals(
            listOf(
                "createSeat",
                "releaseSeat 2",
                "supporter ${SupporterInput(supporterBadge = false)}",
                "supporter ${SupporterInput(thankYouList = true)}"
            ),
            repository.calls
        )
        model.dismissMessage()
        assertNull(model.state.value.message)

        val version = events.version.value
        model.giveBack()
        assertEquals(version + 1, events.version.value)

        repository.action = Result.failure(ApiError.Validation("Kein Platz frei.", mapOf("seat" to listOf("Alle Plätze sind vergeben."))))
        model.createSeat()
        assertEquals("Alle Plätze sind vergeben.", model.state.value.error)
        model.giveBack()
        assertEquals(version + 1, events.version.value)
        model.dismissError()

        repository.perks = FakeMembershipRepository.failure("Server Error")
        model.load()
        assertEquals("Server Error", model.state.value.error)
        assertEquals("Mäzen", model.state.value.perks.valueOrNull?.tierName)

        val fresh = PerksViewModel(repository, events, Fixtures.session())
        fresh.load()
        assertTrue(fresh.state.value.perks is Loadable.Failed)
        assertNull(fresh.state.value.error)
    }

    @Test
    fun `a companion link names the sponsor, accepts and reports refusals`() = runTest {
        val model = CompanionLinkViewModel(token, repository, events, Fixtures.session())
        model.load()
        assertEquals(Loadable.Loaded("Mia Mäzen"), model.state.value.sponsor)

        var left = false
        repository.action = Result.success("Willkommen in Mildcrowd! Dein Platz ist bereit.")
        model.accept { left = true }
        assertTrue(left)
        assertEquals("Willkommen in Mildcrowd! Dein Platz ist bereit.", events.take().message)
        assertEquals(listOf("accept $token"), repository.calls)

        repository.action = Result.failure(ApiError.Validation("Nein.", mapOf("seat" to listOf("Du hast schon eine Mitgliedschaft."))))
        left = false
        model.accept { left = true }
        assertFalse(left)
        assertEquals("Du hast schon eine Mitgliedschaft.", model.state.value.error)
        model.dismissError()
        assertNull(model.state.value.error)

        repository.companionSponsor = Result.success(null)
        model.load()
        assertEquals(Loadable.Loaded(null), model.state.value.sponsor)
        repository.companionSponsor = FakeMembershipRepository.failure()
        model.load()
        assertTrue(model.state.value.sponsor is Loadable.Failed)
    }

    @Test
    fun `a held place is hidden while the member pays, and the companion tier comes from the server`() = runTest {
        repository.perks = Result.success(Fixtures.perks(Fixtures.companionPerks))
        val paying = PerksViewModel(repository, events, Fixtures.session())
        paying.load()
        assertTrue(paying.state.value.paying)
        assertNull(paying.state.value.companionTierName)

        val seat = MembershipOverview.CompanionSeat("Mia Mäzen", "oase", "Oase")
        repository.overview = Result.success(Fixtures.overview(Fixtures.noMembershipOverview).copy(companionSeat = seat))
        val companion = PerksViewModel(repository, events, Fixtures.session())
        companion.load()
        assertFalse(companion.state.value.paying)
        assertEquals("Oase", companion.state.value.companionTierName)
        assertFalse(Fixtures.overview(Fixtures.noMembershipOverview).isPaying)

        // Without the overview the place stays shown.
        repository.overview = FakeMembershipRepository.failure()
        val offline = PerksViewModel(repository, events, Fixtures.session())
        offline.load()
        assertFalse(offline.state.value.paying)
        assertEquals(
            seat,
            OasisJson.snake.decodeFromString(
                MembershipOverview.CompanionSeat.serializer(),
                """{"sponsor":"Mia Mäzen","tier":"oase","tier_name":"Oase"}"""
            )
        )
    }

    @Test
    fun `the perks models write what they read`() {
        val json = OasisJson.snake
        val perks = Fixtures.perks().copy(heldSeat = MembershipPerks.HeldSeat("Mia Mäzen", active = true))
        assertEquals(perks, json.decodeFromString(MembershipPerks.serializer(), json.encodeToString(MembershipPerks.serializer(), perks)))
        val link = CompanionLinkInfo("Mia Mäzen")
        assertNull(CompanionLinkInfo().sponsor)
        assertEquals("jonas", MembershipPerks.Seat.Member("Jonas", "jonas").username)
        assertEquals(MembershipPerks(), MembershipPerks(features = MembershipPerks.Features()))
        assertEquals(false, MembershipPerks.HeldSeat("Mia").active)
        assertEquals(true, SupporterInput(supporterBadge = true).supporterBadge)
        assertNull(SupporterInput(supporterBadge = true).thankYouList)
        assertEquals(link, json.decodeFromString(CompanionLinkInfo.serializer(), json.encodeToString(CompanionLinkInfo.serializer(), link)))
        val overview = Fixtures.overview(Fixtures.subscriptionOverview)
        val written = json.encodeToString(MembershipOverview.serializer(), overview)
        assertEquals(overview, json.decodeFromString(MembershipOverview.serializer(), written))
    }
}
