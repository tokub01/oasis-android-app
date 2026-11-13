package de.velwebsolutions.oasis.chat.core

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Why a voice message could not be turned into text. */
sealed class TranscriptionProblem(val text: String) : Exception(text) {
    /** Nothing was recognised, or the recognizer failed. */
    data object Failed : TranscriptionProblem("Das ließ sich nicht in Text umwandeln.")

    /** The speech recognition is not allowed for Oasis. */
    data object Denied : TranscriptionProblem(
        "Erlaube die Spracherkennung in den Einstellungen, dann kann Mildcrowd Sprachnachrichten in Text umwandeln."
    )
}

/**
 * Voice messages as text, on the device only: the chat is end-to-end encrypted, so neither the audio nor the text may
 * reach any server – a recognizer that might send them is never used (the same rule as on iOS).
 */
interface VoiceTranscriber {
    /** Whether German speech can be recognised on this device itself. */
    suspend fun isAvailable(): Boolean

    /**
     * The text of a decrypted voice message in the chat's cache.
     * @throws TranscriptionProblem
     */
    suspend fun transcribe(file: File): String
}

/** No transcription (previews, tests that do not need it). */
object NoTranscriber : VoiceTranscriber {
    override suspend fun isAvailable(): Boolean = false

    override suspend fun transcribe(file: File): String = throw TranscriptionProblem.Failed
}

/** Decoded audio: 16-bit PCM in memory, never written to the device. */
class Pcm(val bytes: ByteArray, val sampleRate: Int, val channels: Int)

/**
 * Android's on-device recognizer (`createOnDeviceSpeechRecognizer`), fed with the decoded voice message through
 * `EXTRA_AUDIO_SOURCE` – Android 13 (API 33) and later, and only with a German model installed on the device; nothing
 * falls back to the recognizer that uses Google's servers. The audio comes from the file, so no microphone permission is
 * needed. Recognizers live on the main thread.
 */
class AndroidVoiceTranscriber(
    private val context: Context,
    /** Decodes the voice message to PCM in memory ([PcmDecoder.decode]). */
    private val decode: (File) -> Pcm,
    /** Where decoding runs (Dispatchers.IO). */
    private val io: CoroutineDispatcher
) : VoiceTranscriber {
    override suspend fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && germanOnDevice()

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun germanOnDevice(): Boolean = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return@withContext false
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        try {
            suspendCancellableCoroutine { done ->
                recognizer.checkRecognitionSupport(
                    intent(),
                    Runnable::run,
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(support: RecognitionSupport) {
                            done.resume(support.installedOnDeviceLanguages.any { it.startsWith(GERMAN_PREFIX) })
                        }

                        override fun onError(error: Int) {
                            done.resume(false)
                        }
                    }
                )
            }
        } finally {
            recognizer.destroy()
        }
    }

    override suspend fun transcribe(file: File): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) throw TranscriptionProblem.Failed
        val pcm = withContext(io) { runCatching { decode(file) }.getOrNull() } ?: throw TranscriptionProblem.Failed
        return recognize(pcm)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun recognize(pcm: Pcm): String = withContext(Dispatchers.Main) {
        val (source, sink) = ParcelFileDescriptor.createPipe()
        // The decoded audio goes through a pipe in memory; the recognizer reads it like a microphone.
        val writer = Thread {
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(sink).use { it.write(pcm.bytes) } }
        }.apply { start() }
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        try {
            suspendCancellableCoroutine { done ->
                recognizer.setRecognitionListener(
                    ResultListener(
                        onText = { done.resume(it) },
                        onProblem = { done.resumeWithException(it) }
                    )
                )
                recognizer.startListening(
                    intent()
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, pcm.channels)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, pcm.sampleRate)
                )
                done.invokeOnCancellation { recognizer.cancel() }
            }
        } finally {
            recognizer.destroy()
            source.close()
            writer.interrupt()
        }
    }

    /** German, on the device, without partial results. */
    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, GERMAN)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

    private companion object {
        const val GERMAN = "de-DE"
        const val GERMAN_PREFIX = "de"
    }
}

/** The recognizer's answer: the best text, or why there is none. */
internal class ResultListener(private val onText: (String) -> Unit, private val onProblem: (TranscriptionProblem) -> Unit) :
    RecognitionListener {
    override fun onResults(results: Bundle) {
        val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }
        if (text == null) onProblem(TranscriptionProblem.Failed) else onText(text.trim())
    }

    override fun onError(error: Int) {
        onProblem(
            if (error ==
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            ) {
                TranscriptionProblem.Denied
            } else {
                TranscriptionProblem.Failed
            }
        )
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit

    override fun onBeginningOfSpeech() = Unit

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() = Unit

    override fun onPartialResults(partialResults: Bundle?) = Unit

    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}

/** Decodes a voice message (AAC in MP4, as recorded) to 16-bit PCM in memory with the platform's codec. */
object PcmDecoder {
    private const val TIMEOUT_US = 10_000L

    /** @throws NoSuchElementException without an audio track */
    fun decode(file: File): Pcm {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/")
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return drain(
                    PlatformDecoder(extractor, codec),
                    format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                )
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    /** Feeds samples and collects the decoded audio until the codec says the end came out. */
    internal fun drain(port: DecoderPort, sampleRate: Int, channels: Int): Pcm {
        val out = ByteArrayOutputStream()
        var rate = sampleRate
        var count = channels
        var inputDone = false
        val info = MediaCodec.BufferInfo()
        while (true) {
            if (!inputDone) inputDone = feed(port)
            val index = port.dequeueOutput(info)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                rate = port.outputFormat().getInteger(MediaFormat.KEY_SAMPLE_RATE)
                count = port.outputFormat().getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else if (index >= 0) {
                out.write(port.output(index, info))
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        return Pcm(out.toByteArray(), rate, count)
    }

    /** Hands the codec the next sample; true once the end went in. */
    private fun feed(port: DecoderPort): Boolean {
        val index = port.dequeueInput()
        if (index < 0) return false
        val size = port.readSample(index)
        port.queueInput(index, size.coerceAtLeast(0), end = size < 0)
        return size < 0
    }

    /** [DecoderPort] on the platform's extractor and codec. */
    private class PlatformDecoder(private val extractor: MediaExtractor, private val codec: MediaCodec) : DecoderPort {
        override fun dequeueInput(): Int = codec.dequeueInputBuffer(TIMEOUT_US)

        override fun readSample(index: Int): Int = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)

        override fun queueInput(index: Int, size: Int, end: Boolean) {
            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, if (end) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
            extractor.advance()
        }

        override fun dequeueOutput(info: MediaCodec.BufferInfo): Int = codec.dequeueOutputBuffer(info, TIMEOUT_US)

        override fun output(index: Int, info: MediaCodec.BufferInfo): ByteArray {
            val buffer = codec.getOutputBuffer(index)!!
            val chunk = ByteArray(info.size)
            buffer.position(info.offset)
            buffer.get(chunk)
            codec.releaseOutputBuffer(index, false)
            return chunk
        }

        override fun outputFormat(): MediaFormat = codec.outputFormat
    }
}

/** What the decoding loop needs of the codec and the extractor (the codec may say "try again later" any time). */
internal interface DecoderPort {
    /** An input buffer, or below 0 while none is free. */
    fun dequeueInput(): Int

    /** Reads the next sample into input buffer [index]: its size, or below 0 at the end. */
    fun readSample(index: Int): Int

    fun queueInput(index: Int, size: Int, end: Boolean)

    /** An output buffer, INFO_OUTPUT_FORMAT_CHANGED, or below 0 while none is ready. */
    fun dequeueOutput(info: MediaCodec.BufferInfo): Int

    /** The decoded bytes of output buffer [index], which is released then. */
    fun output(index: Int, info: MediaCodec.BufferInfo): ByteArray

    fun outputFormat(): MediaFormat
}
