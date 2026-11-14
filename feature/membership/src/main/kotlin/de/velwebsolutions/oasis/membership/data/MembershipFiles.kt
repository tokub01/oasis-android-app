package de.velwebsolutions.oasis.membership.data

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Files handed to the invoice view and the share sheet: invoices as `Rechnungen/Rechnung-OAS-<id>.html`, the data
 * export as `Export/oasis-daten.zip`, below the app's cache directory (shared through MembershipFileProvider). They hold
 * personal data (name, address, payments; the whole account), so they are kept only while needed: the screen that wrote
 * one deletes it when it closes ([delete]), and [clear] removes all of them when the membership's files are first used
 * in a process (leftovers of one that ended) and when the member signs out, also after deleting the account.
 */
class MembershipFiles(private val cacheDirectory: File) {
    /** The invoice HTML of the API, kept as a file for the invoice view, printing and "Teilen". */
    fun invoice(html: ByteArray, number: String): File = write(File(cacheDirectory, INVOICES), "Rechnung-$number.html", html)

    /** The data export ZIP; it can be large (Posts with their images), so it is written off the main thread. */
    suspend fun export(zip: ByteArray): File = withContext(Dispatchers.IO) {
        write(File(cacheDirectory, EXPORT), "oasis-daten.zip", zip)
    }

    /** Removes a file its screen no longer shows. */
    fun delete(file: File) {
        file.delete()
    }

    /** Removes every invoice and export. */
    fun clear() {
        File(cacheDirectory, INVOICES).deleteRecursively()
        File(cacheDirectory, EXPORT).deleteRecursively()
    }

    private fun write(directory: File, name: String, bytes: ByteArray): File {
        directory.mkdirs()
        return File(directory, name).apply { writeBytes(bytes) }
    }

    private companion object {
        const val INVOICES = "Rechnungen"
        const val EXPORT = "Export"
    }
}
