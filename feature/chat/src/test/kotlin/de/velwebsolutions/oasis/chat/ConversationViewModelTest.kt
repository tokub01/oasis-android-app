package de.velwebsolutions.oasis.chat

import de.velwebsolutions.oasis.chat.api.SafetyLists
import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.core.ChatMessageItem
import de.velwebsolutions.oasis.chat.ui.ConversationViewModel
import de.velwebsolutions.oasis.chatcrypto.MessageEnvelope
import de.velwebsolutions.oasis.core.realtime.RealtimeEvent
import de.velwebsolutions.oasis.core.realtime.RealtimeService
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A realtime connection the test drives: its events and whether it is connected. */
class FakeRealtime(connected: Boolean = false) : RealtimeService {
    override val isConnected = MutableStateFlow(connected)
    val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 16)

    override fun subscribe(channel: String) = events
}

/** The conversation screen's view model in every branch: keys, notices, sending, safety, requests and failures. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val support = ChatTestSupport()
    private val server get() = support.server

    private fun failing(key: String, status: Int = 500, json: String = """{"message":"kaputt"}""") {
        server.failures[key] = status to json
    }

    private fun model(context: ChatContext, id: String, realtime: RealtimeService = FakeRealtime()) =
        ConversationViewModel(id, context, realtime).apply {
            pollInterval = Duration.INFINITE
            sweepInterval = Duration.INFINITE
        }

    private suspend fun chat(owner: ChatContext, vararg others: Int, type: String = "direct") =
        owner.repository.createConversation(type, null, others.map(Int::toString), 7).id

    /** Mira (owner) and Jonas with a chat whose key Mira distributed; Jonas has not answered yet. */
    private suspend fun pair(): Triple<ChatContext, ChatContext, String> {
        val mira = support.member(1)
        val jonas = support.member(2)
        val id = chat(mira, 2)
        model(mira, id).open()
        return Triple(mira, jonas, id)
    }

    private fun envelopeJson(envelope: MessageEnvelope) = envelope.toJsonObject()

    @Test
    fun theOwnerDistributesTheKeyAndBothWrite() = runTest {
        val (mira, jonas, id) = pair()
        val owner = model(mira, id)
        owner.open()
        assertTrue(owner.state.value.canSend)
        assertTrue(owner.state.value.isOwner)
        assertEquals("Ende-zu-Ende verschlüsselt · Nachrichten löschen sich nach 7 Tagen", owner.state.value.lifetimeLine)
        owner.send()
        owner.setDraft("   ")
        owner.send()
        owner.setDraft("a".repeat(4001))
        assertEquals("Zu lang", owner.state.value.counter)
        owner.send()
        assertTrue("nothing went out", owner.state.value.timeline.items.isEmpty())
        owner.setDraft("Hallo Jonas")
        assertEquals("11 / 4000", owner.state.value.counter)
        owner.send()
        assertEquals("Gesendet.", owner.state.value.spokenNotice)
        owner.noticeSpoken()
        assertNull(owner.state.value.spokenNotice)

        val reader = model(jonas, id)
        reader.open()
        assertEquals("Nachrichtenanfrage von Mira", reader.state.value.requestTitle)
        assertEquals("Hallo Jonas", reader.state.value.timeline.items.single().plaintext)
        assertEquals("Mira", reader.senderName("1"))
        assertEquals("Unbekannt", reader.senderName("99"))
        reader.setDraft("Hallo Mira")
        reader.send()
        assertNull("answering accepts the request", reader.state.value.requestTitle)
        assertNotNull(reader.reportModel(reader.state.value.timeline.items.first()))
        clear(reader)
    }

    @Test
    fun realtimeEventsBringMessagesAndKeyChanges() = runTest {
        val (mira, jonas, id) = pair()
        val live = FakeRealtime(connected = true)
        val reader = model(jonas, id, live)
        reader.open()
        val writer = model(mira, id)
        writer.open()
        writer.setDraft("Live")
        writer.send()
        val sent = Json.decodeFromString(MessageEnvelope.serializer(), server.messages.getValue(id.toInt()).last().second.toString())
        live.events.emit(RealtimeEvent(".MessageSent", envelopeJson(sent)))
        assertEquals("Live", reader.state.value.timeline.items.single().plaintext)
        assertEquals("Neue Nachricht von Mira.", reader.state.value.spokenNotice)
        live.events.emit(RealtimeEvent("MessageSent", envelopeJson(sent)))
        live.events.emit(RealtimeEvent("MessageSent", JsonPrimitive("kaputt")))
        live.events.emit(RealtimeEvent("MessageSent", envelopeJson(sent.copy(conversationId = "999", signature = "x"))))
        live.events.emit(RealtimeEvent("UserTyping"))
        assertEquals(1, reader.state.value.timeline.items.size)
        live.events.emit(RealtimeEvent("KeyRotated"))
        failing("GET conversations/$id")
        live.events.emit(RealtimeEvent("KeyRotated"))
        withContext(Dispatchers.Default) { delay(200) }
        assertEquals("Da ist etwas schiefgegangen. Versuch es gleich noch einmal.", reader.state.value.composerError)
    }

    @Test
    fun noticesExplainWhyNobodyCanWriteYet() = runTest {
        val mira = support.member(1)
        val jonas = support.member(2)
        val aylin = support.member(3)
        val id = chat(mira, 2, 3, type = "group")
        server.keyChangedAt[3] = "2026-10-18T12:00:00Z"
        jonas.vault.rememberPublicKeys("3", testCrypto.verifyPublicKeys(testCrypto.exportPublicKeys(testCrypto.createIdentity())))
        val waiting = model(jonas, id)
        waiting.open()
        val notices = waiting.state.value.notices
        assertEquals(
            "Der Chat bekommt einen neuen Schlüssel. Sobald die Person, die ihn gestartet hat, online ist, kannst du schreiben.",
            notices.first()
        )
        assertTrue(notices.any { it.startsWith("Aylin hat am 18.10.2026 neue Chat-Schlüssel erstellt.") })
        assertTrue(notices.any { it.startsWith("Die Schlüssel von Aylin haben sich gerade geändert.") })
        assertFalse(waiting.state.value.canSend)

        // A member without keys stops the owner's distribution; the owner sees why.
        server.identities[3] = buildJsonObject {
            put("user_id", "3")
            put("public_keys", Json.parseToJsonElement("""{"encryption":"AA","signing":"AA","binding":"AA"}"""))
        }
        val owner = model(mira, id)
        owner.open()
        assertTrue(owner.state.value.notices.any { it.startsWith("Die Schlüssel von Aylin sind ungültig.") })
        assertTrue(owner.state.value.notices.any { it.startsWith("Für aylin gibt es keine gültigen Chat-Schlüssel.") })
        assertTrue(owner.state.value.notices.first().startsWith("Für diesen Chat liegt dir noch kein Schlüssel vor."))
        assertNotNull(aylin)

        val readOnly = support.context(2, canParticipate = false, vault = jonas.vault)
        val reading = model(readOnly, id)
        reading.open()
        assertTrue(
            reading.state.value.notices.contains(
                "Dein Konto ist gerade nur zum Lesen. Mit einer Mitgliedschaft kannst du wieder schreiben."
            )
        )
    }

    @Test
    fun sensitiveDraftsNeedASecondPressWithTheSameText() = runTest {
        val (mira, _, id) = pair()
        val owner = model(mira, id)
        owner.open()
        owner.setDraft("mein Passwort ist sonne123")
        owner.send()
        assertTrue(owner.state.value.hint.orEmpty().startsWith("Du bist dabei, ein Passwort oder einen Code zu verschicken."))
        owner.setDraft("mein Passwort ist sonne123 ")
        owner.setDraft("mein Passwort ist sonne1234")
        owner.send()
        assertTrue("a changed text asks again", owner.state.value.timeline.items.isEmpty())
        owner.send()
        assertEquals(1, owner.state.value.timeline.items.size)
        owner.setDraft("Digga, alles klar?")
        mira.vault.safety = SafetyLists(tone = SafetyLists.Tone(always = listOf("digga")))
        owner.setDraft("Digga, alles klar?")
        assertEquals("Kurzer Hinweis: „Digga“ wirkt schnell herablassend. Bitte achte auf einen freundlichen Ton.", owner.state.value.hint)
    }

    @Test
    fun sendingFailuresStayAtTheComposer() = runTest {
        val (mira, _, id) = pair()
        val owner = model(mira, id)
        owner.open()
        owner.setDraft("Eins")
        failing("POST conversations/$id/messages", 409, """{"message":"rotation"}""")
        owner.send()
        assertEquals("Der Chat bekommt gerade einen neuen Schlüssel. Versuch es gleich noch einmal.", owner.state.value.composerError)
        failing("POST conversations/$id/messages", 409, """{"message":"rotation"}""")
        failing("GET conversations/$id", 500)
        owner.send()
        assertEquals("Da ist etwas schiefgegangen. Versuch es gleich noch einmal.", owner.state.value.composerError)
        failing("POST conversations/$id/messages", 422, """{"message":"Zu lang","errors":{"envelope":["Zu lang."]}}""")
        owner.send()
        assertEquals("Zu lang.", owner.state.value.composerError)
    }

    @Test
    fun theSafetyBannerWarnsAboutTheNewestFlaggedMessage() = runTest {
        val (mira, jonas, id) = pair()
        jonas.vault.safety = SafetyLists(lists = mapOf("insult" to listOf("vollidiot")))
        val writer = model(mira, id)
        writer.open()
        val reader = model(jonas, id)
        reader.open()
        for (text in listOf("Schick mir dein Passwort", "Lust auf ein Taschengeld-Treffen?", "Du Vollidiot")) {
            writer.setDraft(text)
            writer.send()
            writer.send()
        }
        reader.open()
        val flagged = checkNotNull(reader.state.value.flagged)
        assertEquals("Möglicherweise Belästigung erkannt. Mira blockieren und Chat löschen?", flagged.title)
        assertTrue(flagged.text.endsWith("Gerade in den ersten Nachrichten ist das ein typisches Warnzeichen."))
        reader.dismissFlagged()
        assertNull("„Ist in Ordnung“ hides the banner", reader.state.value.flagged)
        // The next check shows the next older finding; dismissed ones stay dismissed.
        reader.refresh()
        assertTrue(checkNotNull(reader.state.value.flagged).title.startsWith("Möglicherweise sexuelle Belästigung erkannt."))
        reader.dismissFlagged()
        reader.refresh()
        assertTrue(checkNotNull(reader.state.value.flagged).title.startsWith("Achtung: Hier fragt jemand nach sensiblen Daten."))
        reader.dismissFlagged()
        reader.refresh()
        assertNull(reader.state.value.flagged)
        reader.dismissFlagged()

        // Later in a chat the warning drops the "first messages" sentence.
        repeat(3) {
            writer.setDraft("Hallo $it")
            writer.send()
        }
        writer.setDraft("Du Vollidiot")
        writer.send()
        reader.open()
        assertFalse(checkNotNull(reader.state.value.flagged).text.contains("ersten Nachrichten"))
    }

    @Test
    fun requestsCanBeAcceptedDeclinedOrBlocked() = runTest {
        val (_, jonas, id) = pair()
        val reader = model(jonas, id)
        reader.open()
        failing("POST conversations/$id/accept")
        reader.acceptRequest()
        assertEquals("Da ist etwas schiefgegangen. Versuch es gleich noch einmal.", reader.state.value.actionError)
        reader.dismissActionError()
        assertNull(reader.state.value.actionError)
        reader.acceptRequest()
        assertEquals("Angenommen. Das ist jetzt ein normaler Chat.", reader.state.value.status)
        reader.dismissStatus()
        server.onRequest = { if (it == "POST conversations/$id/accept") reader.close() }
        reader.open()
        reader.acceptRequest()
        server.onRequest = {}

        reader.open()
        failing("POST conversations/$id/leave")
        assertFalse(reader.declineRequest(block = false))
        assertTrue(reader.declineRequest(block = true))
        assertEquals("Blockiert und gelöscht.", jonas.vault.announcement.value)
        assertEquals(listOf(2 to 1), server.blocks)
        reader.close()
        assertFalse("nothing to decline after leaving", reader.declineRequest(block = false))

        val (_, other, second) = pair()
        val declining = model(other, second)
        declining.open()
        assertTrue(declining.declineRequest(block = false))
        assertEquals("Anfrage gelöscht. Die Person erfährt davon nichts.", other.vault.announcement.value)
    }

    @Test
    fun blockingLeavesTheChatUnlessTheOwnerMustStay() = runTest {
        val (mira, jonas, id) = pair()
        val reader = model(jonas, id)
        reader.open()
        failing("POST blocks")
        assertFalse(reader.blockAndLeave("1"))
        assertTrue(reader.blockAndLeave("1"))
        assertEquals("Blockiert. Der Chat ist gelöscht.", jonas.vault.announcement.value)

        val owner = model(mira, id)
        owner.open()
        failing("POST conversations/$id/leave", 422, """{"message":"Owner","errors":{"user":["Du hast die Gruppe gestartet."]}}""")
        assertTrue("the block alone stops the messages", owner.blockAndLeave("2"))
    }

    @Test
    fun lostAccessClosesTheScreenOtherFailuresStay() = runTest {
        val (mira, _, id) = pair()
        failing("GET conversations/$id", 404, """{"message":"Not found"}""")
        val gone = model(mira, id)
        gone.open()
        assertTrue(gone.state.value.closed)
        assertEquals("Diesen Chat gibt es nicht mehr.", mira.vault.announcement.value)

        failing("GET conversations/$id", 403, """{"message":"This action is unauthorized."}""")
        val forbidden = model(mira, id)
        forbidden.open()
        assertTrue(forbidden.state.value.closed)

        failing("GET conversations/$id", 500)
        val broken = model(mira, id)
        broken.open()
        assertEquals("Da ist etwas schiefgegangen. Versuch es gleich noch einmal.", broken.state.value.loadFailed)

        val open = model(mira, id)
        open.open()
        failing("GET conversations/$id/messages")
        open.loadOlder()
        assertEquals("Da ist etwas schiefgegangen. Versuch es gleich noch einmal.", open.state.value.composerError)
        open.loadOlder()
    }

    @Test
    fun aLockOrCloseMidwayDropsTheResult() = runTest {
        val (mira, _, id) = pair()
        val owner = model(mira, id)
        server.onRequest = { if (it == "GET conversations/$id/key-grants") owner.close() }
        owner.open()
        server.onRequest = { if (it == "GET conversations/$id/messages") owner.close() }
        owner.open()
        server.onRequest = { if (it == "POST conversations/$id/read") mira.vault.lock(announce = false) }
        owner.open()
        server.onRequest = {}
        assertTrue(owner.state.value.timeline.items.isEmpty())
    }

    @Test
    fun keysArrivingLaterOpenOlderMessages() = runTest {
        val mira = support.member(1)
        val jonas = support.member(2)
        val id = chat(mira, 2)
        // Jonas opens before Mira distributed the first key.
        val reader = model(jonas, id)
        reader.open()
        assertTrue(reader.state.value.notices.first().startsWith("Der Chat bekommt einen neuen Schlüssel."))
        val writer = model(mira, id)
        writer.open()
        writer.setDraft("Vorher")
        writer.send()
        val sent = Json.decodeFromString(MessageEnvelope.serializer(), server.messages.getValue(id.toInt()).last().second.toString())
        reader.receive(sent)
        val waiting = reader.state.value.timeline.items.single()
        assertEquals(ChatMessageItem.State.NoKey, waiting.state)
        // A message for a key version nobody has stays without a key.
        reader.receive(waiting.envelope.copy(keyVersion = 9, signature = "other"))

        reader.refresh()
        val states = reader.state.value.timeline.items.map { it.state }
        assertEquals(listOf(ChatMessageItem.State.Readable, ChatMessageItem.State.NoKey), states)
        reader.refresh()
    }

    @Test
    fun aMessageThatExpiredWhileWaitingForItsKeyIsNotOpened() = runTest {
        val mira = support.member(1)
        val jonas = support.member(2)
        val id = chat(mira, 2)
        val reader = model(jonas, id)
        reader.open()
        val writer = model(mira, id)
        writer.open()
        writer.setDraft("Gleich weg")
        writer.send()
        val sent = Json.decodeFromString(MessageEnvelope.serializer(), server.messages.getValue(id.toInt()).last().second.toString())
        reader.receive(sent)
        server.serverTime = "2026-11-30T12:00:00Z"
        reader.refresh()
        assertEquals(ChatMessageItem.State.NoKey, reader.state.value.timeline.items.single().state)
    }

    @Test
    fun pollingAndReceivingIgnoreWhatDoesNotBelong() = runTest {
        val (mira, jonas, id) = pair()
        val reader = model(jonas, id)
        reader.poll()
        assertTrue("before opening: nothing to show", reader.state.value.timeline.items.isEmpty())
        reader.open()
        server.conversations.getValue(id.toInt()).rotationRequired = true
        reader.poll()
        assertFalse(reader.state.value.canSend)
        server.onRequest = { if (it == "GET conversations/$id/messages") reader.close() }
        reader.poll()
        server.onRequest = {}

        val writer = model(mira, id)
        writer.open()
        writer.setDraft("Hallo")
        writer.send()
        val sent = Json.decodeFromString(MessageEnvelope.serializer(), server.messages.getValue(id.toInt()).last().second.toString())
        reader.close()
        reader.receive(sent)
        reader.open()
        jonas.vault.clock.sync("2026-11-30T12:00:00Z")
        reader.receive(sent)
        assertEquals(1, reader.state.value.timeline.items.size)

        // A member missing from the key directory gets no warning (the server lists only current keys).
        server.failures["GET conversations/$id/public-keys"] = 200 to """{"data":[]}"""
        reader.refresh()

        // Without a key the composer stays closed whatever is typed.
        val readOnly = model(support.context(2, canParticipate = false, vault = jonas.vault), id)
        readOnly.open()
        readOnly.setDraft("Hallo")
        readOnly.send()
        model(jonas, id).send()
        assertTrue(readOnly.state.value.timeline.items.none { it.plaintext == "Hallo" && it.senderId == "2" })
    }

    @Test
    fun theTimersSweepExpiredMessagesAndPollWhileOffline() = runBlocking {
        val mira = support.member(1)
        val jonas = support.member(2)
        val id = chat(mira, 2)
        model(mira, id).open()
        val live = FakeRealtime(connected = false)
        val reader = ConversationViewModel(id, jonas, live).apply {
            pollInterval = 10.milliseconds
            sweepInterval = 10.milliseconds
        }
        reader.open()
        val writer = model(mira, id)
        writer.open()
        writer.setDraft("Gleich weg")
        writer.send()
        advance { reader.state.value.timeline.items.size == 1 }
        assertEquals("the poll fetched it", 1, reader.state.value.timeline.items.size)
        live.isConnected.value = true
        advance()
        jonas.vault.clock.sync("2026-11-30T12:00:00Z")
        advance { reader.state.value.timeline.items.isEmpty() }
        assertTrue("expired and swept", reader.state.value.timeline.items.isEmpty())
        server.conversations.getValue(id.toInt()).keyVersion += 1
        live.isConnected.value = false
        advance()
        failing("GET conversations/$id")
        advance { reader.state.value.composerError != null }
        assertEquals("Da ist etwas schiefgegangen. Versuch es gleich noch einmal.", reader.state.value.composerError)
        jonas.vault.lock(announce = false)
        advance()
        clear(reader)
    }

    /** One round of both timers: their delays pass in virtual time, the requests in real time, until [done] holds. */
    private suspend fun advance(done: () -> Boolean = { true }) {
        val scheduler = mainRule.dispatcher.scheduler
        scheduler.advanceTimeBy(15)
        scheduler.runCurrent()
        settle(scheduler, server, done)
    }

    companion object {
        /** Opens a chat as [context] once, e.g. so the owner distributes the first key. */
        suspend fun open(context: ChatContext, id: String) {
            ConversationViewModel(id, context, FakeRealtime()).apply {
                pollInterval = Duration.INFINITE
                sweepInterval = Duration.INFINITE
            }.open()
        }
    }
}
