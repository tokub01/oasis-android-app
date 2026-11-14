package de.velwebsolutions.oasis.network

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.network.api.NoContext
import de.velwebsolutions.oasis.network.api.OasisCollection
import de.velwebsolutions.oasis.network.api.OasisCollections
import de.velwebsolutions.oasis.network.ui.PostListState
import de.velwebsolutions.oasis.network.ui.components.CollectionActions
import de.velwebsolutions.oasis.network.ui.components.LocalCollectionActions
import de.velwebsolutions.oasis.network.ui.components.NetworkPage
import de.velwebsolutions.oasis.network.ui.components.PostCard
import de.velwebsolutions.oasis.network.ui.components.block
import de.velwebsolutions.oasis.network.ui.oases.CollectPostScreen
import de.velwebsolutions.oasis.network.ui.oases.CollectPostViewModel
import de.velwebsolutions.oasis.network.ui.oases.CollectionForm
import de.velwebsolutions.oasis.network.ui.oases.OasisCollectionActions
import de.velwebsolutions.oasis.network.ui.oases.OasisCollectionsUi
import de.velwebsolutions.oasis.network.ui.oases.OasisDetailScreen
import de.velwebsolutions.oasis.network.ui.oases.OasisViewModel
import de.velwebsolutions.oasis.network.ui.oases.collectionsSection
import de.velwebsolutions.oasis.network.ui.profiles.TimeCapsulesScreen
import de.velwebsolutions.oasis.network.ui.profiles.TimeCapsulesViewModel
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** „In Sammlung“ on a card, the Sammlungen of a crowd, the sheet and the gemeinsamen Zeitkapseln at 360 dp. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp")
class CollectionsAndCapsulesUiTest {
    @get:Rule val main = MainDispatcherRule()

    @get:Rule val compose = createComposeRule()

    private val navigator = Navigator()
    private val models = ViewModelKeeper()

    @After
    fun clear() = models.clear()

    private var screen by mutableStateOf<@Composable () -> Unit>({})

    private fun show(content: @Composable () -> Unit) {
        screen = content
        compose.setContent { OasisTheme { CompositionLocalProvider(LocalNavigator provides navigator) { screen() } } }
    }

    /** Shows [content] in place of the screen shown so far. */
    private fun switchTo(content: @Composable () -> Unit) {
        screen = content
        compose.waitForIdle()
    }

    /** A tap through the semantics: nodes inside one tall list item may lie outside the screen. */
    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    private fun capture(name: String) {
        compose.waitForIdle()
        val roots = compose.onAllNodes(isRoot())
        roots[roots.fetchSemanticsNodes().size - 1].captureRoboImage("build/outputs/roborazzi/network-$name.png")
    }

    private fun waitFor(text: String) =
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun opened(route: NetworkRoute) = MainTab.entries.any { route in navigator.stack(it) }

    @Test
    fun theCardNamesItsCollectionsAndItsMenuAddsAndRemoves() {
        val removed = mutableListOf<String>()
        show {
            NetworkPage {
                block("a") {
                    CompositionLocalProvider(
                        LocalCollectionActions provides CollectionActions { post, id ->
                            removed += "${post.id} $id"
                        }
                    ) {
                        PostCard(decodePost(collectedPostJson(3, 4)), onAction = { _, _ -> })
                    }
                }
            }
        }
        compose.onNodeWithText("In Sammlung:").assertExists()
        capture("post-collections")
        compose.onAllNodesWithTag("post.menu").onFirst().performClick()
        compose.onNodeWithText("Zu Sammlung hinzufügen …").performClick()
        assertEquals(NetworkRoute.CollectPost(3, "garten"), navigator.sheet)
        compose.onAllNodesWithTag("post.menu").onFirst().performClick()
        compose.onNodeWithText("Aus „Rezepte“ entfernen").performClick()
        compose.waitForIdle()
        assertEquals(listOf("3 4"), removed)
        navigator.dismissSheet()
        compose.onNodeWithTag("post.collection.4").performClick()
        assertTrue(opened(NetworkRoute.Oasis("garten", collection = 4)))
    }

    @Test
    fun theOasisShowsChoosesEditsAndDeletesCollections() {
        val network = StubbedNetwork(
            stub("GET", "network/oases/garten", oasisJson(member = true, pate = false)),
            stub("GET", "network/oases/garten/posts", pageJson()),
            stub("GET", "network/oases/garten/members", """{"data":{"members":[]}}"""),
            stub("GET", "network/oases/garten/meetups", """{"data":{"can_create":false,"members_create_meetups":false}}"""),
            stub("GET", "network/oases/garten/collections", collectionsJson()),
            stub("GET", "network/collections/4/posts", collectionPageJson(1, 1, collectedPostJson(3, 4))),
            stub("GET", "network/collections/6/posts", collectionPageJson(1, 1)),
            stub("POST", "network/oases/garten/collections", """{"message":"Die Sammlung ist angelegt.","data":${collectionJson(6)}}"""),
            stub("DELETE", "network/collections/4/posts/3", """{"message":"Aus „Rezepte“ entfernt.","data":${collectionJson(4)}}"""),
            stub("DELETE", "network/collections/4", """{"message":"Die Sammlung ist gelöscht."}"""),
            stub("PUT", "network/oases/garten/collection-settings", """{"data":{"members_create_collections":true}}""")
        )
        val model = models.keep(OasisViewModel("garten", network.repository))
        show { OasisDetailScreen("garten", model, atCollection = 4) }
        compose.waitUntil(15_000) { model.shownCollection.value?.posts?.isNotEmpty() == true }
        compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag("oasis.collections"))
        waitFor("Was wir kochen")
        capture("oasis-collections")

        compose.onNodeWithTag("collection.allowMembers").tap()
        compose.waitUntil(15_000) { model.collections.value?.membersCreateCollections == true }
        compose.onNodeWithTag("collection.edit").tap()
        compose.onNodeWithText("Sammlung ändern").assertExists()
        compose.onNodeWithTag("collection.close").tap()
        compose.onNodeWithTag("collection.create").tap()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("collection.name"))).performTextInput("Tipps")
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("collection.description"))).performTextInput("Für den Garten")
        compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag("collection.form"))
        capture("collection-form")
        compose.onNodeWithTag("collection.save").tap()
        waitFor("Die Sammlung ist angelegt.")

        compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag("oasis.collections"))
        compose.onNodeWithTag("collection.6").tap()
        compose.waitUntil(15_000) { model.shownCollectionId.value == 6 }
        waitFor("Noch kein Post in dieser Sammlung.")
        compose.onNodeWithTag("collection.4").tap()
        compose.waitUntil(15_000) { model.shownCollection.value?.posts?.isNotEmpty() == true && model.shownCollectionId.value == 4 }
        compose.onNodeWithTag("collection.delete").tap()
        compose.onNodeWithText("Ihre Posts bleiben in der Crowd.").assertExists()
        compose.onNodeWithTag("dialog.cancel").performClick()
        compose.onNodeWithTag("collection.delete").tap()
        compose.onNodeWithTag("dialog.confirm").performClick()
        compose.waitUntil(15_000) { model.shownCollectionId.value == null }
    }

    @Test
    fun theSheetListsTheCollectionsATropfenMayGoInto() {
        val network = StubbedNetwork(
            stub("GET", "network/oases/garten/collections", collectionsJson()),
            stub("POST", "network/collections/4/posts", """{"message":"Zu „Rezepte“ hinzugefügt.","data":${collectionJson(4)}}""")
        )
        navigator.present(NetworkRoute.CollectPost(3, "garten"))
        val model = models.keep(CollectPostViewModel(3, "garten", network.repository))
        show { CollectPostScreen(3, "garten", model) }
        waitFor("Rezepte · 2")
        capture("collect-post")
        compose.onNodeWithTag("collect.4").performClick()
        compose.waitUntil(15_000) { navigator.sheet == null }
        NetworkSignals.confirmation.value = null

        val empty = StubbedNetwork(stub("GET", "network/oases/garten/collections", """{"data":{"can_create":false,"collections":[]}}"""))
        val none = models.keep(CollectPostViewModel(3, "garten", empty.repository))
        switchTo { CollectPostScreen(3, "garten", none) }
        waitFor("Hier gibt es noch keine Sammlung")
    }

    @Test
    fun aSharedCapsuleIsSealedAndInvitationsAreAnswered() {
        val network = StubbedNetwork(
            stub("GET", "network/time-capsules", capsulesJson()),
            stub("GET", "network/time-capsules/invitees", """{"data":[${memberJson(2, "jonas")},${memberJson(3, "aylin")}]}"""),
            stub(
                "PUT",
                "network/time-capsules/invitations/1",
                """{"message":"Dein Beitrag ist gespeichert.","data":${invitationJson(1, "contributed", body = "\"Alles Liebe\"")}}"""
            ),
            stub(
                "DELETE",
                "network/time-capsules/invitations/2/contribution",
                """{"message":"Entfernt.","data":${invitationJson(2, "invited")}}"""
            ),
            stub(
                "DELETE",
                "network/time-capsules/invitations/6/contribution",
                """{"message":"Entfernt.","data":${invitationJson(6, "declined", canEdit = false)}}"""
            ),
            stub(
                "POST",
                "network/time-capsules/invitations/1/decline",
                """{"message":"Du hast abgelehnt.","data":${invitationJson(1, "declined", canEdit = false)}}"""
            )
        )
        val model = models.keep(TimeCapsulesViewModel(network.repository))
        show { TimeCapsulesScreen(model, atInvitations = true) }
        waitFor("lädt dich zu einer gemeinsamen Zeitkapsel ein")
        capture("capsule-invitations")
        waitFor("Die Zeit zum Beitragen ist vorbei.")
        waitFor("Dein Beitrag ist versiegelt")
        waitFor("Nicht erschienen: Die Zeitkapsel ist nicht aufgegangen.")
        compose.onNodeWithText("Dein Beitrag ist erschienen").tap()
        assertTrue(opened(NetworkRoute.Post(12)))

        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("capsule.contribution.1"))).performTextInput("Alles Liebe")
        compose.onNodeWithTag("capsule.contribute.1").tap()
        waitFor("Dein Beitrag ist gespeichert.")
        compose.onNodeWithTag("capsule.remove.2").tap()
        compose.waitUntil(15_000) { model.capsules.value.valueOrNull?.invitations?.get(1)?.body == null }
        compose.onNode(hasText("Beitrag entfernen") and hasAnyAncestor(hasTestTag("capsule.invitation.6"))).tap()
        compose.waitUntil(15_000) { model.capsules.value.valueOrNull?.invitations?.get(5)?.status == "declined" }
        compose.onNodeWithTag("capsule.decline.1").tap()
        compose.onNodeWithText("Dein Text wird gelöscht.").assertExists()
        compose.onNodeWithTag("dialog.cancel").performClick()
        compose.onNodeWithTag("capsule.decline.1").tap()
        compose.onNodeWithTag("dialog.confirm").performClick()
        compose.waitUntil(15_000) { model.capsules.value.valueOrNull?.invitations?.first()?.status == "declined" }

        compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag("capsule.shared"))
        compose.onNodeWithTag("capsule.shared").tap()
        compose.waitUntil(15_000) { model.candidates.value != null }
        compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag("capsule.invitee.jonas"))
        compose.onNodeWithTag("capsule.invitee.jonas").tap()
        compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag("capsule.invitees"))
        capture("capsule-shared-seal")
        assertEquals(setOf("jonas"), model.draft.value.invitees)
        compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag("capsule.shared.8"))
        compose.onNodeWithText("Jonas: Hat beigetragen").assertExists()
        compose.onNodeWithText("Aylin: Eingeladen").assertExists()
    }

    @Test
    fun belowOaseTheInvitationsStayWithTheWayToTheMembership() {
        val network = StubbedNetwork(
            stub("GET", "network/time-capsules", capsulesJson(available = false)),
            stub("GET", "network/time-capsules/invitees", """{"data":[]}""")
        )
        val model = models.keep(TimeCapsulesViewModel(network.repository))
        show { TimeCapsulesScreen(model) }
        waitFor("Zeitkapseln gibt es ab Mittendrin.")
        waitFor("lädt dich zu einer gemeinsamen Zeitkapsel ein")
        capture("capsule-locked-invitations")

        val friendless = StubbedNetwork(
            stub("GET", "network/time-capsules", capsulesJson().replace(""""invitations":[""", """"invitations":[],"x":[""")),
            stub("GET", "network/time-capsules/invitees", """{"data":[]}""")
        )
        val alone = models.keep(TimeCapsulesViewModel(friendless.repository))
        switchTo { TimeCapsulesScreen(alone) }
        compose.waitUntil(15_000) { alone.capsules.value.valueOrNull != null }
        compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag("capsule.shared"))
        compose.onNodeWithTag("capsule.shared").tap()
        waitFor("Noch niemand zum Einladen")
    }

    @Test
    fun theCollectionsSectionWithoutCollectionsRightsOrList() {
        val rezepte = decodeCollection(collectionJson(4)).copy(canEdit = false)
        fun ui(collections: List<OasisCollection>, chosen: Int?, shown: PostListState<NoContext>?, form: CollectionForm? = null) =
            OasisCollectionsUi(
                OasisCollections(canCreate = true, collections = collections),
                chosen,
                shown,
                null,
                form,
                OasisCollectionActions.None
            )
        show {
            NetworkPage {
                collectionsSection(ui(emptyList(), null, null, CollectionForm(error = ApiError.Forbidden("Nicht erlaubt."))), false)
            }
        }
        waitFor("Noch keine Sammlung.")
        waitFor("Nicht erlaubt.")
        compose.onNodeWithTag("collection.allowMembers").assertDoesNotExist()
        switchTo { NetworkPage { collectionsSection(ui(listOf(rezepte), 4, PostListState()), false) } }
        waitFor("Was wir kochen")
        compose.onNodeWithTag("collection.edit").assertDoesNotExist()
        switchTo { NetworkPage { collectionsSection(ui(listOf(rezepte), 4, null), true) } }
        compose.onNodeWithTag("collection.allowMembers").assertExists()
    }
}
