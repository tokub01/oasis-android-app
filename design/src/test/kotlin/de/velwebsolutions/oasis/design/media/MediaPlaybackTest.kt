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

    /** Audio focus the test grants or refuses, and takes away like a call. */
    private class FakeFocus(var grants: Boolean = true) : AudioFocus {
        var held = false
        var requests = 0
        var onLoss: () -> Unit = {}

        override fun request(onLoss: () -> Unit): Boolean {
            requests++
            this.onLoss = onLoss
            held = grants
            return grants
        }

        override fun abandon() {
            held = false
        }
    }

    private val focus = FakeFocus()

    @Test
    fun voiceMessagesPlayAndPauseAndReportUnplayableFiles() {
        val file = File(context.cacheDir, "stimme.m4a").apply { writeBytes(byteArrayOf(1)) }
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(file.path), ShadowMediaPlayer.MediaInfo(3_000, 0))
        val players = mutableListOf<MediaPlayer>()
        val voice = VoicePlayer(file, 0, focus) { MediaPlayer().also { players += it } }
        voice.toggle()
        assertTrue(voice.playing)
        assertTrue("a voice message holds transient focus while it plays", focus.held)
        voice.toggle()
        assertFalse(voice.playing)
        assertFalse(focus.held)
        voice.toggle()
        assertTrue(voice.playing)
        // A call takes the focus: it pauses.
        focus.onLoss()
        assertFalse(voice.playing)
        assertFalse(focus.held)
        voice.toggle()
        // At the end: back to the start, as before the first play, focus given back.
        shadowOf(players.single()).invokeCompletionListener()
        assertFalse(voice.playing)
        assertFalse(voice.started)
        assertEquals(0L, voice.positionMs)
        assertFalse(focus.held)
        voice.toggle()
        voice.release()
        assertFalse("released while playing: focus given back", focus.held)
        voice.release()
        assertFalse(voice.failed)

        val unknown = VoicePlayer(File(context.cacheDir, "unbekannt.m4a"), 0, focus)

        // Focus refused (a call is going on): nothing plays.
        val refused = FakeFocus(grants = false)
        val quiet = VoicePlayer(file, 0, refused)
        quiet.play()
        assertFalse(quiet.playing)
        assertEquals(1, refused.requests)
        quiet.release()
        unknown.toggle()
        assertTrue(unknown.failed)
        assertFalse(unknown.playing)
    }

    @Test
    fun seekSpeedAndOneVoiceMessageAtATime() {
        VoiceSpeed.reset()
        val first = File(context.cacheDir, "eins.m4a").apply { writeBytes(byteArrayOf(1)) }
        val second = File(context.cacheDir, "zwei.m4a").apply { writeBytes(byteArrayOf(2)) }
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(first.path), ShadowMediaPlayer.MediaInfo(45_000, 0))
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(second.path), ShadowMediaPlayer.MediaInfo(10_000, 0))
        val speeds = mutableListOf<Float>()
        val players = mutableListOf<MediaPlayer>()
        val one = VoicePlayer({ it.setDataSource(first.path) }, 44_000, { _, speed -> speeds += speed }, focus) {
            MediaPlayer().also {
                players +=
                    it
            }
        }
        val two = VoicePlayer({ it.setDataSource(second.path) }, 0, { _, speed -> speeds += speed }, focus)

        // Before the first play: the length the message names, no position.
        assertEquals(44_000L, one.durationMs)
        assertFalse(one.started)
        one.tick()
        two.load()
        assertEquals("the file says how long it is", 10_000L, two.durationMs)
        assertFalse(two.started)
        one.seekTo(12_000)
        assertEquals("prepared: the file's length", 45_000L, one.durationMs)
        assertEquals(12_000L, one.positionMs)
        assertTrue(one.started)
        one.seekTo(99_000)
        assertEquals("kept within the length", 45_000L, one.positionMs)
        one.seekTo(-5)
        assertEquals(0L, one.positionMs)

        one.play()
        assertTrue(one.playing)
        shadowOf(players.single()).setCurrentPosition(7_000)
        one.tick()
        assertEquals(7_000L, one.positionMs)
        one.pause()
        shadowOf(players.single()).setCurrentPosition(9_000)
        one.tick()
        assertEquals("paused: the position stays", 7_000L, one.positionMs)
        one.play()

        // The speed goes round and reaches the voice message playing.
        assertEquals("1×", VoiceSpeed.label())
        VoiceSpeed.next()
        assertEquals("1,5×", VoiceSpeed.label())
        VoiceSpeed.next()
        assertEquals("2×", VoiceSpeed.label())
        VoiceSpeed.next()
        assertEquals("1×", VoiceSpeed.label())
        assertEquals("every play and every change while playing", listOf(1f, 1f, 1.5f, 2f, 1f), speeds)

        // Another one starts: the first pauses where it was.
        two.play()
        assertFalse(one.playing)
        assertTrue(two.playing)
        assertEquals("the player's position when it paused", 9_000L, one.positionMs)
        two.pause()
        two.pause()
        VoiceSpeed.next()
        assertEquals("nothing playing: the speed waits for the next play", 6, speeds.size)
        two.release()
        one.release()
        VoiceSpeed.next()
        VoiceSpeed.reset()

        // At the end: back to the start, play again.
        val three = VoicePlayer({ it.setDataSource(first.path) }, 0, { _, _ -> }, focus) { MediaPlayer().also { players += it } }
        three.play()
        shadowOf(players.last()).invokeCompletionListener()
        assertFalse(three.playing)
        assertEquals(0L, three.positionMs)
        three.release()

        // A file that cannot be played stays failed, without seeking or playing.
        val broken = VoicePlayer(byteArrayOf(1, 2, 3), 0, focus)
        broken.seekTo(1_000)
        assertTrue(broken.failed)
        broken.play()
        broken.pause()
        assertFalse(broken.playing)
        val unknown = VoicePlayer({ error("kaputt") }, 3_000, { _, _ -> }, focus)
        unknown.toggle()
        assertTrue(unknown.failed)
        assertEquals(3_000L, unknown.durationMs)
    }

    @Test
    fun theDefaultSpeedGoesToThePlatformPlayer() {
        val file = File(context.cacheDir, "tempo.m4a").apply { writeBytes(byteArrayOf(1)) }
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(file.path), ShadowMediaPlayer.MediaInfo(3_000, 0))
        val voice = VoicePlayer(file, 3_000, focus)
        voice.play()
        assertTrue(voice.playing)
        voice.release()
        platformSpeed(MediaPlayer(), 1.5f)

        // A file without a length keeps the one the message names.
        val silent = File(context.cacheDir, "stumm.m4a").apply { writeBytes(byteArrayOf(1)) }
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(silent.path), ShadowMediaPlayer.MediaInfo(0, 0))
        val named = VoicePlayer(silent, 8_000, focus)
        named.load()
        assertEquals(8_000L, named.durationMs)
        named.release()
    }

    @Test
    fun thePlatformFocusIsTransientForSpeechAndALossPauses() {
        val manager = context.getSystemService(android.media.AudioManager::class.java)
        val platform = SystemAudioFocus(context)
        var losses = 0
        assertTrue(platform.request { losses++ })
        val request = checkNotNull(shadowOf(manager).lastAudioFocusRequest)
        assertEquals(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, request.durationHint)
        assertEquals(android.media.AudioAttributes.CONTENT_TYPE_SPEECH, request.audioFocusRequest.audioAttributes.contentType)
        request.listener.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(0, losses)
        request.listener.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(1, losses)
        platform.abandon()
        platform.abandon()

        // During a call Android refuses the focus.
        shadowOf(manager).setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertFalse(platform.request {})
    }

    @Test
    fun aRecordingInMemoryIsReadWithoutAFile() {
        var source: android.media.MediaDataSource? = null
        val voice = VoicePlayer(byteArrayOf(1, 2, 3), 1_000, focus) {
            object : MediaPlayer() {
                override fun setDataSource(dataSource: android.media.MediaDataSource) {
                    source = dataSource
                }
            }
        }
        voice.load()
        val read = checkNotNull(source)
        assertEquals(3L, read.size)
        val buffer = ByteArray(8)
        assertEquals(2, read.readAt(1, buffer, 0, 8))
        assertEquals(listOf<Byte>(2, 3), buffer.take(2))
        assertEquals(-1, read.readAt(3, buffer, 0, 8))
        read.close()
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
