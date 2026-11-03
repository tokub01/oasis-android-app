package de.velwebsolutions.oasis.network

import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.model.Me
import de.velwebsolutions.oasis.network.api.ComposerContext
import de.velwebsolutions.oasis.network.api.ImageUpload
import de.velwebsolutions.oasis.network.api.PostDraft
import de.velwebsolutions.oasis.network.api.PublishResult
import de.velwebsolutions.oasis.network.data.NetworkRepository
import de.velwebsolutions.oasis.network.ui.compose.ComposeViewModel
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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

/** The composer (iOS `ComposeTests`). */
@RunWith(Parameterized::class)
class ComposeViewModelTest(private val answersAtOnce: Boolean) {
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

    private val jpeg = ImageUpload(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0), "bild-1.jpg", "image/jpeg")

    private val models = ViewModelKeeper()

    private fun composer(network: StubbedNetwork, oasisSlug: String? = null, replyTo: Int? = null, carried: Int? = null) =
        models.keep(network.composer(oasisSlug, replyTo, carried))

    @After
    fun clear() {
        models.clear()
        NetworkSignals.confirmation.value = null
    }

    @Test
    fun `sends the draft as multipart with images and descriptions`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/composer", Fixtures.composer),
            stub("POST", "network/posts", """{"message":"Dein Tropfen ist da.","suspended":false,"data":${postJson(99)}}""", 201)
        )
        val model = composer(network, oasisSlug = "stadtgarten")
        model.load()
        assertEquals("public", model.state.value.visibility)
        assertEquals(4096, model.state.value.imageEdge)
        model.updateBody("Hallo Garten")
        model.add(jpeg)
        model.updateAlt(0, "Ein Beet")

        val result = model.publish()
        assertEquals("Dein Tropfen ist da.", result?.message)
        assertTrue(model.state.value.body.isEmpty())
        assertEquals("stadtgarten", network.last("network/composer").request.url.queryParameter("oasis"))
        val body = network.last("network/posts").body!!
        assertTrue(body.contains(Regex("name=\"oasis\"[\\s\\S]*?\r\n\r\nstadtgarten")))
        assertTrue(body.contains(Regex("name=\"alts\\[]\"[\\s\\S]*?\r\n\r\nEin Beet")))
        assertTrue(body.contains("name=\"images[]\"; filename=\"bild-1.jpg\""))
    }

    @Test
    fun `asks again about the tone and sends the confirmation`() = runTest {
        val question = "Nochmal lesen? Dein Tropfen klingt verletzend. Vielleicht findest du Worte, die dein Gegenüber besser hören kann."
        val network = StubbedNetwork(
            stub("GET", "network/composer", Fixtures.composer),
            stub("POST", "network/posts", """{"message":"$question","errors":{"body":["$question"]},"tone_question":true}""", 422)
        )
        val model = composer(network, oasisSlug = "ton")
        model.load()
        model.updateBody("Du bist ein Idiot")
        assertNull(model.publish())
        assertTrue(model.state.value.toneNudge)
        assertEquals(question, model.state.value.fieldError("body"))
        assertNull(model.state.value.generalError)

        model.setToneConfirmed(true)
        network.add("POST", "network/posts", 201, """{"message":"Dein Tropfen ist da.","suspended":false,"data":${postJson(5)}}""")
        assertNotNull(model.publish())
        assertTrue(network.last("network/posts").body!!.contains(Regex("name=\"tone_confirmed\"[\\s\\S]*?\r\n\r\n1")))
    }

    @Test
    fun `a changed text needs the tone confirmation again`() = runTest {
        val network = StubbedNetwork(stub("GET", "network/composer", Fixtures.composer))
        val model = composer(network, oasisSlug = "neu-gelesen")
        model.updateBody("Erster Satz")
        model.setToneConfirmed(true)
        model.updateBody("Erster Satz")
        assertTrue("the same text keeps it", model.state.value.toneConfirmed)
        model.updateBody("Erster Satz, anders")
        assertFalse(model.state.value.toneConfirmed)
        model.discard()
    }

    @Test
    fun `shows the live tone hint while typing`() = runTest {
        val network = StubbedNetwork(stub("POST", "network/tone", """{"data":{"hint":"Lappen","nudge":false}}"""))
        val model = composer(network, oasisSlug = "hinweis")
        model.updateBody("Du Lappen")
        model.checkTone()
        assertEquals("Lappen", model.state.value.toneHint)
        assertEquals("post", network.transport.lastBody("network/tone").toString().substringAfter("\"field\":\"").substringBefore("\""))
        model.discard()
        model.checkTone()
        assertNull(model.state.value.toneHint)
    }

    @Test
    fun `caps the text and descriptions at the server's limits counting characters like PHP`() = runTest {
        val network = StubbedNetwork(stub("GET", "network/composer", Fixtures.composer))
        val model = composer(network, replyTo = 4711)
        model.load()
        val limit = model.state.value.maxLength
        model.updateBody("😀".repeat(limit + 20))
        assertEquals(limit, model.state.value.length)
        model.add(jpeg)
        model.updateAlt(0, "x".repeat(400))
        assertEquals(ComposeViewModel.MAX_ALT_LENGTH, model.state.value.images[0].alt.length)
        model.discard()
    }

    @Test
    fun `takes at most the server's number of images`() = runTest {
        val network = StubbedNetwork(stub("GET", "network/composer", Fixtures.composer))
        val model = composer(network, oasisSlug = "bilder")
        model.load()
        repeat(6) { model.add(jpeg) }
        assertEquals(4, model.state.value.images.size)
        model.removeImage(0)
        assertEquals(3, model.state.value.images.size)
        model.discard()
    }

    @Test
    fun `keeps an unsent draft until it is discarded`() {
        val network = StubbedNetwork()
        val first = composer(network, oasisSlug = "entwurf")
        assertFalse(first.state.value.hasDraft)
        first.updateBody("Halb geschrieben")
        assertTrue(first.state.value.hasDraft)

        val reopened = composer(network, oasisSlug = "entwurf")
        assertEquals("Halb geschrieben", reopened.state.value.body)
        assertTrue(composer(network, oasisSlug = "andere-oase").state.value.body.isEmpty())

        reopened.discard()
        assertTrue(composer(network, oasisSlug = "entwurf").state.value.body.isEmpty())
    }

    @Test
    fun `an unsent draft belongs to its member and is gone after signing out`() = runTest {
        val network = StubbedNetwork()
        val mira = Me(7, "mira", "Mira Sol", "MS", "mira@example.org")
        network.session.update(mira)
        val written = composer(network)
        written.updateBody("Nur für mich")
        written.add(jpeg)
        assertEquals("Nur für mich", composer(network).state.value.body)

        // Someone else on the same device never sees it …
        network.session.update(Me(8, "jonas", "Jonas Weber", "JW", "jonas@example.org"))
        assertFalse(composer(network).state.value.hasDraft)
        network.session.update(mira)
        assertEquals(1, composer(network).state.value.images.size)

        // … and signing out forgets it, also for the member who wrote it.
        network.session.signOut()
        network.session.update(mira)
        assertFalse(composer(network).state.value.hasDraft)
    }

    @Test
    fun `a Tropfen whose form went away is sent once and its answer waits for the next form`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val network = StubbedNetwork(
            stub("GET", "network/composer", Fixtures.composer),
            stub("POST", "network/posts", """{"message":"Dein Tropfen ist da.","suspended":false,"data":${postJson(44)}}""", 201)
        )
        val slow = object : NetworkRepository by network.repository {
            override suspend fun publish(draft: PostDraft): PublishResult {
                gate.await()
                return network.repository.publish(draft)
            }
        }
        val model = models.keep(network.composer("gedreht", repository = slow))
        model.load()
        model.updateBody("Hallo")
        assertNull(model.unclaimedPublish())

        // A rotation cancels the form's coroutine while the Tropfen is on its way; a second tap sends nothing.
        val form = launch { model.publish() }
        runCurrent()
        form.cancelAndJoin()
        assertTrue(model.state.value.isPublishing)
        assertNull(model.publish())

        gate.complete(Unit)
        assertEquals("Dein Tropfen ist da.", model.unclaimedPublish()?.message)
        assertFalse(model.state.value.isPublishing)
        assertFalse(model.state.value.hasDraft)
        assertNull(model.unclaimedPublish())
        assertEquals(1, network.transport.requests.count { it.path == "network/posts" })
    }

    @Test
    fun `an answer taken late leaves the Tropfen sent after it to its own form`() = runTest {
        val gates = List(3) { CompletableDeferred<Unit>() }
        var sent = 0
        val network = StubbedNetwork()
        val slow = object : NetworkRepository by network.repository {
            override suspend fun publish(draft: PostDraft): PublishResult {
                val number = ++sent
                gates[number - 1].await()
                return PublishResult("Tropfen $number ist da.", data = decodePost(postJson(number)))
            }
        }
        val model = models.keep(network.composer("nacheinander", repository = slow))

        // The first form gets its answer only after the next Tropfen went out.
        model.updateBody("Erster")
        val first = async { model.publish() }
        runCurrent()
        gates[0].complete(Unit)
        model.updateBody("Zweiter")
        val second = async(start = CoroutineStart.UNDISPATCHED) { model.publish() }
        assertEquals("Tropfen 1 ist da.", first.await()?.message)

        // The second form goes away (a rotation); the next form takes its answer only after a third Tropfen went out.
        second.cancelAndJoin()
        val next = async { model.unclaimedPublish() }
        runCurrent()
        gates[1].complete(Unit)
        model.updateBody("Dritter")
        val third = async(start = CoroutineStart.UNDISPATCHED) { model.publish() }
        assertEquals("Tropfen 2 ist da.", next.await()?.message)

        gates[2].complete(Unit)
        assertEquals("the third answer still reaches its form", "Tropfen 3 ist da.", third.await()?.message)
        assertNull(model.unclaimedPublish())
        assertEquals(3, sent)
    }

    @Test
    fun `a context whose load was cancelled with its screen loads again`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val network = StubbedNetwork(stub("GET", "network/composer", Fixtures.composer))
        val slow = object : NetworkRepository by network.repository {
            override suspend fun composer(oasisSlug: String?, replyTo: Int?, carried: Int?): ComposerContext {
                gate.await()
                return network.repository.composer(oasisSlug, replyTo, carried)
            }
        }
        val model = models.keep(network.composer(replyTo = 7, repository = slow))
        // The reply form scrolled out of view (or the screen rotated) while its context loaded.
        val form = launch { model.loadIfNeeded() }
        runCurrent()
        assertEquals(Loadable.Loading, model.state.value.context)
        form.cancelAndJoin()
        assertEquals(Loadable.Idle, model.state.value.context)

        gate.complete(Unit)
        model.loadIfNeeded()
        assertEquals(4096, model.state.value.imageEdge)
    }

    @Test
    fun `publishing tells the screens what was written`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/composer", Fixtures.composer),
            stub("POST", "network/posts", """{"message":"Dein Tropfen ist da.","suspended":false,"data":${postJson(42)}}""", 201)
        )
        val model = composer(network, oasisSlug = "signale")
        model.load()
        model.updateBody("Hallo")
        val before = NetworkSignals.published.value
        assertNotNull(model.publish())
        assertTrue(NetworkSignals.published.value > before)
        assertEquals(42, NetworkSignals.lastPublished?.id)
        assertEquals("Dein Tropfen ist da.", NetworkSignals.confirmation.value)
        assertFalse(model.state.value.hasDraft)
    }

    @Test
    fun `offers only what the server allows and never preselects a hidden audience`() = runTest {
        // A server that leaves "Öffentlich" out wins over a privacy default of public.
        val membersOnly = """
            {"data": {"default_visibility": "public", "visibilities": [{"value": "members", "label": "Nur für Mitglieder"}],
            "kinds": [], "oasis": null, "links": [], "max_length": 500, "max_images": 4}}
        """.trimIndent()
        val network = StubbedNetwork(stub("GET", "network/composer", membersOnly))
        val model = composer(network, oasisSlug = "jung")
        model.load()
        assertEquals("members", model.state.value.visibility)
        assertEquals(listOf("members"), model.state.value.context.valueOrNull?.visibilities?.map { it.value })
    }

    @Test
    fun `labels follow the context like the web`() {
        val network = StubbedNetwork()
        assertEquals("Antworten", composer(network, replyTo = 1).state.value.submitLabel)
        assertEquals("Deine Antwort", composer(network, replyTo = 1).state.value.bodyLabel)
        assertEquals("Deine Worte dazu (freiwillig)", composer(network, carried = 1).state.value.bodyLabel)
        assertEquals("Weitertragen", composer(network, carried = 1).state.value.submitLabel)
        assertEquals("Tropfen teilen", composer(network).state.value.submitLabel)
        assertEquals("Dein Tropfen", composer(network).state.value.bodyLabel)
    }

    @Test
    fun `ignores images and descriptions that are not there and a blank tone check`() = runTest {
        val network = StubbedNetwork(stub("POST", "network/tone", """{"message":"Kaputt."}""", 500))
        val model = composer(network, oasisSlug = "leer")
        model.removeImage(3)
        model.updateAlt(3, "Nichts")
        assertTrue(model.state.value.images.isEmpty())
        model.checkTone()
        assertTrue(network.transport.requests.isEmpty())

        model.updateBody("Hallo")
        model.checkTone()
        assertNull(model.state.value.toneHint)
        model.setVisibility("members")
        model.setKind("question")
        model.setLinkRead(true)
        assertEquals("members", model.state.value.visibility)
        assertEquals("question", model.state.value.kind)
        assertTrue(model.state.value.linkRead)
        model.discard()
    }

    @Test
    fun `a context that does not load shows its error, and loadIfNeeded loads once`() = runTest {
        val network = StubbedNetwork(stub("GET", "network/composer", """{"message":"Kaputt."}""", 500))
        val model = composer(network, replyTo = 12)
        model.loadIfNeeded()
        assertEquals("Kaputt.", (model.state.value.context as de.velwebsolutions.oasis.core.model.Loadable.Failed).error.userMessage)
        model.loadIfNeeded()
        assertEquals(1, network.transport.requests.size)
        assertEquals("12", network.last("network/composer").request.url.queryParameter("reply_to"))
    }

    @Test
    fun `a held Tropfen refreshes the account and a carry is sent as a plain Tropfen`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/composer", Fixtures.composer),
            stub("POST", "network/posts", """{"message":"Zurückgehalten.","suspended":true,"data":${postJson(43)}}""", 201)
        )
        val model = composer(network, carried = 12)
        model.load()
        model.updateBody("Hallo")
        assertEquals("Zurückgehalten.", model.publish()?.message)
        assertTrue(network.last("network/posts").body!!.contains(Regex("name=\"carried\"[\\s\\S]*?\r\n\r\n12\r\n")))
        assertTrue(network.last("network/posts").body!!.contains(Regex("name=\"kind\"[\\s\\S]*?\r\n\r\npost\r\n")))
    }

    @Test
    fun `a plain carry sends neither words nor images`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/composer", Fixtures.composer),
            stub("POST", "network/posts", """{"message":"Weitergetragen.","data":${postJson(44)}}""", 201)
        )
        val model = composer(network, carried = 12)
        model.load()
        model.updateBody("Meine Worte")
        model.add(ImageUpload(byteArrayOf(1), "a.jpg", "image/jpeg"))
        model.carryPlainly()
        assertTrue(model.state.value.plainCarry)
        assertEquals("Weitergetragen.", model.publish()?.message)
        val sent = network.last("network/posts").body!!
        assertTrue(sent.contains("name=\"carried\""))
        assertFalse(sent.contains("Meine Worte"))
        assertFalse(sent.contains("a.jpg"))
    }

    @Test
    fun `a general error shows above the form, field errors at their field`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/composer", Fixtures.composer),
            stub("POST", "network/posts", """{"message":"Kaputt."}""", 500)
        )
        val model = composer(network, oasisSlug = "fehler")
        model.load()
        model.updateBody("Hallo")
        assertNull(model.publish())
        assertEquals("Kaputt.", model.state.value.generalError)
        assertFalse(model.state.value.toneNudge)

        network.add("POST", "network/posts", 422, """{"message":"Zu lang.","errors":{"body":["Zu lang."]}}""")
        assertNull(model.publish())
        assertNull(model.state.value.generalError)
        assertEquals("Zu lang.", model.state.value.fieldError("body"))
    }
}
