package de.velwebsolutions.oasis.chat

import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.core.TranscriptionProblem
import de.velwebsolutions.oasis.chat.core.VoiceTranscriber
import de.velwebsolutions.oasis.chat.ui.ConversationViewModel
import de.velwebsolutions.oasis.chat.ui.Transcript
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import java.io.File
import java.io.IOException
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Voice messages as text on the device: availability, success, failure, hiding, and nothing kept after leaving. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceTextTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val support = ChatTestSupport()

    /** A transcriber the test steers: available or not, and what each file turns into. */
    private class FakeTranscriber(var available: Boolean = true) : VoiceTranscriber {
        var answer: suspend (File) -> String = { "Bin gleich da" }
        val files = mutableListOf<File>()

        override suspend fun isAvailable(): Boolean = available

        override suspend fun transcribe(file: File): String {
            files += file
            return answer(file)
        }
    }

    private fun model(context: ChatContext, id: String) = ConversationViewModel(id, context, FakeRealtime()).apply {
        pollInterval = Duration.INFINITE
        sweepInterval = Duration.INFINITE
        recordingTick = Duration.INFINITE
    }

    /** Mira sends Jonas a voice message; Jonas accepts and loads it. */
    private suspend fun voiceMessage(transcriber: FakeTranscriber, viewOnce: Boolean = false): Pair<ConversationViewModel, String> {
        val mira = support.member(1)
        val jonas = support.member(2, transcriber = transcriber)
        val id = mira.repository.createConversation("direct", null, listOf("2"), 7).id
        val writer = model(mira, id)
        writer.open()
        writer.startRecording()
        writer.finishRecording()
        if (viewOnce) writer.setViewOnce(writer.state.value.drafts.single().localId, true)
        writer.send()
        val reader = model(jonas, id)
        reader.open()
        val item = reader.state.value.timeline.items.last()
        val voice = item.content.attachments.single()
        reader.answer(item, voice, true)
        if (!viewOnce) reader.loadAttachment(voice)
        return reader to voice.id
    }

    @Test
    fun aVoiceMessageBecomesTextAndHidesAgain() = runTest {
        val transcriber = FakeTranscriber()
        val (reader, id) = voiceMessage(transcriber)
        assertTrue(reader.state.value.canTranscribe)
        val voice = reader.state.value.timeline.items.last().content.attachments.single()

        reader.transcribe(voice)
        assertEquals(Transcript.Shown("Bin gleich da"), reader.state.value.transcripts[id])
        assertEquals("the decrypted file on screen", 1, transcriber.files.size)

        reader.hideTranscript(id)
        assertEquals(Transcript.Hidden("Bin gleich da"), reader.state.value.transcripts[id])
        reader.hideTranscript(id)
        reader.transcribe(voice)
        assertEquals("shown again without a second run", Transcript.Shown("Bin gleich da"), reader.state.value.transcripts[id])
        assertEquals(1, transcriber.files.size)
        reader.hideTranscript("unbekannt")

        // Nothing recognised, a denied recognizer, or anything else going wrong: the texts of the spec.
        transcriber.answer = { throw TranscriptionProblem.Failed }
        reader.close()
        reader.open()
        reader.loadAttachment(voice)
        reader.transcribe(voice)
        assertEquals(Transcript.Failed("Das ließ sich nicht in Text umwandeln."), reader.state.value.transcripts[id])
        transcriber.answer = { throw TranscriptionProblem.Denied }
        reader.transcribe(voice)
        assertEquals(
            Transcript.Failed("Erlaube die Spracherkennung in den Einstellungen, dann kann Mildcrowd Sprachnachrichten in Text umwandeln."),
            reader.state.value.transcripts[id]
        )
        transcriber.answer = { throw IOException("kaputt") }
        reader.transcribe(voice)
        assertEquals(Transcript.Failed("Das ließ sich nicht in Text umwandeln."), reader.state.value.transcripts[id])
        // A cancelled transcription stays cancelled, it is no failure.
        transcriber.answer = { throw kotlinx.coroutines.CancellationException("weg") }
        assertTrue(runCatching { reader.transcribe(voice) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        clear(reader)
    }

    @Test
    fun theTextIsWipedWhenTheChatLocksOrIsLeft() = runTest {
        val transcriber = FakeTranscriber()
        val (reader, id) = voiceMessage(transcriber)
        val voice = reader.state.value.timeline.items.last().content.attachments.single()
        reader.transcribe(voice)
        assertTrue(reader.state.value.transcripts.isNotEmpty())
        reader.close()
        assertTrue("left: no text kept", reader.state.value.transcripts.isEmpty())

        // A text that arrives after the chat closed is not kept either.
        reader.open()
        reader.loadAttachment(voice)
        val gate = CompletableDeferred<String>()
        transcriber.answer = { gate.await() }
        val running = launch { reader.transcribe(voice) }
        runCurrent()
        assertEquals(Transcript.Working, reader.state.value.transcripts[id])
        reader.close()
        gate.complete("Zu spät")
        running.join()
        assertNull(reader.state.value.transcripts[id])
        clear(reader)
    }

    @Test
    fun noButtonWithoutOnDeviceRecognitionAndNothingForAFileNotOnScreen() = runTest {
        val transcriber = FakeTranscriber(available = false)
        val (reader, id) = voiceMessage(transcriber, viewOnce = true)
        assertFalse(reader.state.value.canTranscribe)
        // A view-once voice message is never on screen as a file: nothing to turn into text.
        reader.transcribe(reader.state.value.timeline.items.last().content.attachments.single())
        assertNull(reader.state.value.transcripts[id])
        assertTrue(transcriber.files.isEmpty())
        // Asked once per screen.
        transcriber.available = true
        reader.open()
        assertFalse(reader.state.value.canTranscribe)
        clear(reader)
    }
}
