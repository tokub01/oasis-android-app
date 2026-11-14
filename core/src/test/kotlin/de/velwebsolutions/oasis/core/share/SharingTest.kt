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
                Uri.parse("content:ohne-anbieter"),
                sent("x/y/z/gross.zip", ByteArray(200_000))
            )
        )
        val files = checkNotNull(ShareInbox.read(context, several, maxBytes = 1_000)).files
        assertEquals("a missing file and one without a provider are skipped", 2, files.size)
        assertEquals(listOf("anonym.bin", "application/octet-stream"), listOf(files[0].name, files[0].mime))
        assertTrue("a large file is copied only a little beyond the limit", files[1].file.length() in 1_001L..70_000L)
        assertTrue("a newer share leaves the copies of an earlier one to that share", file.file.exists())
    }

    @Test
    fun aNewerShareNeverDeletesOrOverwritesTheCopiesOfAnother() {
        val photo = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, sent("image/png/bild.png", byteArrayOf(1, 2, 3)))
        val first = checkNotNull(ShareInbox.read(context, photo))
        PendingShare.offer(first)
        val again = checkNotNull(ShareInbox.read(context, photo))
        PendingShare.offer(again)
        assertArrayEquals("the same photo shared again keeps its copy", byteArrayOf(1, 2, 3), again.files.single().file.readBytes())
        assertFalse("the replaced share's copies are gone", checkNotNull(first.folder).exists())

        PendingShare.target("7")
        val taken = checkNotNull(PendingShare.take("7"))
        ShareInbox.read(context, photo)
        assertTrue("a share that comes in while a chat takes one leaves its copies alone", taken.files.single().file.exists())
        PendingShare.delete(taken)
        assertFalse("the chat deletes them with their folder", checkNotNull(taken.folder).exists())
    }

    @Test
    fun copiesAnEarlierProcessLeftBehindGoWithTheNextShare() {
        val inbox = File(context.cacheDir, "shared-in").apply { deleteRecursively() }
        val photo = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, sent("image/png/bild.png", byteArrayOf(1)))
        val waiting = checkNotNull(ShareInbox.read(context, photo))
        File(inbox, "frueherer-prozess/teilen/0/alt.png").apply {
            checkNotNull(parentFile).mkdirs()
            writeBytes(byteArrayOf(9))
        }
        ShareInbox.read(context, photo)
        assertFalse(File(inbox, "frueherer-prozess").exists())
        assertTrue("the shares of this process stay", waiting.files.single().file.exists())
    }

    /** Opened with Mildcrowd's own permissions, these would hand any app a copy of Mildcrowd's private files. */
    @Test
    fun oasisOwnFilesAreNeverReadAsAShare() {
        val own = context.packageName
        // Also under the user-id form, so `content://0@…` and its encoded `0%40…` reach the provider as on a device.
        Robolectric.setupContentProvider(SendingApp::class.java, "$own.chat.files;0@$own.chat.files")
        Robolectric.setupContentProvider(SendingApp::class.java, own)
        sent("image/png/geheim.png", byteArrayOf(7))
        val export = File(context.cacheDir, "Export/oasis-daten.zip").apply {
            checkNotNull(parentFile).mkdirs()
            writeBytes(byteArrayOf(4, 2))
        }
        listOf(
            Uri.fromFile(export),
            Uri.parse("content://$own.chat.files/image/png/geheim.png"),
            Uri.parse("content://0@$own.chat.files/image/png/geheim.png"),
            Uri.parse("content://0%40$own.chat.files/image/png/geheim.png"),
            Uri.parse("content://$own/image/png/geheim.png"),
            Uri.parse("android.resource://$own/raw/geheim")
        ).forEach { uri ->
            assertNull(uri.toString(), ShareInbox.read(context, Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)))
        }
        val shared = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, sent("image/png/bild.png", byteArrayOf(1)))
        assertEquals("another app's content still comes in", "Name von bild.png", ShareInbox.read(context, shared)?.files?.single()?.name)
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
