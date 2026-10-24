package de.velwebsolutions.oasis.membership.data

import java.io.File

/**
 * Files handed to the invoice view and the share sheet: invoices as `Rechnungen/Rechnung-OAS-<id>.html`, the data
 * export as `oasis-daten.zip`, below the app's cache directory (shared through MembershipFileProvider).
 */
class MembershipFiles(private val cacheDirectory: File) {
    /** The invoice HTML of the API, kept as a file for the invoice view, printing and "Teilen". */
    fun invoice(html: ByteArray, number: String): File = write(File(cacheDirectory, "Rechnungen"), "Rechnung-$number.html", html)

    /** The data export ZIP. */
    fun export(zip: ByteArray): File = write(File(cacheDirectory, "Export"), "oasis-daten.zip", zip)

    private fun write(directory: File, name: String, bytes: ByteArray): File {
        directory.mkdirs()
        return File(directory, name).apply { writeBytes(bytes) }
    }
}
