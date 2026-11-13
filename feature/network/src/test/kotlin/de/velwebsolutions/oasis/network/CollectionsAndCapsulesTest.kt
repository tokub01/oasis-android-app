package de.velwebsolutions.oasis.network

import de.velwebsolutions.oasis.network.api.CapsuleInvitation
import de.velwebsolutions.oasis.network.api.CollectionInput
import de.velwebsolutions.oasis.network.api.ContributionBody
import de.velwebsolutions.oasis.network.api.FeedPage
import de.velwebsolutions.oasis.network.api.NoContext
import de.velwebsolutions.oasis.network.api.OasisCollection
import de.velwebsolutions.oasis.network.api.OasisCollections
import de.velwebsolutions.oasis.network.api.Post
import de.velwebsolutions.oasis.network.api.TimeCapsule
import de.velwebsolutions.oasis.network.api.TimeCapsuleInput
import de.velwebsolutions.oasis.network.ui.PostListModel
import de.velwebsolutions.oasis.network.ui.oases.CollectPostViewModel
import de.velwebsolutions.oasis.network.ui.oases.OasisCollectionActions
import de.velwebsolutions.oasis.network.ui.oases.OasisCollectionsUi
import de.velwebsolutions.oasis.network.ui.oases.OasisViewModel
import de.velwebsolutions.oasis.network.ui.posts.PostDetailViewModel
import de.velwebsolutions.oasis.network.ui.profiles.CapsuleDraft
import de.velwebsolutions.oasis.network.ui.profiles.TimeCapsulesViewModel
import de.velwebsolutions.oasis.network.ui.profiles.capsuleMoment
import de.velwebsolutions.oasis.network.ui.profiles.invitationLine
import de.velwebsolutions.oasis.network.ui.profiles.inviteeStatus
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * Sammlungen of an oasis and gemeinsame Zeitkapseln (docs/oasis-network.md): what the view models send and how the
 * answers change what is shown.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class CollectionsAndCapsulesTest(private val answersAtOnce: Boolean) {
    companion object {
        /** Each test runs with answers that suspend and with answers on the calling thread. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "immediate={0}")
        fun modes() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }

    @get:Rule val main = MainDispatcherRule()

    private val models = ViewModelKeeper()

    @Before
    fun answerMode() {
        immediate = answersAtOnce
    }

    @After
    fun clear() {
        immediate = false
        models.clear()
    }

    @Test
    fun theOasisShowsItsCollectionsAndTheirTropfen() {
        val network = StubbedNetwork(
            stub("GET", "network/oases/garten", oasisJson(member = true, pate = false)),
            stub("GET", "network/oases/garten/posts", pageJson()),
            stub("GET", "network/oases/garten/members", """{"data":{"members":[]}}"""),
            stub("GET", "network/oases/garten/meetups", meetupsJson()),
            stub("GET", "network/oases/garten/collections", collectionsJson()),
            stub("GET", "network/collections/4/posts", collectionPageJson(1, 2, collectedPostJson(3, 4))),
            stub("POST", "network/oases/garten/collections", """{"message":"x","errors":{"name":["Den Namen gibt es schon."]}}""", 422),
            stub("PUT", "network/collections/4", """{"message":"Gespeichert.","data":${collectionJson(4)}}"""),
            stub("DELETE", "network/collections/4", """{"message":"Die Sammlung ist gelöscht."}"""),
            stub("PUT", "network/oases/garten/collection-settings", """{"data":{"members_create_collections":true}}""")
        )
        val model = models.keep(OasisViewModel("garten", network.repository))
        runBlocking {
            model.load()
            assertEquals(listOf("Rezepte", "Tipps"), model.collections.value?.collections?.map { it.name })
            model.chooseCollection(4)
            val shown = model.shownCollection.value!!
            assertEquals(listOf(3), shown.posts.map { it.id })
            assertEquals("2", shown.state.value.nextCursor)
            shown.loadMore()
            assertEquals("page=2", network.last("network/collections/4/posts").request.url.query)
            model.chooseCollection(4)
            assertNull("the chosen one again: none", model.shownCollection.value)

            model.saveCollection()
            model.openCollectionForm(null)
            model.editCollectionForm { it.copy(name = " Rezepte ") }
            model.saveCollection()
            assertEquals("Den Namen gibt es schon.", model.collectionForm.value?.error?.message("name"))
            model.closeCollectionForm()
            model.editCollectionForm { it.copy(name = "x") }
            assertNull(model.collectionForm.value)
            model.openCollectionForm(model.collections.value!!.collections.first())
            model.editCollectionForm { it.copy(description = "Was schmeckt") }
            model.saveCollection()
            assertTrue(""""description":"Was schmeckt"""" in network.last("network/collections/4").body.orEmpty())
            assertEquals("Gespeichert.", model.status.value)

            model.chooseCollection(4)
            model.deleteCollection(model.collections.value!!.collections.first())
            assertNull(model.shownCollection.value)
            model.setMembersCreateCollections(true)
            assertTrue(model.collections.value!!.membersCreateCollections)
            model.setMembersCreateCollections(false)
            model.loadIfNeeded()
            NetworkSignals.didChangeCollections()
        }
        eventually { network.transport.requests.count { it.path == "network/oases/garten/collections" } >= 4 }
    }

    @Test
    fun withoutCollectionsNothingChangesAndRefusalsAreShown() {
        val network = StubbedNetwork(
            stub("GET", "network/oases/garten", oasisJson(member = false, pate = false)),
            stub("GET", "network/oases/garten/posts", pageJson()),
            stub("DELETE", "network/collections/4", """{"message":"Server Error"}""", 500),
            stub("PUT", "network/oases/garten/collection-settings", """{"data":{"members_create_collections":true}}"""),
            stub("GET", "network/collections/9/posts", """{"data":[],"meta":{"current_page":1}}""")
        )
        val model = models.keep(OasisViewModel("garten", network.repository))
        runBlocking {
            model.load()
            assertNull("collections that cannot load are not shown", model.collections.value)
            model.setMembersCreateCollections(true)
            assertNull(model.collections.value)
            model.deleteCollection(decodeCollection(collectionJson(4)))
            assertTrue(model.error.value != null)
            model.chooseCollection(9)
            assertEquals(null, model.shownCollection.value?.state?.value?.nextCursor)
            model.loadIfNeeded()
        }
        val refused = StubbedNetwork(stub("PUT", "network/oases/garten/collection-settings", """{"message":"x"}""", 403))
        val other = models.keep(OasisViewModel("garten", refused.repository))
        runBlocking { other.setMembersCreateCollections(true) }
        assertTrue(other.error.value != null)
    }

    @Test
    fun aTropfenLeavesACollection() {
        val network = StubbedNetwork(
            stub("GET", "network/collections/4/posts", collectionPageJson(1, 1, collectedPostJson(3, 4), collectedPostJson(5, 4))),
            stub("DELETE", "network/collections/4/posts/3", """{"message":"Aus „Rezepte“ entfernt.","data":${collectionJson(4)}}"""),
            stub("DELETE", "network/collections/4/posts/5", """{"message":"Aus „Rezepte“ entfernt.","data":${collectionJson(4)}}"""),
            stub("DELETE", "network/collections/4/posts/7", """{"message":"Server Error"}""", 500),
            stub("GET", "network/posts/5", """{"data":{"post":${collectedPostJson(5, 4)}}}""")
        )
        val inCollection = PostListModel<NoContext>(network.repository, collectionId = 4) { _, _ -> collectionPagePosts(4) }
        val elsewhere = PostListModel<NoContext>(network.repository) { _, _ -> collectionPagePosts(4) }
        runBlocking {
            inCollection.reload()
            elsewhere.reload()
            inCollection.removeFromCollection(inCollection.posts.first(), 4)
            assertEquals(listOf(5), inCollection.posts.map { it.id })
            assertEquals("Aus „Rezepte“ entfernt.", inCollection.state.value.status)
            elsewhere.removeFromCollection(elsewhere.posts.last(), 4)
            assertEquals(listOf(3, 5), elsewhere.posts.map { it.id })
            assertTrue(elsewhere.posts.last().collections.isEmpty())
            elsewhere.removeFromCollection(decodePost(collectedPostJson(7, 4)), 4)
            assertTrue(elsewhere.state.value.actionError != null)

            val page = models.keep(PostDetailViewModel(5, network.repository))
            page.removeFromCollection(decodePost(collectedPostJson(5, 4)), 4)
            page.load()
            page.removeFromCollection(page.detail.value.valueOrNull!!.post, 4)
            assertTrue(page.detail.value.valueOrNull!!.post.collections.isEmpty())
            page.removeFromCollection(decodePost(collectedPostJson(7, 4)), 4)
            assertTrue(page.error.value != null)
        }
    }

    @Test
    fun theSheetAddsATropfenToACollection() {
        val network = StubbedNetwork(
            stub("GET", "network/oases/garten/collections", collectionsJson()),
            stub("POST", "network/collections/4/posts", """{"message":"Zu „Rezepte“ hinzugefügt.","data":${collectionJson(4)}}"""),
            stub(
                "POST",
                "network/collections/6/posts",
                """{"message":"x","errors":{"post_id":["Der Post ist schon in dieser Sammlung."]}}""",
                422
            )
        )
        val sheet = models.keep(CollectPostViewModel(3, "garten", network.repository))
        runBlocking {
            sheet.load()
            assertEquals("only where it may go", listOf(4), sheet.collections.value.valueOrNull?.map { it.id })
            sheet.add(decodeCollection(collectionJson(6)))
            assertFalse(sheet.done.value)
            assertTrue(sheet.error.value != null)
            sheet.add(sheet.collections.value.valueOrNull!!.first())
            assertEquals("""{"post_id":3}""", network.last("network/collections/4/posts").body.orEmpty())
            assertTrue(sheet.done.value)
            assertEquals("Zu „Rezepte“ hinzugefügt.", NetworkSignals.confirmation.value)
        }
        NetworkSignals.confirmation.value = null
    }

    @Test
    fun aCapsuleIsSealedTogether() {
        val network = StubbedNetwork(
            stub("GET", "network/time-capsules", capsulesJson()),
            stub("GET", "network/time-capsules/invitees", """{"data":[${memberJson(2, "jonas")},${memberJson(3, "aylin")}]}"""),
            stub("POST", "network/time-capsules", """{"message":"Deine Zeitkapsel ist versiegelt."}""")
        )
        val model = models.keep(TimeCapsulesViewModel(network.repository))
        runBlocking {
            model.load()
            model.setShared(true)
            assertEquals(listOf("jonas", "aylin"), model.candidates.value?.map { it.username })
            model.updateOasis(2)
            assertEquals("oasis_id=2", network.last("network/time-capsules/invitees").request.url.query)
            model.toggleInvitee("jonas")
            model.toggleInvitee("aylin")
            model.toggleInvitee("aylin")
            repeat(12) { model.toggleInvitee("person$it") }
            assertEquals("at most 10", 10, model.draft.value.invitees.size)
            model.updateContributeUntil(Instant.parse("2026-12-24T16:00:00Z"))
            model.updateBody("Frohe Weihnachten")
            model.seal()
            val body = network.transport.requests.last { it.request.method == "POST" && it.path == "network/time-capsules" }.body.orEmpty()
            assertTrue(""""invitees":["jonas",""" in body)
            assertTrue(""""contribute_until":"""" in body)
            assertFalse(model.draft.value.shared)
            model.updateOasis(null)
            model.setShared(false)
        }
        val alone =
            CapsuleDraft(
                body = "x",
                opensAt = Instant.parse("2026-12-24T17:00:00Z"),
                invitees = setOf("jonas"),
                contributeUntil = Instant.now()
            )
        assertEquals(TimeCapsuleInput("x", CapsuleDraft.wireTime(alone.opensAt)), alone.input)
        assertEquals("Hat beigetragen", inviteeStatus("contributed"))
        assertEquals("Abgelehnt", inviteeStatus("declined"))
        assertEquals("Erschienen", inviteeStatus("published"))
        assertEquals("Nicht erschienen", inviteeStatus("failed"))
        assertEquals("Eingeladen", inviteeStatus("invited"))
        assertEquals("24. Dezember 2026 um 18:00 Uhr", capsuleMoment(Instant.parse("2026-12-24T17:00:00Z")))
    }

    @Test
    fun anInviteeWritesChangesRemovesAndDeclines() {
        val network = StubbedNetwork(
            stub("GET", "network/time-capsules", capsulesJson()),
            stub(
                "PUT",
                "network/time-capsules/invitations/1",
                """{"message":"Dein Beitrag ist gespeichert.","data":${invitationJson(1, "contributed", body = "\"Alles Liebe\"")}}"""
            ),
            stub("PUT", "network/time-capsules/invitations/2", """{"message":"x","errors":{"body":["Bitte schreib etwas."]}}""", 422),
            stub("DELETE", "network/time-capsules/invitations/2/contribution", """{"message":"Server Error"}""", 500),
            stub(
                "POST",
                "network/time-capsules/invitations/1/decline",
                """{"message":"Du hast abgelehnt.","data":${invitationJson(1, "declined")}}"""
            ),
            stub(
                "DELETE",
                "network/time-capsules/invitations/1/contribution",
                """{"message":"Dein Beitrag ist entfernt.","data":${invitationJson(1, "invited")}}"""
            )
        )
        val model = models.keep(TimeCapsulesViewModel(network.repository))
        runBlocking {
            model.saveContribution(decodeInvitation(invitationJson(1, "invited")))
            model.load()
            val first = model.capsules.value.valueOrNull!!.invitations.first()
            model.updateContribution(1, "Alles Liebe")
            assertEquals("Alles Liebe", model.contributionOf(first))
            model.saveContribution(first)
            assertEquals("""{"body":"Alles Liebe"}""", network.last("network/time-capsules/invitations/1").body.orEmpty())
            assertEquals("contributed", model.capsules.value.valueOrNull!!.invitations.first().status)
            assertTrue(model.contributions.value.isEmpty())

            val second = model.capsules.value.valueOrNull!!.invitations[1]
            model.saveContribution(second)
            assertEquals("Bitte schreib etwas.", model.contributionErrors.value[2])
            model.removeContribution(second)
            assertTrue(model.contributionErrors.value.isEmpty())
            assertTrue(model.error.value != null)

            model.removeContribution(first)
            model.decline(first)
            assertEquals("declined", model.capsules.value.valueOrNull!!.invitations.first().status)
            assertEquals("Du hast abgelehnt.", model.status.value)
        }
        val invitation = decodeInvitation(invitationJson(1, "invited"))
        assertEquals(
            "Geht auf am 24. Dezember 2026 um 18:00 Uhr in der Quelle von Mira Sol · Beitragen bis 24. Dezember 2026 um 17:00 Uhr",
            invitationLine(invitation)
        )
        assertEquals(
            "Geht auf am 24. Dezember 2026 um 18:00 Uhr in der Crowd Stadtgarten",
            invitationLine(
                invitation.copy(oasis = de.velwebsolutions.oasis.network.api.NamedOasis("garten", "Stadtgarten"), contributeUntil = null)
            )
        )
    }

    @Test
    fun theEdgesOfCollectionsAndInvitations() {
        val network = StubbedNetwork(
            stub("GET", "network/oases/garten", oasisJson(member = false, pate = false)),
            stub("GET", "network/oases/garten/posts", pageJson()),
            stub("GET", "network/oases/garten/collections", collectionsJson().replace(""""description":"Was wir kochen",""", "")),
            stub("GET", "network/collections/4/posts", collectionPageJson(1, 1, collectedPostJson(3, 4))),
            stub("DELETE", "network/collections/6", """{"message":"Die Sammlung ist gelöscht."}"""),
            stub("DELETE", "network/collections/4/posts/3", """{"message":"Entfernt.","data":${collectionJson(4)}}"""),
            stub("GET", "network/posts/3", """{"data":{"post":${collectedPostJson(3, 4, 6)}}}"""),
            stub("GET", "network/time-capsules/invitees", """{"message":"Server Error"}""", 500)
        )
        val model = models.keep(OasisViewModel("garten", network.repository))
        runBlocking {
            model.load()
            model.openCollectionForm(model.collections.value!!.collections.first())
            assertEquals("", model.collectionForm.value?.description)
            model.chooseCollection(4)
            model.deleteCollection(decodeCollection(collectionJson(6)))
            assertEquals("another one gone: the chosen one stays", 4, model.shownCollectionId.value)
        }
        NetworkSignals.didChangeCollections()
        eventually { network.transport.requests.count { it.path == "network/collections/4/posts" } >= 2 }
        runBlocking {
            model.chooseCollection(4)
            model.deleteCollection(decodeCollection(collectionJson(6)))
            assertNull(model.shownCollectionId.value)
        }

        val other = PostListModel<NoContext>(network.repository, collectionId = 6) { _, _ ->
            FeedPage(listOf(decodePost(collectedPostJson(3, 4, 6)), decodePost(postJson(5))))
        }
        val page = models.keep(PostDetailViewModel(3, network.repository))
        val capsules = models.keep(TimeCapsulesViewModel(network.repository))
        runBlocking {
            other.reload()
            other.removeFromCollection(other.posts.first(), 4)
            assertEquals("out of another collection: it stays", listOf(3, 5), other.posts.map { it.id })
            assertEquals(listOf(6), other.posts.first().collections.map { it.id })
            page.load()
            page.removeFromCollection(page.detail.value.valueOrNull!!.post, 4)
            assertEquals(listOf(6), page.detail.value.valueOrNull!!.post.collections.map { it.id })

            capsules.updateContribution(1, "x".repeat(600))
            assertEquals(500, capsules.contributionOf(decodeInvitation(invitationJson(1, "invited"))).length)
            capsules.setShared(true)
            assertEquals("no one to invite when the list cannot load", emptyList<Any>(), capsules.candidates.value)
        }
    }

    @Test
    fun theNewModelsHaveTheirDefaults() {
        val collection = OasisCollection(1, "Rezepte")
        assertEquals(0, collection.postsCount)
        assertTrue(collection.creator == null && !collection.canEdit && !collection.canAdd && collection.description == null)
        val none = OasisCollections()
        assertTrue(none.collections.isEmpty() && !none.canCreate && !none.membersCreateCollections)
        assertEquals("", CollectionInput().name)
        assertFalse(Post.CollectionRef(1, "Rezepte").canRemove)
        val member = decodeInvitation(invitationJson(1, "invited")).from
        val invitation = CapsuleInvitation(1, member, opensAt = Instant.EPOCH)
        assertEquals("invited", invitation.status)
        assertTrue(invitation.oasis == null && invitation.body == null && !invitation.canEdit && invitation.postId == null)
        assertEquals("Mira Sol", TimeCapsule.Invitee(member, "invited").member.displayName)
        assertTrue(TimeCapsule.Shared().invitees.isEmpty())
        assertEquals("x", ContributionBody("x").body)
        assertNull(OasisCollectionsUi.None.chosen)
        val listed =
            OasisCollectionsUi(OasisCollections(collections = listOf(collection)), 9, null, null, null, OasisCollectionActions.None)
        assertNull("a chosen one no longer listed", listed.chosen)
        assertNull("none chosen", OasisCollectionsUi(listed.collections, null, null, null, null, OasisCollectionActions.None).chosen)
    }
}

fun collectionJson(id: Int, canAdd: Boolean = true) = """
    {"id":$id,"name":"${if (id == 4) "Rezepte" else "Tipps"}","description":"Was wir kochen","posts_count":2,
    "creator":${memberJson(2, "jonas")},"can_edit":true,"can_add":$canAdd}
""".trimIndent()

fun collectionsJson() = """
    {"data":{"can_create":true,"members_create_collections":false,"collections":[${collectionJson(
    4
)},${collectionJson(6, canAdd = false)}]}}
""".trimIndent()

fun memberJson(id: Int, username: String) =
    """{"id":$id,"username":"$username","display_name":"${username.replaceFirstChar(Char::uppercase)}","initials":"X"}"""

/** A Tropfen of the oasis „garten“ in the collections [collections] (4 is „Rezepte“). */
fun collectedPostJson(id: Int, vararg collections: Int): String {
    val refs = collections.joinToString(",") { """{"id":$it,"name":"${if (it == 4) "Rezepte" else "Tipps"}","can_remove":true}""" }
    return postJson(id)
        .replace(""""oasis":null""", """"oasis":{"slug":"garten","name":"Stadtgarten","color":"spring"}""")
        .trimEnd().removeSuffix("}") + ""","collections":[$refs]}"""
}

fun collectionPageJson(current: Int, last: Int, vararg posts: String) =
    """{"data":[${posts.joinToString(",")}],"meta":{"current_page":$current,"last_page":$last}}"""

/** The cards of [collectionPageJson] as a list page (for list models built without the oasis page). */
suspend fun de.velwebsolutions.oasis.network.data.NetworkRepository.collectionPagePosts(id: Int) =
    de.velwebsolutions.oasis.network.api.FeedPage<NoContext>(collectionPosts(id, 1).data)

fun decodeCollection(json: String) = de.velwebsolutions.oasis.core.network.OasisJson.snake.decodeFromString(
    de.velwebsolutions.oasis.network.api.OasisCollection.serializer(),
    json
)

fun invitationJson(id: Int, status: String, body: String = "null", canEdit: Boolean = true, post: String = "null") = """
    {"id":$id,"from":{"id":1,"username":"mira","display_name":"Mira Sol","initials":"MS"},"oasis":null,
    "opens_at":"2026-12-24T17:00:00Z","contribute_until":"2026-12-24T16:00:00Z","status":"$status","body":$body,
    "can_edit":$canEdit,"post_id":$post,"failed_reason":${if (status == "failed") "\"Die Zeitkapsel ist nicht aufgegangen.\"" else "null"}}
""".trimIndent()

fun decodeInvitation(json: String) = de.velwebsolutions.oasis.core.network.OasisJson.snake.decodeFromString(
    de.velwebsolutions.oasis.network.api.CapsuleInvitation.serializer(),
    json
)

fun capsulesJson(available: Boolean = true) = """
    {"data":{"available":$available,"locked_message":"Zeitkapseln gibt es ab Mittendrin.","max_length":500,"max_waiting":50,"max_years":10,
    "oases":[{"id":2,"slug":"garten","name":"Stadtgarten"}],
    "waiting":[{"id":8,"body":"Für uns alle","opens_at":"2026-12-24T17:00:00Z","oasis":null,
    "shared":{"contribute_until":"2026-12-24T16:00:00Z","invitees":[{"member":${memberJson(2, "jonas")},"status":"contributed"},
    {"member":${memberJson(3, "aylin")},"status":"invited"}]}}],"past":[],
    "invitations":[${invitationJson(1, "invited")},${invitationJson(2, "contributed", body = "\"Hallo\"")},
    ${invitationJson(3, "contributed", body = "\"Versiegelt\"", canEdit = false)},${invitationJson(4, "invited", canEdit = false)},
    ${invitationJson(5, "published", post = "12", canEdit = false)},${invitationJson(6, "failed", body = "\"Schade\"", canEdit = false)},
    ${invitationJson(7, "declined", canEdit = false)}]}}
""".trimIndent()
