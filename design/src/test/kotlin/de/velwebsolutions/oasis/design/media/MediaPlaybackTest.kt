package de.velwebsolutions.oasis.design.media

import android.content.Context
import android.media.MediaPlayer
import android.widget.VideoView
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/** Voice messages and videos in the cache (chat files, reported files): play, pause, errors (Robolectric's media shadows). */
@RunWith(RobolectricTestRunner::class)
class MediaPlaybackTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun voiceMessagesPlayAndPauseAndReportUnplayableFiles() {
        val file = File(context.cacheDir, "stimme.m4a").apply { writeBytes(byteArrayOf(1)) }
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(file.path), ShadowMediaPlayer.MediaInfo(3_000, 0))
        val players = mutableListOf<MediaPlayer>()
        val voice = VoicePlayer(file) { MediaPlayer().also { players += it } }
        voice.toggle()
        assertTrue(voice.playing)
        voice.toggle()
        assertFalse(voice.playing)
        voice.toggle()
        assertTrue(voice.playing)
        shadowOf(players.single()).invokeCompletionListener()
        assertFalse(voice.playing)
        voice.release()
        voice.release()
        assertFalse(voice.failed)

        val unknown = VoicePlayer(File(context.cacheDir, "unbekannt.m4a"))
        unknown.toggle()
        assertTrue(unknown.failed)
        assertFalse(unknown.playing)
    }

    @Test
    fun videosPlayInThePlatformViewAndReportErrors() {
        val view = VideoView(context)
        var errors = 0
        view.playFile(File(context.cacheDir, "video.mp4")) { errors++ }
        val shadow = shadowOf(view)
        assertTrue(shadow.videoPath.endsWith("video.mp4"))
        shadow.onPreparedListener.onPrepared(MediaPlayer())
        assertEquals(org.robolectric.shadows.ShadowVideoView.START, shadow.currentVideoState)
        assertTrue(shadow.onErrorListener.onError(MediaPlayer(), 1, 0))
        assertEquals(1, errors)
    }
}
