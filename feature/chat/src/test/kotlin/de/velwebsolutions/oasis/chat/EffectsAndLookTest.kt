package de.velwebsolutions.oasis.chat

import android.net.Uri
import de.velwebsolutions.oasis.chat.core.AttachmentKind
import de.velwebsolutions.oasis.chat.core.AttachmentProblem
import de.velwebsolutions.oasis.chat.core.ChatBackground
import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.core.DraftAttachment
import de.velwebsolutions.oasis.chat.core.LookChoice
import de.velwebsolutions.oasis.chat.core.LookStore
import de.velwebsolutions.oasis.chat.ui.ConversationDetailsViewModel
import de.velwebsolutions.oasis.chat.ui.ConversationViewModel
import de.velwebsolutions.oasis.chat.ui.EffectNames
import de.velwebsolutions.oasis.chatcrypto.MessageEnvelope
import de.velwebsolutions.oasis.chatcrypto.MessagePayload
import de.velwebsolutions.oasis.core.realtime.RealtimeEvent
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import kotlin.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Send effects and personal backgrounds in the view models (docs/oasis-chat-client.md, "Backgrounds and effects"): the
 * effect only inside the encrypted payload, once for the sender and once live for the recipient, never for view once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EffectsAndLookTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val support = ChatTestSupport()
    private val server get() = support.server
    private val miraMedia = FakeChatMedia()

    private fun model(context: ChatContext, id: String) = ConversationViewModel(id, context, FakeRealtime()).apply {
        pollInterval = Duration.INFINITE
        sweepInterval = Duration.INFINITE
        recordingTick = Duration.INFINITE
    }

    private suspend fun chat(): Triple<ChatContext, ChatContext, String> {
        val mira = support.member(1, media = miraMedia)
        val jonas = support.member(2)
        val id = mira.repository.createConversation("direct", null, listOf("2"), 7).id
        model(mira, id).open()
        return Triple(mira, jonas, id)
    }

    private fun lastSent(id: String): MessageEnvelope =
        Json.decodeFromString(MessageEnvelope.serializer(), server.messages.getValue(id.toInt()).last().second.toString())

    @Test
    fun anEffectWaitsAsAChipOrGoesWithTheMessage() = runTest {
        val (mira, _, id) = chat()
        val writer = model(mira, id)
        writer.open()
        assertEquals(LookChoice(ChatBackground.Standard, "default"), writer.state.value.look)
        assertTrue(writer.state.value.effectsOn)

        // Nothing to send yet: the chip.
        writer.chooseEffect("konfetti")
        assertEquals("Effekt: Konfetti", writer.state.value.effectChip)
        assertTrue(server.messages[id.toInt()].isNullOrEmpty())
        writer.clearEffect()
        assertNull(writer.state.value.effectChip)

        // With text: it goes at once, inside the payload, and plays once for the sender.
        writer.setDraft("Alles Gute!")
        writer.chooseEffect("herzen")
        val sent = writer.state.value.timeline.items.last()
        assertEquals("herzen", sent.content.effect)
        assertEquals("""{"t":"oasis-message","v":2,"text":"Alles Gute!","attachments":[],"effect":"herzen"}""", sent.plaintext)
        assertEquals("herzen", writer.state.value.playing?.key)
        assertNull(writer.state.value.pendingEffect)
        writer.effectDone()
        assertNull(writer.state.value.playing)
        assertEquals(listOf("Konfetti", "Herzen", "Ballons", "Feuerwerk"), EffectNames.all.map { it.second })

        // A chosen effect is ready for the next message too.
        writer.chooseEffect("ballons")
        writer.setDraft("Hoch")
        writer.send()
        assertEquals("ballons", writer.state.value.timeline.items.last().content.effect)
        clear(writer)
    }

    @Test
    fun noEffectWithAViewOnceFileAndNoneWhenSwitchedOff() = runTest {
        val (mira, _, id) = chat()
        val writer = model(mira, id)
        writer.open()
        miraMedia.prepared = { _, localId -> DraftAttachment(localId, AttachmentKind.Image, "image/jpeg", byteArrayOf(1, 2, 3), 2, 1) }
        writer.addPicked(Uri.parse("content://oasis.test/1"))
        writer.setViewOnce(writer.state.value.drafts.single().localId, true)
        writer.chooseEffect("feuerwerk")
        assertNull("sent without the effect", writer.state.value.timeline.items.last().content.effect)
        assertEquals("the chip stays for the next message", "Effekt: Feuerwerk", writer.state.value.effectChip)
        assertNull(writer.state.value.playing)

        // „Effekte abspielen“ off: sent with the effect, nothing plays here.
        mira.look.setEffectsEnabled(false)
        writer.open()
        assertFalse(writer.state.value.effectsOn)
        writer.setDraft("Still")
        writer.chooseEffect("konfetti")
        assertEquals("konfetti", writer.state.value.timeline.items.last().content.effect)
        assertNull(writer.state.value.playing)
        clear(writer)
    }

    @Test
    fun theRecipientSeesItOnceLiveButNeverFromTheHistory() = runTest {
        val (mira, jonas, id) = chat()
        val writer = model(mira, id)
        writer.open()
        val reader = model(jonas, id)
        reader.open()
        val otherDevice = model(mira, id)
        otherDevice.open()
        writer.setDraft("Konfetti!")
        writer.chooseEffect("konfetti")
        val envelope = lastSent(id)

        reader.handle(RealtimeEvent("MessageSent", envelope.toJsonObject()))
        assertEquals("konfetti", reader.state.value.playing?.key)
        reader.effectDone()
        // Again (a duplicate) or from the history: nothing.
        reader.handle(RealtimeEvent("MessageSent", envelope.toJsonObject()))
        reader.open()
        assertNull(reader.state.value.playing)
        assertEquals("konfetti", reader.state.value.timeline.items.last().content.effect)

        // Own messages coming back live play nothing more, also on another device of the sender.
        writer.effectDone()
        writer.handle(RealtimeEvent("MessageSent", envelope.toJsonObject()))
        assertNull(writer.state.value.playing)
        otherDevice.handle(RealtimeEvent("MessageSent", envelope.toJsonObject()))
        assertEquals("konfetti", otherDevice.state.value.timeline.items.last().content.effect)
        assertNull(otherDevice.state.value.playing)
        clear(otherDevice)
        clear(reader)
        clear(writer)
    }

    @Test
    fun aLiveMessageWithFilesPlaysOnlyWhenNoneIsViewOnce() = runTest {
        val (mira, jonas, id) = chat()
        val writer = model(mira, id)
        writer.open()
        val reader = model(jonas, id)
        reader.open()
        miraMedia.prepared = { _, localId -> DraftAttachment(localId, AttachmentKind.Image, "image/jpeg", byteArrayOf(1, 2, 3), 2, 1) }
        writer.addPicked(Uri.parse("content://oasis.test/1"))
        writer.chooseEffect("ballons")
        reader.handle(RealtimeEvent("MessageSent", lastSent(id).toJsonObject()))
        waitFor { reader.state.value.playing != null }
        assertEquals("ballons", reader.state.value.playing?.key)
        reader.effectDone()

        // A view-once file with an effect (only a foreign client would send that): nothing plays.
        val viewOnce = DraftAttachment(9, AttachmentKind.Image, "image/jpeg", byteArrayOf(4, 5, 6), 2, 1, viewOnce = true)
        val reference = de.velwebsolutions.oasis.chat.core.ChatAttachments.upload(mira, id, viewOnce) {}
        val plaintext = MessagePayload.plaintext(de.velwebsolutions.oasis.chatcrypto.MessageContent("", listOf(reference), "herzen"))
        val sealed = de.velwebsolutions.oasis.chat.core.ChatMessages.seal(
            plaintext,
            mira.repository.conversation(id),
            mira.vault,
            testCrypto
        )
        mira.repository.sendMessage(id, sealed, listOf(reference.id))
        reader.handle(RealtimeEvent("MessageSent", sealed.toJsonObject()))
        waitFor { reader.state.value.timeline.items.last().attachmentStates.isNotEmpty() }
        assertNull(reader.state.value.playing)
        reader.close()
        assertNull(reader.state.value.playing)
        clear(reader)
        clear(writer)
    }

    @Test
    fun theDetailsChooseTheBackgroundThePhotoAndTheSwitch() = runTest {
        val media = FakeChatMedia()
        val mira = support.member(1, media = media)
        support.member(2)
        val id = mira.repository.createConversation("direct", null, listOf("2"), 7).id
        val details = ConversationDetailsViewModel(id, mira)
        details.loadLook()
        assertEquals(ChatBackground.Standard, details.state.value.look.background)

        details.chooseBackground(ChatBackground.Oase)
        assertEquals(LookChoice(ChatBackground.Oase, id), details.state.value.look)
        details.setForAll(true)
        details.chooseBackground(ChatBackground.Sandkoerner)
        assertEquals(LookChoice(ChatBackground.Sandkoerner, "default"), details.state.value.look)

        media.prepared = { _, localId -> DraftAttachment(localId, AttachmentKind.Image, "image/jpeg", byteArrayOf(7, 7), 2, 1) }
        details.choosePhoto(Uri.parse("content://oasis.test/foto"))
        assertEquals(ChatBackground.Photo, details.state.value.look.background)
        assertArrayEquals(byteArrayOf(7, 7), details.state.value.look.photo?.jpeg)
        details.removePhoto()
        assertEquals(ChatBackground.Standard, details.state.value.look.background)

        media.prepared = { _, _ -> throw AttachmentProblem("Dieses Foto ließ sich nicht öffnen.") }
        details.choosePhoto(Uri.parse("content://oasis.test/kaputt"))
        assertEquals("Dieses Foto ließ sich nicht öffnen.", details.state.value.lookError)
        media.prepared = { _, localId -> DraftAttachment(localId, AttachmentKind.Video, "video/mp4", byteArrayOf(1), durationMs = 1_000) }
        details.choosePhoto(Uri.parse("content://oasis.test/film"))
        assertEquals("Dieses Foto ließ sich nicht öffnen.", details.state.value.lookError)

        details.setEffects(false)
        assertFalse(details.state.value.effectsOn)
        assertFalse(mira.look.effectsEnabled())
        details.setEffects(true)
        assertTrue(details.state.value.effectsOn)
        clear(details)
    }

    /** A look store that suspends on every call, like the DataStore. */
    private class SuspendingLook(private val inner: LookStore) : LookStore {
        override suspend fun get(key: String): String? {
            yield()
            return inner.get(key)
        }

        override suspend fun set(key: String, value: String) {
            yield()
            inner.set(key, value)
        }

        override suspend fun remove(key: String) {
            yield()
            inner.remove(key)
        }

        override suspend fun clear() {
            yield()
            inner.clear()
        }
    }

    @Test
    fun theDetailsWaitForAStoreThatSuspends() = runTest {
        support.lookStore = SuspendingLook(support.look)
        val mira = support.member(1)
        support.member(2)
        val id = mira.repository.createConversation("direct", null, listOf("2"), 7).id
        val details = ConversationDetailsViewModel(id, mira)
        details.setEffects(false)
        details.loadLook()
        assertFalse(details.state.value.effectsOn)
        details.chooseBackground(ChatBackground.Duene)
        details.removePhoto()
        assertEquals(LookChoice(ChatBackground.Standard, id), details.state.value.look)
        clear(details)
    }

    private suspend fun waitFor(done: () -> Boolean) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        kotlinx.coroutines.withTimeout(5_000) { while (!done()) kotlinx.coroutines.delay(10) }
    }
}
