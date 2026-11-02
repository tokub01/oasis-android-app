package de.velwebsolutions.oasis.design.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * The playback speed of voice messages: „1×“ → „1,5×“ → „2×“ → „1×“. Remembered while the app runs and applied to every
 * voice message, also to the one playing right now (the same rule on the web and iOS).
 */
object VoiceSpeed {
    /** The speeds in their order. */
    val speeds = listOf(1f, 1.5f, 2f)

    /** The speed now. */
    var current by mutableFloatStateOf(1f)
        private set

    /** The next speed; the voice message playing takes it at once. */
    fun next() {
        current = speeds[(speeds.indexOf(current) + 1) % speeds.size]
        VoicePlayer.active?.applySpeed()
    }

    /** „1×“, „1,5×“, „2×“. */
    fun label(speed: Float = current): String = when (speed) {
        1f -> "1×"
        1.5f -> "1,5×"
        else -> "2×"
    }

    /** Back to 1× (tests). */
    fun reset() {
        current = 1f
    }
}

/**
 * One voice message (a decrypted chat file, a recording in the composer, a reported file in the moderation) on the
 * platform's MediaPlayer: play and pause, seek, the position and length, the speed of [VoiceSpeed]. Only one voice
 * message plays at a time: starting another pauses this one. Pausing keeps the position; at the end it goes back to the
 * start. The player is prepared on the first play or seek and freed with [release] (leaving the bubble or the page).
 */
class VoicePlayer(
    private val setSource: (MediaPlayer) -> Unit,
    /** The length the message names (`duration_ms`), shown until the file itself says; 0 when unknown. */
    knownDurationMs: Long,
    /** Sets the speed on the platform player ([platformSpeed]). */
    private val setSpeed: (MediaPlayer, Float) -> Unit,
    /** Transient audio focus while it plays ([SystemAudioFocus]); losing it (a call) pauses. */
    private val focus: AudioFocus,
    private val newPlayer: () -> MediaPlayer = ::MediaPlayer
) {
    /** A voice message in a file. */
    constructor(file: File, knownDurationMs: Long, focus: AudioFocus, newPlayer: () -> MediaPlayer = ::MediaPlayer) :
        this({ it.setDataSource(file.path) }, knownDurationMs, ::platformSpeed, focus, newPlayer)

    /** A voice message in memory (a recording in the composer): played without writing it to the device. */
    constructor(bytes: ByteArray, knownDurationMs: Long, focus: AudioFocus, newPlayer: () -> MediaPlayer = ::MediaPlayer) :
        this({ it.setDataSource(BytesSource(bytes)) }, knownDurationMs, ::platformSpeed, focus, newPlayer)

    /** Whether it is playing right now. */
    var playing by mutableStateOf(false)
        private set

    /** The file could not be played. */
    var failed by mutableStateOf(false)
        private set

    /** Played or moved once: the time shows the position, not only the length. */
    var started by mutableStateOf(false)
        private set

    /** The position in milliseconds. */
    var positionMs by mutableLongStateOf(0L)
        private set

    /** The length in milliseconds: the file's once prepared, the message's before. */
    var durationMs by mutableLongStateOf(knownDurationMs)
        private set

    private var player: MediaPlayer? = null

    /** Prepares the file to know its length (a voice message whose message names none); nothing plays. */
    fun load() {
        prepared()
    }

    /** „Abspielen“ / „Pausieren“. */
    fun toggle() = if (playing) pause() else play()

    /** Plays from the position; another voice message playing pauses. Without audio focus (a call) nothing plays. */
    fun play() {
        val current = prepared() ?: return
        active?.takeIf { it !== this }?.pause()
        if (!focus.request(::pause)) return
        active = this
        setSpeed(current, VoiceSpeed.current)
        current.start()
        playing = true
        started = true
    }

    /** Pauses and keeps the position. */
    fun pause() {
        val current = player ?: return
        if (current.isPlaying) current.pause()
        positionMs = current.currentPosition.toLong()
        playing = false
        focus.abandon()
    }

    /** Moves to [ms] (kept within the length); playing goes on from there. */
    fun seekTo(ms: Long) {
        val current = prepared() ?: return
        val target = ms.coerceIn(0L, durationMs)
        current.seekTo(target.toInt())
        positionMs = target
        started = true
    }

    /** Reads the position while playing (the screen asks a few times a second). */
    fun tick() {
        val current = player ?: return
        if (playing) positionMs = current.currentPosition.toLong()
    }

    /** The speed of [VoiceSpeed] for the voice message playing now. */
    internal fun applySpeed() {
        player?.takeIf { playing }?.let { setSpeed(it, VoiceSpeed.current) }
    }

    /** The prepared player, created on first use; null when the file cannot be played. */
    private fun prepared(): MediaPlayer? {
        player?.let { return it }
        if (failed) return null
        val created = newPlayer()
        return try {
            setSource(created)
            created.setAudioAttributes(SPEECH)
            // At the end: back to the start, as before the first play (only the length shows), focus given back.
            created.setOnCompletionListener {
                playing = false
                started = false
                it.seekTo(0)
                positionMs = 0
                focus.abandon()
            }
            created.prepare()
            durationMs = created.duration.toLong().takeIf { it > 0 } ?: durationMs
            player = created
            created
        } catch (_: Exception) {
            created.release()
            failed = true
            null
        }
    }

    /** Stops and frees the player. */
    fun release() {
        player?.release()
        player = null
        if (playing) focus.abandon()
        playing = false
        if (active === this) active = null
    }

    companion object {
        /** The voice message playing (or last played) in the app: only one at a time. */
        internal var active: VoicePlayer? = null
            private set
    }
}

/** Spoken audio: the attributes of the player and of its audio focus. */
private val SPEECH: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

/** Audio focus for a voice message: requested when it starts, given back when it pauses or ends. */
interface AudioFocus {
    /** Asks for transient focus; [onLoss] runs when another app takes it (a call). False when it is refused. */
    fun request(onLoss: () -> Unit): Boolean

    /** Gives the focus back. */
    fun abandon()
}

/** [AudioFocus] of the platform: AUDIOFOCUS_GAIN_TRANSIENT for speech; any loss pauses. */
class SystemAudioFocus(context: Context) : AudioFocus {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var held: AudioFocusRequest? = null

    override fun request(onLoss: () -> Unit): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(SPEECH)
            .setOnAudioFocusChangeListener { change -> if (change < 0) onLoss() }
            .build()
        held = request
        return manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandon() {
        held?.let(manager::abandonAudioFocusRequest)
        held = null
    }
}

/** The platform's speed for a player (PlaybackParams; set only while it plays or right before it starts). */
internal fun platformSpeed(player: MediaPlayer, speed: Float) {
    player.playbackParams = PlaybackParams().setSpeed(speed)
}

/** A recording in memory for the MediaPlayer, so a preview never needs a file. */
internal class BytesSource(private val bytes: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= bytes.size) return -1
        val count = minOf(size, bytes.size - position.toInt())
        System.arraycopy(bytes, position.toInt(), buffer, offset, count)
        return count
    }

    override fun getSize(): Long = bytes.size.toLong()

    override fun close() = Unit
}

/**
 * Prepares the platform video view for a video in the cache (decrypted chat file, reported file in the moderation):
 * system controls, start when ready, [onError] when the file cannot be played.
 */
fun VideoView.playFile(file: File, onError: () -> Unit) {
    setMediaController(MediaController(context).also { it.setAnchorView(this) })
    setOnPreparedListener { start() }
    setOnErrorListener { _, _, _ ->
        onError()
        true
    }
    setVideoPath(file.path)
}
