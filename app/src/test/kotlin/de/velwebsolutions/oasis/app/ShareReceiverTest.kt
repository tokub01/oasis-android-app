package de.velwebsolutions.oasis.app

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import de.velwebsolutions.oasis.app.shell.AppHost
import de.velwebsolutions.oasis.core.share.PendingShare
import de.velwebsolutions.oasis.design.navigation.ChatRoute
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.navigation.Navigator
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A gallery app's file under `content://oasis.test.gallery/<name>`. */
class Gallery : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri) = "image/png"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(File(context!!.cacheDir, uri.lastPathSegment!!), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? =
        null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}

/** Sharing into Mildcrowd: the receiving activity copies the files, keeps the share and opens "Teilen mit …". */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ShareReceiverTest {
    /** The real graph of MainActivityTest binds links to the debug server; these tests use mildcrowd.com links. */
    @org.junit.Before
    fun resetOwnHost() {
        de.velwebsolutions.oasis.design.navigation.DeepLink.ownHost = null
    }

    private val application = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun clear() = PendingShare.discard()

    @Test
    fun `a shared screenshot is copied, kept and opens Teilen mit`() {
        Robolectric.setupContentProvider(Gallery::class.java, "oasis.test.gallery")
        File(application.cacheDir, "screenshot.png").writeBytes(byteArrayOf(1, 2))
        val shared = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Uri.parse("content://oasis.test.gallery/screenshot.png"))
        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, shared).setup()
        val end = System.currentTimeMillis() + 5_000
        while (!controller.get().isFinishing && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue(controller.get().isFinishing)
        assertEquals("screenshot.png", PendingShare.share.value?.files?.single()?.name)
        val opened = shadowOf(application).nextStartedActivity
        assertEquals(MainActivity::class.java.name, opened.component?.className)
        assertTrue(opened.getBooleanExtra(AppHost.EXTRA_SHARE, false))

        val navigator = Navigator()
        AppHost.open(opened, navigator)
        assertEquals(MainTab.Chats, navigator.selectedTab)
        assertEquals(listOf(ChatRoute.Share), navigator.stack(MainTab.Chats).toList())
        AppHost.open(Intent(Intent.ACTION_VIEW, Uri.parse("https://mildcrowd.com/app/nachrichten")), navigator)
        AppHost.open(null, navigator)

        // Back in front with the share still waiting: "Teilen mit …" again, not twice, and nothing without a share.
        val elsewhere = Navigator()
        AppHost.openPendingShare(elsewhere)
        assertEquals(listOf(ChatRoute.Share), elsewhere.stack(MainTab.Chats).toList())
        AppHost.openPendingShare(elsewhere)
        assertEquals(listOf(ChatRoute.Share), elsewhere.stack(MainTab.Chats).toList())
        PendingShare.discard()
        val idle = Navigator()
        AppHost.openPendingShare(idle)
        assertTrue(idle.stack(MainTab.Chats).isEmpty())
    }

    @Test
    fun `nothing usable shared opens nothing`() {
        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, Intent(Intent.ACTION_SEND)).setup()
        val end = System.currentTimeMillis() + 5_000
        while (!controller.get().isFinishing && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue(controller.get().isFinishing)
        assertNull(PendingShare.share.value)
        assertNull(shadowOf(application).nextStartedActivity)
        kotlinx.coroutines.runBlocking { AppHost.receiveShare(controller.get(), null) }
        assertNull(shadowOf(application).nextStartedActivity)
    }
}
