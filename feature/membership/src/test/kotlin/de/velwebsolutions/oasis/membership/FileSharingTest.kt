package de.velwebsolutions.oasis.membership

import android.app.Application
import android.content.Intent
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import de.velwebsolutions.oasis.membership.data.MembershipFiles
import de.velwebsolutions.oasis.membership.ui.FileSharing
import de.velwebsolutions.oasis.membership.ui.InvoicePdf
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The share sheet and the print dialog of invoices and the data export. */
@RunWith(RobolectricTestRunner::class)
class FileSharingTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val files = MembershipFiles(context.cacheDir)

    private fun nextShare(): Intent = requireNotNull(shadowOf(context).nextStartedActivity) { "no share sheet" }

    @Test
    fun `a file goes to the share sheet with read access for the chosen app`() {
        val invoice = files.invoice("<html></html>".toByteArray(), "OAS-2")
        FileSharing.shareFile(context, invoice, FileSharing.mimeType(invoice), "Teilen")
        val chooser = nextShare()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals("text/html", send.type)
        val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)!!
        assertEquals("content", uri.scheme)
        assertEquals(uri, send.clipData?.getItemAt(0)?.uri)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun `the module's own file provider serves the shared files`() {
        val provider = de.velwebsolutions.oasis.membership.ui.MembershipFileProvider()
        assertTrue(provider is androidx.core.content.FileProvider)
    }

    @Test
    fun `the module keeps its files in the app's cache`() {
        val invoice = de.velwebsolutions.oasis.membership.di.MembershipModule.files(context).invoice(ByteArray(0), "OAS-1")
        assertEquals(File(context.cacheDir, "Rechnungen/Rechnung-OAS-1.html"), invoice)
    }

    @Test
    fun `a voucher code goes to the share sheet as text`() {
        FileSharing.shareText(context, "OASIS-1234", "Weitergeben")
        val send = nextShare().getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals("OASIS-1234", send.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `without an app to share with nothing happens`() {
        shadowOf(context).checkActivities(true)
        FileSharing.shareText(context, "OASIS-1234", "Weitergeben")
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun `invoices and exports are shared with their MIME type`() {
        assertEquals("application/pdf", FileSharing.mimeType(File("Rechnung.PDF")))
        assertEquals("application/zip", FileSharing.mimeType(files.export("PK".toByteArray())))
        assertEquals("application/octet-stream", FileSharing.mimeType(File("Rechnung")))
    }

    @Test
    fun `the invoice opens the system print dialog without failing`() {
        // Robolectric's print manager records no jobs; this checks that the call goes through on API 35.
        InvoicePdf.print(context, WebView(context), "Rechnung-OAS-2")
        assertTrue(context.getSystemService(android.print.PrintManager::class.java).printJobs.isEmpty())
    }
}
