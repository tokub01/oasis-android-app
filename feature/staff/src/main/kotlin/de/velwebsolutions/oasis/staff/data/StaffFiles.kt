package de.velwebsolutions.oasis.staff.data

import java.io.File

/**
 * The evidence archive for the police as `Beweise/oasis-fall-<id>-beweise.zip` below the cache (StaffFileProvider), and
 * the played files of reported chat messages below `Meldungen`.
 */
class StaffFiles(private val cacheDirectory: File) {
    /** Writes the evidence ZIP of a case for the share sheet. */
    fun evidenceArchive(caseId: Int, zip: ByteArray): File {
        val directory = File(cacheDirectory, "Beweise").apply { mkdirs() }
        return File(directory, "oasis-fall-$caseId-beweise.zip").apply { writeBytes(zip) }
    }

    /**
     * Writes a voice message or video of a reported chat message as `Meldungen/fall-<id>-anhang-<n>` for the platform
     * player, which plays files only.
     */
    fun reportedAttachment(caseId: Int, index: Int, bytes: ByteArray): File {
        val directory = File(cacheDirectory, REPORTED).apply { mkdirs() }
        return File(directory, "fall-$caseId-anhang-${index + 1}").apply { writeBytes(bytes) }
    }

    /** Deletes the played files of a case once its page is closed. */
    fun deleteReportedAttachments(caseId: Int) {
        File(cacheDirectory, REPORTED).listFiles { file -> file.name.startsWith("fall-$caseId-anhang-") }?.forEach { it.delete() }
    }

    private companion object {
        const val REPORTED = "Meldungen"
    }
}
