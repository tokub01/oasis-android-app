package de.velwebsolutions.oasis.network

import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.network.api.ImageUpload
import de.velwebsolutions.oasis.network.ui.oases.CloseOasisViewModel
import de.velwebsolutions.oasis.network.ui.oases.OasesViewModel
import de.velwebsolutions.oasis.network.ui.oases.OasisFormOutcome
import de.velwebsolutions.oasis.network.ui.oases.OasisFormViewModel
import de.velwebsolutions.oasis.network.ui.oases.OasisStatisticsViewModel
import de.velwebsolutions.oasis.network.ui.oases.OasisViewModel
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** The oasis screens' view models: overview, one oasis, the form with banner and roles, closing and statistics. */
@RunWith(Parameterized::class)
class OasisViewModelsTest(private val answersAtOnce: Boolean) {
    companion object {
        /** Each test runs with answers that suspend and with answers on the calling thread. */
        @JvmStatic
        @Parameterized.Parameters(name = "immediate={0}")
        fun modes() = listOf(false, true)
    }

    @Before
    fun answerMode() {
        immediate = answersAtOnce
    }

    @After
    fun suspendingAgain() {
        immediate = false
    }

    @get:Rule
    val main = MainDispatcherRule()

    private val models = ViewModelKeeper()

    @After
    fun clear() {
        models.clear()
        NetworkSignals.confirmation.value = null
    }

    private val summary =
        """{"id": 2, "slug": "stadtgarten", "name": "Stadtgarten", "description": "Gärtnern.", "color": "spring", "visibility": "public",
        |"banner_url": null, "members_count": 4, "role": "host"}
        """.trimMargin()

    private fun detail(member: Boolean) =
        """{"data": {"oasis": $summary, "url": "http://localhost/oasen/stadtgarten", "house_rules": [], "hosts": [],
        |"is_member": $member, "is_host": $member, "can_follow": true, "can_post": $member}}
        """.trimMargin()

    private val editForm =
        """{"data": {"oasis": $summary, "house_rules": "Freundlich bleiben.", "members": [{"id": 1, "username": "mira",
        |"display_name": "Mira Sol", "role": "host", "role_label": "Gastgeber:in"}, {"id": 3, "username": "aylin",
        |"display_name": "Aylin Demir", "role": "member", "role_label": "Mitglied"}], "options": {"visibilities":
        |[{"value": "public", "label": "Alle"}, {"value": "members", "label": "Nur Mitglieder"}], "colors": [{"value": "spring",
        |"label": "Quelle"}]}}}
        """.trimMargin()

    private val banner = ImageUpload(byteArrayOf(1, 2, 3), "banner.jpg", "image/jpeg")

    @Test
    fun `the overview loads once and again when an oasis changed`() = runTest {
        val network = StubbedNetwork(stub("GET", "network/oases", Fixtures.oases))
        val model = models.keep(OasesViewModel(network.repository))
        model.loadIfNeeded()
        model.loadIfNeeded()
        assertEquals(1, network.transport.requests.size)
        assertNotNull(model.directory.value.valueOrNull)

        NetworkSignals.didChangeOases()
        eventually { network.transport.requests.size == 2 }
    }

    @Test
    fun `the overview shows a failed first load and keeps the list when a refresh fails`() = runTest {
        val network = StubbedNetwork(stub("GET", "network/oases", """{"message":"Kaputt."}""", 500))
        val model = models.keep(OasesViewModel(network.repository))
        model.load()
        assertTrue(model.directory.value is Loadable.Failed)

        network.add("GET", "network/oases", json = Fixtures.oases)
        model.load()
        network.add("GET", "network/oases", status = 500, json = """{"message":"Kaputt."}""")
        model.load()
        assertNotNull(model.directory.value.valueOrNull)
        assertEquals("Kaputt.", model.error.value?.userMessage)
        model.dismissError()
        assertNull(model.error.value)
    }

    @Test
    fun `a non-member sees no member list, follows and reloads`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/oases/stadtgarten", detail(member = false)),
            stub("GET", "network/oases/stadtgarten/posts", pageJson(postJson(4))),
            stub("POST", "network/oases/stadtgarten/follow", """{"message":"Du folgst jetzt der Oase Stadtgarten."}""")
        )
        val model = models.keep(OasisViewModel("stadtgarten", network.repository))
        model.loadIfNeeded()
        model.loadIfNeeded()
        assertNull(model.members.value)
        assertEquals(2, network.transport.requests.size)

        model.setFollowing(true)
        assertEquals("Du folgst jetzt der Oase Stadtgarten.", model.status.value)
        model.dismissStatus()
        assertNull(model.status.value)
    }

    @Test
    fun `the last host cannot leave, and the member list stays empty when it cannot load`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/oases/stadtgarten", detail(member = true)),
            stub("GET", "network/oases/stadtgarten/posts", pageJson()),
            stub("GET", "network/oases/stadtgarten/members", """{"message":"Kaputt."}""", 500),
            stub("DELETE", "network/oases/stadtgarten/follow", """{"message":"Du bist die letzte Gastgeberin."}""", 422)
        )
        val model = models.keep(OasisViewModel("stadtgarten", network.repository))
        model.load()
        assertNull(model.members.value)

        val loads = network.transport.requests.size
        model.setFollowing(false)
        assertEquals("Du bist die letzte Gastgeberin.", model.error.value?.userMessage)
        assertEquals(loads + 1, network.transport.requests.size)

        model.updateMemberQuery("a".repeat(80))
        model.loadMembers()
        assertEquals("a".repeat(60), network.last("network/oases/stadtgarten/members").request.url.queryParameter("q"))
    }

    @Test
    fun `a published Tropfen reloads the oasis' Tropfen`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/oases/stadtgarten", detail(member = false)),
            stub("GET", "network/oases/stadtgarten/posts", pageJson(postJson(4)))
        )
        val model = models.keep(OasisViewModel("stadtgarten", network.repository))
        model.load()
        network.add("GET", "network/oases/stadtgarten/posts", json = pageJson(postJson(5), postJson(4)))
        NetworkSignals.didPublish(null, "Gespeichert.")
        eventually { model.posts.posts.size == 2 }
    }

    @Test
    fun `editing fills the form, saves and goes back`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/oases/stadtgarten/edit", editForm),
            stub("PUT", "network/oases/stadtgarten", """{"message":"Gespeichert.","data":{"slug":"stadtgarten"}}""")
        )
        val model = models.keep(OasisFormViewModel("stadtgarten", network.repository))
        assertTrue(model.editing)
        model.loadIfNeeded()
        model.loadIfNeeded()
        assertEquals(1, network.transport.requests.size)
        assertEquals("Freundlich bleiben.", model.state.value.input.houseRules)
        assertEquals(2, model.state.value.edit?.members?.size)

        model.updateInput { it.copy(name = "Stadtgarten Ost") }
        val before = NetworkSignals.oases.value
        model.save()
        assertEquals(OasisFormOutcome.Close, model.state.value.outcome)
        assertTrue(NetworkSignals.oases.value > before)
        assertTrue(network.last("network/oases/stadtgarten").body!!.contains("\"name\":\"Stadtgarten Ost\""))
        model.consumeOutcome()
        assertNull(model.state.value.outcome)
    }

    @Test
    fun `the form shows why it cannot load or save`() = runTest {
        val network = StubbedNetwork(stub("GET", "network/oases/stadtgarten/edit", """{"message":"Kein Zugriff."}""", 403))
        val model = models.keep(OasisFormViewModel("stadtgarten", network.repository))
        model.load()
        assertEquals("Kein Zugriff.", model.state.value.loadError?.userMessage)

        network.add("GET", "network/oases/stadtgarten/edit", json = editForm)
        network.add("PUT", "network/oases/stadtgarten", 422, """{"message":"Der Name fehlt.","errors":{"name":["Der Name fehlt."]}}""")
        model.load()
        assertNull(model.state.value.loadError)
        model.save()
        assertEquals("Der Name fehlt.", model.error.value?.message("name"))
        assertNull(model.state.value.outcome)
    }

    @Test
    fun `a banner is stored at once when editing and removed again`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/oases/stadtgarten/edit", editForm),
            stub("POST", "network/oases/stadtgarten/banner", """{"message":"Das Banner ist gespeichert."}"""),
            stub("DELETE", "network/oases/stadtgarten/banner", """{"message":"Das Banner ist entfernt."}""")
        )
        val model = models.keep(OasisFormViewModel("stadtgarten", network.repository))
        model.load()
        model.pickBanner(banner)
        assertEquals("Das Banner ist gespeichert.", model.status.value)
        assertNull(model.state.value.banner)
        assertTrue(network.last("network/oases/stadtgarten/banner").body!!.contains("filename=\"banner.jpg\""))

        model.removeBanner()
        assertEquals("Das Banner ist entfernt.", model.status.value)
        assertEquals(3, network.transport.requests.count { it.path == "network/oases/stadtgarten/edit" })
    }

    @Test
    fun `a refused banner keeps the form`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/oases/stadtgarten/edit", editForm),
            stub("POST", "network/oases/stadtgarten/banner", """{"message":"Zu groß.","errors":{"banner":["Zu groß."]}}""", 422),
            stub("DELETE", "network/oases/stadtgarten/banner", """{"message":"Kaputt."}""", 500)
        )
        val model = models.keep(OasisFormViewModel("stadtgarten", network.repository))
        model.load()
        model.pickBanner(banner)
        assertEquals("Zu groß.", model.error.value?.message("banner"))
        model.removeBanner()
        assertEquals("Kaputt.", model.error.value?.userMessage)
        assertEquals(1, network.transport.requests.count { it.path == "network/oases/stadtgarten/edit" })
    }

    @Test
    fun `a banner picked while founding waits for Oase eröffnen`() = runTest {
        val network = StubbedNetwork(stub("GET", "network/oases", Fixtures.oases))
        val model = models.keep(OasisFormViewModel(null, network.repository))
        assertFalse(model.editing)
        model.load()
        model.pickBanner(banner)
        assertEquals(banner, model.state.value.banner)
        assertEquals("Das Banner wird mit der Oase gespeichert.", model.status.value)

        model.removeBanner()
        assertNull(model.state.value.banner)
        assertEquals("Das Banner ist entfernt. Jetzt zeigen wir wieder die Farbe der Oase.", model.status.value)
        assertEquals(1, network.transport.requests.size)

        model.setRole(3, "host")
        assertEquals(1, network.transport.requests.size)
    }

    @Test
    fun `making someone host reloads, giving up the own role leaves the form`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/oases/stadtgarten/edit", editForm),
            stub(
                "PUT",
                "network/oases/stadtgarten/members/3/role",
                """{"message":"Aylin ist jetzt Gastgeberin.","data":{"still_host":true}}"""
            )
        )
        val model = models.keep(OasisFormViewModel("stadtgarten", network.repository))
        model.load()
        model.setRole(3, "host")
        assertEquals("Aylin ist jetzt Gastgeberin.", model.status.value)
        assertTrue(network.last("network/oases/stadtgarten/members/3/role").body!!.contains("\"role\":\"host\""))
        assertNull(model.state.value.outcome)

        network.add(
            "PUT",
            "network/oases/stadtgarten/members/1/role",
            json = """{"message":"Du bist jetzt Mitglied.","data":{"still_host":false}}"""
        )
        model.setRole(1, "member")
        assertEquals("Du bist jetzt Mitglied.", NetworkSignals.confirmation.value)
        assertEquals(OasisFormOutcome.Close, model.state.value.outcome)

        network.add(
            "PUT",
            "network/oases/stadtgarten/members/3/role",
            422,
            """{"message":"Es braucht eine Gastgeberin.","errors":{"role":["Es braucht eine Gastgeberin."]}}"""
        )
        model.setRole(3, "member")
        assertEquals("Es braucht eine Gastgeberin.", model.error.value?.message("role"))
    }

    @Test
    fun `closing needs the confirmation and hands the result to the overview`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/oases/stadtgarten/close", """{"data":{"name":"Stadtgarten","members_count":4,"posts_count":9}}"""),
            stub("DELETE", "network/oases/stadtgarten", """{"message":"Bitte bestätige.","errors":{"confirm":["Bitte bestätige."]}}""", 422)
        )
        val model = models.keep(CloseOasisViewModel("stadtgarten", network.repository))
        model.load()
        assertEquals(9, model.closing.value.valueOrNull?.postsCount)
        model.close()
        assertEquals("Bitte bestätige.", model.error.value?.message("confirm"))
        assertFalse(model.closed.value)

        network.add("DELETE", "network/oases/stadtgarten", json = """{"message":"Die Oase ist geschlossen."}""")
        model.setConfirmed(true)
        val before = NetworkSignals.oases.value
        model.close()
        assertTrue(model.closed.value)
        assertEquals("Die Oase ist geschlossen.", NetworkSignals.confirmation.value)
        assertTrue(NetworkSignals.oases.value > before)
        assertTrue(network.last("network/oases/stadtgarten").body!!.contains("\"confirm\":true"))
    }

    @Test
    fun `the host statistics load`() = runTest {
        val network = StubbedNetwork(
            stub(
                "GET",
                "network/oases/stadtgarten/statistics",
                """{"data":{"name":"Stadtgarten","totals":{"members":4,"hosts":1,"posts":9,"posts_30_days":3,"active_authors_30_days":2,
                |"removed":0},"weekly":[{"week":"2026-10-19T00:00:00.000000Z","joined":1,"posts":2}]}}
                """.trimMargin()
            )
        )
        val model = models.keep(OasisStatisticsViewModel("stadtgarten", network.repository))
        model.load()
        val stats = model.statistics.value.valueOrNull!!
        assertEquals(3, stats.totals.posts30Days)
        assertEquals(2, stats.weekly.single().posts)
    }
}
