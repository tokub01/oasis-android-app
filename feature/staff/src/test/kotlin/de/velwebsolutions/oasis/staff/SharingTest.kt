package de.velwebsolutions.oasis.staff

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import de.velwebsolutions.oasis.core.auth.InMemoryTokenStore
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.staff.data.StaffFiles
import de.velwebsolutions.oasis.staff.di.StaffModule
import de.velwebsolutions.oasis.staff.ui.components.Sharing
import de.velwebsolutions.oasis.staff.ui.components.StaffFileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The share sheet for the evidence archive and the drafted breach report. */
@RunWith(RobolectricTestRunner::class)
class SharingTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val session = Session(InMemoryTokenStore("token"), "Pixel Test", CoroutineScope(Dispatchers.Unconfined))

    private fun sent(): Intent = requireNotNull(shadowOf(context).nextStartedActivity) {
        "no share sheet"
    }.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!

    @Test
    fun `the evidence archive goes out as a readable file`() {
        val archive = StaffFiles(context.cacheDir).evidenceArchive(4, "PK".toByteArray())
        assertEquals("oasis-fall-4-beweise.zip", archive.name)
        Sharing.shareFile(context, archive, "application/zip", "Beweis-Archiv teilen")
        val send = sent()
        val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals(uri, send.clipData?.getItemAt(0)?.uri)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(StaffFileProvider() is FileProvider)
        assertEquals(
            java.io.File(context.cacheDir, "Beweise/oasis-fall-5-beweise.zip"),
            StaffModule.files(context, session).evidenceArchive(5, ByteArray(0))
        )
    }

    @Test
    fun `archives and reported files of an ended process go at once, all of them on signing out`() = runTest {
        val leftovers = StaffFiles(context.cacheDir)
        val archive = leftovers.evidenceArchive(6, "PK".toByteArray())
        val voice = leftovers.reportedAttachment(6, 1, "ID3".toByteArray())
        val files = StaffModule.files(context, session)
        assertFalse("the archive a process left behind", archive.exists())
        assertFalse(voice.exists())

        val shared = files.evidenceArchive(7, "PK".toByteArray())
        val played = files.reportedAttachment(7, 0, "ID3".toByteArray(), "Rechnung.pdf")
        assertTrue(shared.exists() && played.exists())
        session.signOut()
        assertFalse("signed out, no evidence stays on the device", shared.exists())
        assertFalse(played.exists())
    }

    @Test
    fun `the drafted report goes out as text, and without an app nothing happens`() {
        Sharing.shareText(context, "Meldung nach Art. 33", "Text teilen oder kopieren")
        assertEquals("Meldung nach Art. 33", sent().getStringExtra(Intent.EXTRA_TEXT))
        shadowOf(context).checkActivities(true)
        Sharing.shareText(context, "Meldung", "Teilen")
        assertNull(shadowOf(context).nextStartedActivity)
    }
}
