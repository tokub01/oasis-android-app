package de.velwebsolutions.oasis.network

import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.design.components.AvatarTint
import de.velwebsolutions.oasis.network.api.FeedPage
import de.velwebsolutions.oasis.network.api.ImageUpload
import de.velwebsolutions.oasis.network.api.NoContext
import de.velwebsolutions.oasis.network.data.NetworkRepository
import de.velwebsolutions.oasis.network.ui.profiles.EditProfileViewModel
import de.velwebsolutions.oasis.network.ui.profiles.FriendsViewModel
import de.velwebsolutions.oasis.network.ui.profiles.InvitationGroupViewModel
import de.velwebsolutions.oasis.network.ui.profiles.InvitationViewModel
import de.velwebsolutions.oasis.network.ui.profiles.InvitationsViewModel
import de.velwebsolutions.oasis.network.ui.profiles.ProfileAction
import de.velwebsolutions.oasis.network.ui.profiles.ProfileConfirmation
import de.velwebsolutions.oasis.network.ui.profiles.ProfileViewModel
import de.velwebsolutions.oasis.network.ui.profiles.StatisticsViewModel
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Profiles, the own profile form, friendships, the own numbers and invitations. */
@RunWith(Parameterized::class)
class ProfileViewModelsTest(private val answersAtOnce: Boolean) {
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

    private fun profileNetwork(vararg more: de.velwebsolutions.oasis.testing.StubTransport.Stub) = StubbedNetwork(
        stub("GET", "network/profiles/jonas", Fixtures.profile),
        stub("GET", "network/profiles/jonas/posts", pageJson()),
        *more
    )

    @Test
    fun `every profile button calls its endpoint and reloads`() = runTest {
        val ok = """{"message":"Erledigt."}"""
        val network = profileNetwork(
            stub("POST", "network/profiles/jonas/follow", ok),
            stub("DELETE", "network/profiles/jonas/follow", ok),
            stub("POST", "network/profiles/jonas/friendship", ok),
            stub("POST", "network/profiles/jonas/friendship/accept", ok),
            stub("DELETE", "network/profiles/jonas/friendship", ok),
            stub("POST", "network/users/2/mute", ok),
            stub("DELETE", "network/users/2/mute", ok),
            stub("POST", "network/users/2/restrict", ok),
            stub("DELETE", "network/users/2/restrict", ok)
        )
        val model = models.keep(ProfileViewModel("Jonas", network.repository))
        assertEquals("jonas", model.handle)
        model.loadIfNeeded()
        model.loadIfNeeded()
        val expected = listOf(
            ProfileAction.Follow to "POST network/profiles/jonas/follow",
            ProfileAction.Unfollow to "DELETE network/profiles/jonas/follow",
            ProfileAction.RequestFriend to "POST network/profiles/jonas/friendship",
            ProfileAction.AcceptFriend to "POST network/profiles/jonas/friendship/accept",
            ProfileAction.RemoveFriend to "DELETE network/profiles/jonas/friendship",
            ProfileAction.Mute to "POST network/users/2/mute",
            ProfileAction.Unmute to "DELETE network/users/2/mute",
            ProfileAction.Restrict to "POST network/users/2/restrict",
            ProfileAction.Unrestrict to "DELETE network/users/2/restrict"
        )
        expected.forEach { (action, call) ->
            val before = network.transport.requests.size
            model.perform(action)
            val sent = network.transport.requests.drop(before).map { "${it.request.method} ${it.path}" }
            assertEquals(listOf(call, "GET network/profiles/jonas", "GET network/profiles/jonas/posts"), sent)
            assertEquals("Erledigt.", model.status.value)
        }
    }

    @Test
    fun `a refused button shows the error, and nothing happens before the profile is there`() = runTest {
        val network = profileNetwork(
            stub("POST", "network/profiles/jonas/friendship", """{"message":"Jonas nimmt keine Anfragen an."}""", 403),
            stub("POST", "network/users/2/block", """{"message":"Kaputt."}""", 500)
        )
        val model = models.keep(ProfileViewModel("jonas", network.repository))
        model.perform(ProfileAction.Follow)
        assertTrue(network.transport.requests.isEmpty())

        model.load()
        model.perform(ProfileAction.RequestFriend)
        assertEquals("Jonas nimmt keine Anfragen an.", model.error.value?.userMessage)
        model.perform(ProfileAction.Block)
        assertEquals("Kaputt.", model.error.value?.userMessage)
        assertFalse(model.leave.value)
    }

    @Test
    fun `a missing profile shows its error, and changes elsewhere reload it`() = runTest {
        val network = StubbedNetwork(stub("GET", "network/profiles/jonas", """{"message":"Nicht gefunden."}""", 404))
        val model = models.keep(ProfileViewModel("jonas", network.repository))
        model.load()
        assertTrue(model.profile.value is Loadable.Failed)
        assertEquals(1, network.transport.requests.size)

        network.add("GET", "network/profiles/jonas", json = Fixtures.profile)
        network.add("GET", "network/profiles/jonas/posts", json = pageJson(postJson(5)))
        NetworkSignals.didChangeProfile()
        eventually { model.posts.posts.size == 1 }
        NetworkSignals.didPublish(null, "Gespeichert.")
        eventually { network.transport.requests.count { it.path == "network/profiles/jonas/posts" } == 2 }
    }

    @Test
    fun `Tropfen whose load was cancelled with the profile load again with it`() = runTest {
        val asked = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val network = profileNetwork(stub("GET", "network/profiles/jonas/posts", pageJson(postJson(5))))
        val slow = object : NetworkRepository by network.repository {
            override suspend fun profilePosts(handle: String, cursor: String?): FeedPage<NoContext> {
                asked.complete(Unit)
                gate.await()
                return network.repository.profilePosts(handle, cursor)
            }
        }
        val model = models.keep(ProfileViewModel("jonas", slow))
        // A rotation while the profile's Tropfen load cancels the screen's coroutine.
        val screen = launch { model.loadIfNeeded() }
        asked.await()
        screen.cancelAndJoin()
        assertEquals(Loadable.Idle, model.posts.state.value.state)

        gate.complete(Unit)
        model.loadIfNeeded()
        assertEquals(listOf(5), model.posts.posts.map { it.id })
    }

    @Test
    fun `the status line is cut at 80 characters counted like the server, never inside an emoji`() {
        val network = StubbedNetwork()
        val model = models.keep(EditProfileViewModel(network.repository, network.common))
        model.updateStatus("x".repeat(79) + "🌱🌱")
        assertEquals("x".repeat(79) + "🌱", model.form.value.status)
        model.updateStatus("🌱".repeat(90))
        assertEquals("🌱".repeat(80), model.form.value.status)
    }

    @Test
    fun `the chosen colour of the initials comes back into the form`() = runTest {
        val chosen = Fixtures.ownProfile.replace("\"has_photo\"", "\"avatar_color_choice\":\"night\",\"has_photo\"")
        val network = StubbedNetwork(stub("GET", "network/profile", chosen))
        val model = models.keep(EditProfileViewModel(network.repository, network.common))
        model.loadIfNeeded()
        assertEquals("night", model.form.value.color)
    }

    private val savedProfile get() = Fixtures.ownProfile.replaceFirst("{\"data\"", "{\"message\":\"Dein Profil ist gespeichert.\",\"data\"")

    @Test
    fun `the own profile form loads, checks the tone and saves`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/profile", Fixtures.ownProfile),
            stub("POST", "network/tone", """{"data":{"hint":"Idiot","nudge":true}}"""),
            stub("PUT", "network/profile", savedProfile)
        )
        val model = models.keep(EditProfileViewModel(network.repository, network.common))
        model.checkTone()
        assertTrue(network.transport.requests.isEmpty())

        model.loadIfNeeded()
        model.loadIfNeeded()
        assertEquals("„Automatisch“ when the server names no choice", AvatarTint.AUTOMATIC, model.form.value.color)
        assertEquals("Leipzig", model.form.value.location)
        assertEquals("Im Urlaub bis Sonntag", model.form.value.status)
        model.updateStatus("x".repeat(90))
        assertEquals(80, model.form.value.status.length)
        model.updateStatus("")
        model.updateBio("Du Idiot")
        model.updateLocation("Halle")
        model.updateColor("night")
        model.checkTone()
        assertEquals("Idiot", model.form.value.toneHint)
        assertTrue(model.form.value.toneNudge)
        assertTrue(network.last("network/tone").body!!.contains("Du Idiot Halle"))

        model.setToneConfirmed(true)
        val before = NetworkSignals.profile.value
        model.save()
        val sent = network.last("network/profile").body!!
        assertTrue(sent.contains("\"avatar_color\":\"night\""))
        assertTrue(sent.contains("\"tone_confirmed\":true"))
        // An empty status is sent, so it clears the line.
        assertTrue(sent.contains("\"status\":\"\""))
        assertEquals("Dein Profil ist gespeichert.", model.status.value)
        assertTrue(NetworkSignals.profile.value > before)
        assertNull(model.form.value.toneHint)
        assertFalse(model.form.value.toneConfirmed)
    }

    @Test
    fun `a refused profile asks about the tone, and a failed tone check changes nothing`() = runTest {
        val question = "Nochmal lesen? Deine Beschreibung klingt verletzend."
        val network = StubbedNetwork(
            stub("GET", "network/profile", Fixtures.ownProfile),
            stub("POST", "network/tone", """{"message":"Kaputt."}""", 500),
            stub("PUT", "network/profile", """{"message":"$question","errors":{"bio":["$question"]}}""", 422)
        )
        val model = models.keep(EditProfileViewModel(network.repository, network.common))
        model.load()
        model.checkTone()
        assertNull(model.form.value.toneHint)
        model.save()
        assertTrue(model.form.value.toneNudge)
        assertEquals(question, model.error.value?.message("bio"))

        network.add("PUT", "network/profile", 422, """{"message":"Der Ort ist zu lang.","errors":{"location":["Der Ort ist zu lang."]}}""")
        model.save()
        assertEquals("Der Ort ist zu lang.", model.error.value?.message("location"))

        network.add(
            "PUT",
            "network/profile",
            422,
            """{"message":"Zu lang.","errors":{"status":["Der Status darf höchstens 80 Zeichen haben."]}}"""
        )
        model.save()
        assertEquals("Der Status darf höchstens 80 Zeichen haben.", model.error.value?.message("status"))
    }

    @Test
    fun `photo and banner are stored and removed at once`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/profile", Fixtures.ownProfile),
            stub("POST", "network/profile/photo", savedProfile),
            stub("DELETE", "network/profile/photo", savedProfile),
            stub("POST", "network/profile/banner", savedProfile),
            stub("DELETE", "network/profile/banner", savedProfile)
        )
        val model = models.keep(EditProfileViewModel(network.repository, network.common))
        model.load()
        val image = ImageUpload(byteArrayOf(9), "profilbild.jpg", "image/jpeg")

        model.setUploading(true)
        model.uploadPhoto(null)
        assertFalse(model.form.value.uploading)
        assertEquals(1, network.transport.requests.size)

        model.uploadPhoto(image)
        assertTrue(network.last("network/profile/photo").body!!.contains("name=\"photo\"; filename=\"profilbild.jpg\""))
        model.uploadBanner(image)
        assertTrue(network.last("network/profile/banner").body!!.contains("name=\"banner\""))
        model.removePhoto()
        model.removeBanner()
        assertFalse(model.form.value.uploading)
        assertEquals("Dein Profil ist gespeichert.", model.status.value)
        assertEquals(
            listOf("POST", "POST", "DELETE", "DELETE"),
            network.transport.requests.drop(1).map { it.request.method }
        )
    }

    @Test
    fun `a refused photo shows its error and a failed load the error page`() = runTest {
        val network = StubbedNetwork(stub("GET", "network/profile", """{"message":"Kaputt."}""", 500))
        val model = models.keep(EditProfileViewModel(network.repository, network.common))
        model.load()
        assertTrue(model.profile.value is Loadable.Failed)

        network.add("GET", "network/profile", json = Fixtures.ownProfile)
        network.add("POST", "network/profile/photo", 422, """{"message":"Kein Bild.","errors":{"photo":["Kein Bild."]}}""")
        model.load()
        model.uploadPhoto(ImageUpload(byteArrayOf(1), "x.jpg", "image/jpeg"))
        assertEquals("Kein Bild.", model.error.value?.message("photo"))
        assertFalse(model.form.value.uploading)
    }

    @Test
    fun `friendships are accepted, declined and ended`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/friends", Fixtures.friends),
            stub("POST", "network/friends/3/accept", """{"message":"Ihr seid jetzt befreundet."}"""),
            stub("DELETE", "network/friends/3", """{"message":"Erledigt."}""")
        )
        val model = models.keep(FriendsViewModel(network.repository))
        model.loadIfNeeded()
        model.loadIfNeeded()
        model.accept(3)
        assertEquals("Ihr seid jetzt befreundet.", model.status.value)
        model.remove(3)
        assertEquals("Erledigt.", model.status.value)
        assertEquals(3, network.transport.requests.count { it.path == "network/friends" })

        network.add("DELETE", "network/friends/3", 422, """{"message":"Nicht gefunden."}""")
        model.remove(3)
        assertEquals("Nicht gefunden.", model.error.value?.userMessage)
        assertEquals(3, network.transport.requests.count { it.path == "network/friends" })
    }

    @Test
    fun `the own numbers load once`() = runTest {
        val network = StubbedNetwork(stub("GET", "network/statistics", Fixtures.statistics))
        val model = models.keep(StatisticsViewModel(network.repository))
        model.loadIfNeeded()
        model.loadIfNeeded()
        assertEquals(1, network.transport.requests.size)
        assertTrue(model.statistics.value.valueOrNull != null)
    }

    @Test
    fun `a new invitation link reloads, the budget message stays on the page`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/invitations", Fixtures.invitations),
            stub("POST", "network/invitations", """{"message":"Dein Link ist da."}""", 201)
        )
        val model = models.keep(InvitationsViewModel(network.repository))
        model.loadIfNeeded()
        model.loadIfNeeded()
        model.create()
        assertEquals("Dein Link ist da.", model.status.value)
        assertEquals(2, network.transport.requests.count { it.path == "network/invitations" && it.request.method == "GET" })

        model.dismissStatus()
        network.add("POST", "network/invitations", 422, """{"message":"Keine Links mehr.","errors":{"invitation":["Keine Links mehr."]}}""")
        model.create()
        assertEquals("Keine Links mehr.", model.error.value?.message("invitation"))
        assertNull(model.status.value)

        network.add("POST", "network/invitations", 500, """{"message":"Kaputt."}""")
        model.create()
        assertEquals("Kaputt.", model.status.value)
    }

    @Test
    fun `a group is founded with name and target, refusals stay at their fields`() = runTest {
        val created = """{"message":"Eure Gruppe ist angelegt. Teile den Gruppenlink.","data":{"id":3,"name":"Chor","target":12,""" +
            """"joined":0,"progress":"0 von 12 haben angenommen","open":true,"expires_at":"2027-02-05T10:00:00.000000Z",""" +
            """"token":"t","url":"http://localhost/gemeinsam/t","members":[]}}"""
        val network = StubbedNetwork(
            stub("GET", "network/invitations", Fixtures.invitations),
            stub("POST", "network/invitations/groups", created, 201)
        )
        val model = models.keep(InvitationsViewModel(network.repository))
        model.loadIfNeeded()
        model.setGroupName("  Chor ")
        model.setGroupTarget("1a2")
        assertEquals("only digits reach the field", "12", model.groupTarget.value)
        model.createGroup()
        assertEquals("Eure Gruppe ist angelegt. Teile den Gruppenlink.", model.status.value)
        assertEquals(JsonPrimitive("Chor"), network.transport.lastBody("network/invitations/groups")?.jsonObject?.get("name"))
        assertEquals(JsonPrimitive(12), network.transport.lastBody("network/invitations/groups")?.jsonObject?.get("target"))
        assertEquals("the form empties", "", model.groupName.value)
        assertEquals("", model.groupTarget.value)
        assertEquals(2, network.transport.requests.count { it.path == "network/invitations" && it.request.method == "GET" })

        model.dismissStatus()
        model.setGroupName("X")
        network.add("POST", "network/invitations/groups", 422, """{"message":"Zu kurz.","errors":{"name":["Zu kurz."]}}""")
        model.createGroup()
        assertEquals("Zu kurz.", model.error.value?.message("name"))
        assertNull(model.status.value)
        assertEquals("a refusal keeps the typed name", "X", model.groupName.value)
        val withoutTarget = network.transport.lastBody("network/invitations/groups")!!.jsonObject["target"]
        assertTrue("without a target none is sent", withoutTarget == null || withoutTarget is JsonNull)

        model.setGroupTarget("500")
        network.add("POST", "network/invitations/groups", 422, """{"message":"Zu viele.","errors":{"target":["Höchstens 100."]}}""")
        model.createGroup()
        assertEquals("Höchstens 100.", model.error.value?.message("target"))
        assertNull(model.status.value)

        network.add("POST", "network/invitations/groups", 402, """{"message":"Dafür brauchst du eine laufende Mitgliedschaft."}""")
        model.createGroup()
        assertEquals("Dafür brauchst du eine laufende Mitgliedschaft.", model.status.value)
    }

    @Test
    fun `a group link shows the group or nothing`() = runTest {
        val founder = """{"id":2,"username":"jonas","display_name":"Jonas Weber","initials":"JW","avatar_color":"spring"}"""
        val network = StubbedNetwork(
            stub(
                "GET",
                "network/invitations/groups/abc",
                """{"data":{"group":{"founder":$founder,"id":3,"name":"Chor","target":null,"joined":2,"progress":"2 dabei",""" +
                    """"open":true,"expires_at":"2027-02-05T10:00:00.000000Z"}}}"""
            ),
            stub("GET", "network/invitations/groups/alt", """{"data":{"group":null}}""")
        )
        val model = models.keep(InvitationGroupViewModel("abc", network.repository))
        model.load()
        assertEquals("jonas", model.landing.value.valueOrNull?.group?.founder?.username)
        assertEquals("2 dabei", model.landing.value.valueOrNull?.group?.progress)
        val gone = models.keep(InvitationGroupViewModel("alt", network.repository))
        gone.load()
        assertNull(gone.landing.value.valueOrNull?.group)
    }

    @Test
    fun `an invitation link shows who invites`() = runTest {
        val network = StubbedNetwork(
            stub(
                "GET",
                "network/invitations/abc",
                """{"data":{"inviter":{"id":2,"username":"jonas","display_name":"Jonas Weber","initials":"JW","avatar_color":"spring",
                |"photo_url":null}}}
                """.trimMargin()
            )
        )
        val model = models.keep(InvitationViewModel("abc", network.repository))
        model.load()
        assertEquals("jonas", model.landing.value.valueOrNull?.inviter?.username)
    }

    @Test
    fun `the profile questions name the person`() {
        assertEquals("Jonas nicht mehr folgen?", ProfileConfirmation.Unfollow.title("Jonas"))
        assertEquals("Freundschaft beenden?", ProfileConfirmation.EndFriendship.title("Jonas"))
        assertEquals("Anfrage ablehnen?", ProfileConfirmation.Decline.title("Jonas"))
        assertEquals("Wirklich blockieren?", ProfileConfirmation.Block.title("Jonas"))
        assertEquals(ProfileAction.RemoveFriend, ProfileConfirmation.Decline.action)
    }
}
