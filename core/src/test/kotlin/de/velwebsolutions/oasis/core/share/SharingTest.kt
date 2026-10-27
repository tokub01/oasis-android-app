package de.velwebsolutions.oasis.core.share

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The sending app's files under `content://oasis.test.share/<type…>/<name>`; "anonym…" ones have no display name. */
class SendingApp : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri): String? = uri.pathSegments.dropLast(1).joinToString("/").ifEmpty { null }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(File(context!!.cacheDir, "sender/" + uri.lastPathSegment), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {
            if (!uri.lastPathSegment.orEmpty().startsWith("anonym")) addRow(arrayOf("Name von ${uri.lastPathSegment}"))
        }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}

/** What other apps share: copied into the cache with name and type, kept in memory until a chat takes it. */
@RunWith(RobolectricTestRunner::class)
class SharingTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    init {
        Robolectric.setupContentProvider(SendingApp::class.java, "oasis.test.share")
    }

    @After
    fun clear() = PendingShare.discard()

    private fun sent(path: String, bytes: ByteArray): Uri {
        File(context.cacheDir, "sender").mkdirs()
        File(context.cacheDir, "sender/" + path.substringAfterLast('/')).writeBytes(bytes)
        return Uri.parse("content://oasis.test.share/$path")
    }

    @Test
    fun oneOrSeveralFilesAndTextAreCopiedWithNameAndType() {
        val photo = sent("image/png/bild.png", byteArrayOf(1, 2, 3))
        val single = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, photo).putExtra(Intent.EXTRA_TEXT, "  Schau mal  ")
        val share = checkNotNull(ShareInbox.read(context, single))
        assertEquals("Schau mal", share.text)
        val file = share.files.single()
        assertEquals(listOf("Name von bild.png", "image/png"), listOf(file.name, file.mime))
        assertArrayEquals(byteArrayOf(1, 2, 3), file.file.readBytes())

        val several = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(
            Intent.EXTRA_STREAM,
            arrayListOf(
                sent("anonym.bin", byteArrayOf(9)),
                Uri.parse("content://oasis.test.share/fehlt.pdf"),
                sent("x/y/z/gross.zip", ByteArray(200_000))
            )
        )
        val files = checkNotNull(ShareInbox.read(context, several, maxBytes = 1_000)).files
        assertEquals("a missing file is skipped", 2, files.size)
        assertEquals(listOf("anonym.bin", "application/octet-stream"), listOf(files[0].name, files[0].mime))
        assertTrue("a large file is copied only a little beyond the limit", files[1].file.length() in 1_001L..70_000L)
        assertFalse("a new share empties the folder", file.file.exists())
    }

    @Test
    fun nothingUsableIsNoShare() {
        assertNull(ShareInbox.read(context, Intent(Intent.ACTION_VIEW)))
        assertNull(ShareInbox.read(context, Intent(Intent.ACTION_SEND)))
        assertNull(ShareInbox.read(context, Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "   ")))
        assertNull(ShareInbox.read(context, Intent(Intent.ACTION_SEND_MULTIPLE)))
        assertEquals("Nur Text", ShareInbox.read(context, Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "Nur Text"))?.text)
        val unreadable = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Uri.parse("content://kein.anbieter"))
        assertNull("an unreadable stream is skipped", ShareInbox.read(context, unreadable))
        val nameless = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, sent("image/png/anonym.png", byteArrayOf(1)))
        assertEquals("anonym.png", ShareInbox.read(context, nameless)?.files?.single()?.name)
        assertNull(ShareInbox.displayName(context, Uri.parse("content://kein.anbieter/x")))
    }

    @Test
    fun theChatItWasMeantForTakesItOnce() {
        val copy = File(context.cacheDir, "kopie.txt").apply { writeBytes(byteArrayOf(1)) }
        val share = Share(listOf(SharedFile(copy, "kopie.txt", "text/plain")), "Hallo")
        PendingShare.offer(share)
        assertEquals(share, PendingShare.share.value)
        assertNull("no chat picked yet", PendingShare.take("7"))
        PendingShare.target("7")
        assertNull(PendingShare.take("8"))
        assertEquals(share, PendingShare.take("7"))
        assertNull("only once", PendingShare.take("7"))
        assertNull(PendingShare.share.value)
        PendingShare.delete(share)
        assertFalse(copy.exists())

        val older = File(context.cacheDir, "alt.txt").apply { writeBytes(byteArrayOf(1)) }
        PendingShare.offer(Share(listOf(SharedFile(older, "alt.txt", "text/plain")), null))
        PendingShare.offer(Share(emptyList(), "neu"))
        assertFalse("a newer share deletes the older copies", older.exists())
        PendingShare.discard()
        assertNull(PendingShare.share.value)
    }
}
