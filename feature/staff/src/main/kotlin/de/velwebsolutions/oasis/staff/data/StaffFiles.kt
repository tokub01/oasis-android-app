package de.velwebsolutions.oasis.staff.data

import java.io.File

/** The evidence archive for the police as `Beweise/oasis-fall-<id>-beweise.zip` below the cache (StaffFileProvider). */
class StaffFiles(private val cacheDirectory: File) {
    fun evidenceArchive(caseId: Int, zip: ByteArray): File {
        val directory = File(cacheDirectory, "Beweise").apply { mkdirs() }
        return File(directory, "oasis-fall-$caseId-beweise.zip").apply { writeBytes(zip) }
    }
}
