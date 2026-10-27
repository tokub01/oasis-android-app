package de.velwebsolutions.oasis.chat.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.VideoView
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.util.DataSource

/** Hands out files of the cache under `content://oasis.test.files/<type>/<name>`, like the photo picker's provider. */
class PickedFiles : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri): String? = uri.pathSegments.dropLast(1).joinToString("/").takeIf { it != "none" }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(File(context!!.cacheDir, uri.lastPathSegment!!), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? =
        null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}

/** Picked photos and videos, voice recordings and decrypted files on a device (Robolectric's media shadows). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidChatMediaTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    init {
        Robolectric.setupContentProvider(PickedFiles::class.java, "oasis.test.files")
    }

    private fun picked(type: String, name: String, bytes: ByteArray): Uri {
        File(context.cacheDir, name).writeBytes(bytes)
        return Uri.parse("content://oasis.test.files/$type/$name")
    }

    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
    }

    private suspend fun problem(media: AndroidChatMedia, uri: Uri): String = try {
        media.prepare(uri, 1)
        "kein Problem"
    } catch (problem: AttachmentProblem) {
        problem.message.orEmpty()
    }

    @Test
    fun photosAreReEncodedAsJpegAndCheckedAgainstTheLimit() = runTest {
        val media = AndroidChatMedia(context)
        val photo = media.prepare(picked("image/png", "foto.png", png(3000, 1500)), 4)
        assertEquals(4L, photo.localId)
        assertEquals(AttachmentKind.Image, photo.kind)
        assertEquals("image/jpeg", photo.mime)
        assertEquals(2048 to 1024, photo.width to photo.height)
        assertEquals(0xFF, photo.data[0].toInt() and 0xFF)

        assertEquals("Dieses Foto ließ sich nicht öffnen.", problem(media, picked("image/heic", "kaputt.heic", "kein Bild".toByteArray())))
        assertEquals(
            AndroidChatMedia.PHOTO_TOO_LARGE,
            problem(AndroidChatMedia(context, maxImageBytes = 10), picked("image/png", "b.png", png(40, 40)))
        )
        assertEquals("Die Datei ließ sich nicht lesen.", problem(media, Uri.parse("content://oasis.test.files/image/png/fehlt.png")))
        assertEquals(
            "Das kann Oasis nicht verschicken. Wähl ein Foto oder ein Video.",
            problem(media, picked("application/pdf", "a.pdf", byteArrayOf(1)))
        )
        assertEquals(
            "Das kann Oasis nicht verschicken. Wähl ein Foto oder ein Video.",
            problem(media, picked("none", "x.bin", byteArrayOf(1)))
        )
    }

    @Test
    fun videosUpToAMinuteAndTheSizeLimitGoAsTheyAre() = runTest {
        val media = AndroidChatMedia(context, maxVideoBytes = 100_000)
        val clip = picked("video/mp4", "clip.mp4", ByteArray(70_000) { it.toByte() })
        ShadowMediaMetadataRetriever.addMetadata(
            DataSource.toDataSource(context, clip),
            MediaMetadataRetriever.METADATA_KEY_DURATION,
            "59000"
        )
        val video = media.prepare(clip, 2)
        assertEquals(AttachmentKind.Video, video.kind)
        assertEquals("video/mp4", video.mime)
        assertEquals(59_000L, video.durationMs)
        assertEquals(70_000, video.data.size)

        val long = picked("video/quicktime", "lang.mov", ByteArray(10))
        ShadowMediaMetadataRetriever.addMetadata(
            DataSource.toDataSource(context, long),
            MediaMetadataRetriever.METADATA_KEY_DURATION,
            "61000"
        )
        assertTrue(problem(media, long).startsWith("Das Video ist länger als 60 Sekunden."))

        val large = picked("video/webm", "gross.webm", ByteArray(200_000))
        ShadowMediaMetadataRetriever.addMetadata(
            DataSource.toDataSource(context, large),
            MediaMetadataRetriever.METADATA_KEY_DURATION,
            "1000"
        )
        assertEquals("Das Video ist größer als 50 MB. Nimm bitte einen kürzeren Ausschnitt.", problem(media, large))

        assertEquals("Dieses Video ließ sich nicht öffnen.", problem(media, picked("video/mp4", "ohne.mp4", ByteArray(10))))
        val broken = picked("video/mp4", "kaputt.mp4", ByteArray(10))
        ShadowMediaMetadataRetriever.addException(DataSource.toDataSource(context, broken), IllegalArgumentException("kaputt"))
        assertEquals("Dieses Video ließ sich nicht öffnen.", problem(media, broken))
    }

    /** A recorder whose steps the test controls: it fails where asked and remembers its info listener. */
    @Suppress("DEPRECATION")
    private class TestRecorder(val failPrepare: Boolean = false, val failStop: Boolean = false) : MediaRecorder() {
        var info: OnInfoListener? = null
        var released = false

        override fun setOnInfoListener(listener: OnInfoListener?) {
            info = listener
            super.setOnInfoListener(listener)
        }

        override fun prepare() {
            if (failPrepare) throw IOException("kein Mikrofon")
            super.prepare()
        }

        override fun stop() {
            if (failStop) throw IllegalStateException("nichts aufgenommen")
            super.stop()
        }

        override fun release() {
            released = true
            super.release()
        }
    }

    @Test
    fun voiceMessagesAreRecordedAsAacInMpeg4() = runTest {
        val recorders = mutableListOf<TestRecorder>()
        var next = { TestRecorder() }
        val media = AndroidChatMedia(context, newRecorder = { next().also { recorders += it } })
        assertNull("nothing recorded yet", media.finishRecording(1))

        var limits = 0
        assertTrue(media.startRecording { limits++ })
        val recorder = recorders.last()
        assertEquals(MediaRecorder.AudioEncoder.AAC, shadowOf(recorder).audioEncoder)
        assertEquals(MediaRecorder.OutputFormat.MPEG_4, shadowOf(recorder).outputFormat)
        assertEquals(180_000, shadowOf(recorder).maxDuration)
        recorder.info!!.onInfo(recorder, MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED, 0)
        recorder.info!!.onInfo(recorder, MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED, 0)
        assertEquals(1, limits)
        File(shadowOf(recorder).outputPath).writeBytes(byteArrayOf(4, 5, 6))
        ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(4))
        val voice = checkNotNull(media.finishRecording(7))
        assertEquals(AttachmentKind.Audio, voice.kind)
        assertEquals("audio/mp4", voice.mime)
        assertEquals(4_000L, voice.durationMs)
        assertArrayEquals(byteArrayOf(4, 5, 6), voice.data)
        assertTrue(recorder.released)
        assertFalse("the recording file is gone", File(shadowOf(recorder).outputPath).exists())

        assertTrue(media.startRecording {})
        File(shadowOf(recorders.last()).outputPath).writeBytes(ByteArray(0))
        assertNull("an empty recording is nothing", media.finishRecording(8))
        next = { TestRecorder(failStop = true) }
        assertTrue(media.startRecording {})
        assertNull(media.finishRecording(9))
        assertTrue(media.startRecording {})
        assertTrue("a new recording replaces the running one", media.startRecording {})
        media.cancelRecording()
        media.cancelRecording()
        assertTrue(recorders.all { it.released })

        next = { TestRecorder(failPrepare = true) }
        assertFalse(media.startRecording {})
        assertTrue(recorders.last().released)
        assertNotNull(AndroidChatMedia(context))
    }

    @Test
    fun decryptedFilesBecomePhotosOrPlayableFilesUntilCleared() = runTest {
        val media = AndroidChatMedia(context)
        val photo = media.present(AttachmentKind.Image, png(200, 100))
        assertEquals(200, (photo as AttachmentView.Photo).image.width)
        assertEquals(AttachmentView.Failed(AndroidChatMedia.UNVERIFIABLE), media.present(AttachmentKind.Image, byteArrayOf(1, 2)))

        val voice = media.present(AttachmentKind.Audio, byteArrayOf(1, 2, 3)) as AttachmentView.Playable
        assertTrue(voice.file.name.endsWith(".m4a"))
        assertNull(voice.poster)
        assertArrayEquals(byteArrayOf(1, 2, 3), voice.file.readBytes())

        val frame = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888)
        val next = File(media.folder, "anhang-2.mp4").path
        ShadowMediaMetadataRetriever.addFrame(DataSource.toDataSource(next), 0, frame)
        val video = media.present(AttachmentKind.Video, byteArrayOf(7)) as AttachmentView.Playable
        assertEquals(16, video.poster?.width)
        val withoutFrame = media.present(AttachmentKind.Video, byteArrayOf(8)) as AttachmentView.Playable
        assertNull(withoutFrame.poster)
        ShadowMediaMetadataRetriever.addException(
            DataSource.toDataSource(File(media.folder, "anhang-4.mp4").path),
            IllegalStateException("x")
        )
        assertNull((media.present(AttachmentKind.Video, byteArrayOf(9)) as AttachmentView.Playable).poster)

        media.discard(photo)
        media.discard(voice)
        assertFalse(voice.file.exists())
        assertTrue(video.file.exists())
        assertTrue(media.startRecording {})
        media.clear()
        assertFalse(video.file.exists())
        assertTrue(media.folder.exists())
        assertEquals(0, media.folder.listFiles()!!.size)
    }

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
        view.playDecrypted(File(context.cacheDir, "video.mp4")) { errors++ }
        val shadow = shadowOf(view)
        assertTrue(shadow.videoPath.endsWith("video.mp4"))
        shadow.onPreparedListener.onPrepared(MediaPlayer())
        assertEquals(org.robolectric.shadows.ShadowVideoView.START, shadow.currentVideoState)
        assertTrue(shadow.onErrorListener.onError(MediaPlayer(), 1, 0))
        assertEquals(1, errors)
    }

    @Test
    fun theConstantsMatchTheContract() {
        assertThrows(AttachmentProblem::class.java) { throw AttachmentProblem("x") }
        assertEquals(10L * 1024 * 1024, ChatAttachments.MAX_IMAGE_BYTES)
        assertEquals(52_428_784L, ChatAttachments.MAX_VIDEO_BYTES)
    }
}
