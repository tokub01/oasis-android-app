package de.velwebsolutions.oasis.chat.core

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaCodec
import org.robolectric.shadows.ShadowMediaExtractor
import org.robolectric.shadows.ShadowSpeechRecognizer
import org.robolectric.shadows.util.DataSource

/**
 * The platform transcriber on Robolectric's speech recognizer and codecs: only Android 13+ with a German on-device model,
 * the decoded audio through EXTRA_AUDIO_SOURCE, the texts for every outcome – and never the network recognizer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AndroidVoiceTranscriberTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pcm = Pcm(byteArrayOf(1, 2, 3, 4), 44_100, 1)

    private fun transcriber(decode: (File) -> Pcm = { pcm }) =
        AndroidVoiceTranscriber(context, decode, StandardTestDispatcher(mainRule.dispatcher.scheduler))

    private val latest get() = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())

    @After
    fun reset() {
        ShadowSpeechRecognizer.reset()
        ShadowMediaCodec.clearCodecs()
        ShadowMediaExtractor.reset()
    }

    @Test
    @Config(sdk = [32])
    fun beforeAndroid13ThereIsNoButtonAndNoTranscription() = runTest {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        assertFalse(transcriber().isAvailable())
        assertThrows(TranscriptionProblem.Failed::class.java) { kotlinx.coroutines.runBlocking { transcriber().transcribe(File("x")) } }
    }

    @Test
    fun onlyAGermanModelOnTheDeviceCounts() = runTest {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        assertFalse("no on-device recognition at all", transcriber().isAvailable())

        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        fun languages(vararg installed: String) = RecognitionSupport.Builder().setInstalledOnDeviceLanguages(installed.toList()).build()
        val german = async { transcriber().isAvailable() }
        runCurrent()
        latest.triggerSupportResult(languages("en-US", "de-DE"))
        runCurrent()
        assertTrue(german.await())
        assertTrue("the recognizer is gone again", latest.isDestroyed)

        val english = async { transcriber().isAvailable() }
        runCurrent()
        latest.triggerSupportResult(languages("en-US"))
        runCurrent()
        assertFalse(english.await())

        val broken = async { transcriber().isAvailable() }
        runCurrent()
        latest.triggerSupportError(SpeechRecognizer.ERROR_SERVER)
        runCurrent()
        assertFalse(broken.await())
    }

    @Test
    fun theDecodedAudioGoesToTheOnDeviceRecognizerOnly() = runTest {
        val text = async { transcriber().transcribe(File("stimme.m4a")) }
        runCurrent()
        val intent = latest.lastRecognizerIntent
        assertEquals("de-DE", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertNotNull(
            "the audio comes from memory, not the microphone",
            intent.getParcelableExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, android.os.ParcelFileDescriptor::class.java)
        )
        assertEquals(44_100, intent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 0))
        assertEquals(1, intent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 0))
        latest.triggerOnResults(results("  ", " Bin gleich da "))
        runCurrent()
        assertEquals("Bin gleich da", text.await())
        assertTrue(latest.isDestroyed)
    }

    @Test
    fun everyOutcomeHasItsText() = runTest {
        suspend fun outcome(trigger: () -> Unit): TranscriptionProblem {
            val text = async { runCatching { transcriber().transcribe(File("stimme.m4a")) } }
            runCurrent()
            trigger()
            runCurrent()
            return text.await().exceptionOrNull() as TranscriptionProblem
        }
        assertSame(TranscriptionProblem.Failed, outcome { latest.triggerOnResults(results("")) })
        assertSame(TranscriptionProblem.Failed, outcome { latest.triggerOnResults(Bundle()) })
        assertSame(TranscriptionProblem.Failed, outcome { latest.triggerOnError(SpeechRecognizer.ERROR_NO_MATCH) })
        assertSame(TranscriptionProblem.Denied, outcome { latest.triggerOnError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) })

        // A file that does not decode never reaches a recognizer.
        val broken = async { runCatching { transcriber { error("kein Audio") }.transcribe(File("kaputt.m4a")) } }
        runCurrent()
        assertSame(TranscriptionProblem.Failed, broken.await().exceptionOrNull())

        // Leaving the chat while it listens stops the recognizer.
        val left = async { transcriber().transcribe(File("stimme.m4a")) }
        runCurrent()
        val recognizer = latest
        left.cancel()
        runCurrent()
        assertTrue(recognizer.isDestroyed)
    }

    @Test
    fun theListenerIgnoresEverythingButTheAnswer() {
        val listener = ResultListener({}, {})
        listener.onReadyForSpeech(null)
        listener.onBeginningOfSpeech()
        listener.onRmsChanged(1f)
        listener.onBufferReceived(null)
        listener.onEndOfSpeech()
        listener.onPartialResults(null)
        listener.onEvent(0, null)
        assertEquals("Das ließ sich nicht in Text umwandeln.", TranscriptionProblem.Failed.message)
        kotlinx.coroutines.runBlocking {
            assertFalse(NoTranscriber.isAvailable())
            assertThrows(TranscriptionProblem.Failed::class.java) { kotlinx.coroutines.runBlocking { NoTranscriber.transcribe(File("x")) } }
        }
    }

    @Test
    fun aVoiceMessageDecodesToPcmInMemory() {
        val file = File(context.cacheDir, "stimme.m4a").apply { writeBytes(byteArrayOf(0)) }
        val format = MediaFormat.createAudioFormat("audio/mp4a-latm", 16_000, 1)
        val samples = ByteArray(3_000) { it.toByte() }
        ShadowMediaExtractor.addTrack(DataSource.toDataSource(file.path), format, samples)
        ShadowMediaCodec.addDecoder(
            "audio/mp4a-latm",
            ShadowMediaCodec.CodecConfig(1_024, 1_024) { input: ByteBuffer, output: ByteBuffer -> output.put(input) }
        )
        val decoded = PcmDecoder.decode(file)
        assertArrayEquals(samples, decoded.bytes)
        assertEquals(16_000, decoded.sampleRate)
        assertEquals(1, decoded.channels)

        val video = File(context.cacheDir, "film.mp4").apply { writeBytes(byteArrayOf(0)) }
        ShadowMediaExtractor.addTrack(DataSource.toDataSource(video.path), MediaFormat.createVideoFormat("video/avc", 2, 2), ByteArray(4))
        assertThrows(NoSuchElementException::class.java) { PcmDecoder.decode(video) }
        assertEquals(MediaCodec.BUFFER_FLAG_END_OF_STREAM, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    @Test
    fun theDecodingLoopWaitsWhileTheCodecIsBusy() {
        // The codec answers "try again later" for input and output once each, then changes its format, then delivers.
        val port = object : DecoderPort {
            var inputs = listOf(-1, 0, 0)
            var samples = listOf(2, -1)
            var outputs = listOf(MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_FORMAT_CHANGED, 0, 1)
            val queued = mutableListOf<Pair<Int, Boolean>>()

            override fun dequeueInput(): Int = inputs.first().also { inputs = inputs.drop(1) }

            override fun readSample(index: Int): Int = samples.first().also { samples = samples.drop(1) }

            override fun queueInput(index: Int, size: Int, end: Boolean) {
                queued += size to end
            }

            override fun dequeueOutput(info: MediaCodec.BufferInfo): Int = outputs.first().also { index ->
                outputs = outputs.drop(1)
                if (index == 1) info.set(0, 1, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM) else info.set(0, 2, 0, 0)
            }

            override fun output(index: Int, info: MediaCodec.BufferInfo): ByteArray = ByteArray(info.size) { (index + 1).toByte() }

            override fun outputFormat(): MediaFormat = MediaFormat.createAudioFormat("audio/raw", 48_000, 2)
        }
        val pcm = PcmDecoder.drain(port, 44_100, 1)
        assertArrayEquals(byteArrayOf(1, 1, 2), pcm.bytes)
        assertEquals(48_000, pcm.sampleRate)
        assertEquals(2, pcm.channels)
        assertEquals(listOf(2 to false, 0 to true), port.queued)
    }

    private fun results(vararg texts: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, ArrayList(texts.toList()))
    }
}
