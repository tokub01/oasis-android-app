package de.velwebsolutions.oasis.design.media

import android.media.MediaPlayer
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * Play and pause of one voice message in the cache (a decrypted chat file, a reported one in the moderation; MediaPlayer
 * on the file). The player is created on the first play and released with [release] (leaving the bubble or the page).
 */
class VoicePlayer(private val file: File, private val newPlayer: () -> MediaPlayer = ::MediaPlayer) {
    /** Whether it is playing right now. */
    var playing by mutableStateOf(false)
        private set

    /** The file could not be played. */
    var failed by mutableStateOf(false)
        private set

    private var player: MediaPlayer? = null

    /** "Abspielen" / "Pausieren". */
    fun toggle() {
        val current = player
        when {
            current == null -> start()

            current.isPlaying -> {
                current.pause()
                playing = false
            }

            else -> {
                current.start()
                playing = true
            }
        }
    }

    private fun start() {
        val created = newPlayer()
        try {
            created.setDataSource(file.path)
            created.setOnCompletionListener {
                playing = false
                it.seekTo(0)
            }
            created.prepare()
            created.start()
            player = created
            playing = true
        } catch (_: Exception) {
            created.release()
            failed = true
        }
    }

    /** Stops and frees the player. */
    fun release() {
        player?.release()
        player = null
        playing = false
    }
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
