package de.velwebsolutions.oasis.staff.data

import java.io.File

/**
 * The evidence archive for the police as `Beweise/oasis-fall-<id>-beweise.zip` below the cache (StaffFileProvider), and
 * the played files of reported chat messages below `Meldungen`. Both are decrypted copies of what the server keeps
 * encrypted and deletes on schedule, so they live only as long as the case page that wrote them: it deletes them when it
 * closes, the archive also once the evidence is deleted for good. [clear] removes all of them when the staff files are
 * first used in a process (leftovers of one that ended while a case was open) and from then on whenever the member
 * signs out.
 */
class StaffFiles(private val cacheDirectory: File) {
    /** Writes the evidence ZIP of a case for the share sheet. */
    fun evidenceArchive(caseId: Int, zip: ByteArray): File {
        val directory = File(cacheDirectory, EVIDENCE).apply { mkdirs() }
        return File(directory, archiveName(caseId)).apply { writeBytes(zip) }
    }

    /** Deletes the evidence ZIP of a case: when its page closes, and once the evidence is deleted for good. */
    fun deleteEvidenceArchive(caseId: Int) {
        File(File(cacheDirectory, EVIDENCE), archiveName(caseId)).delete()
    }

    /**
     * Writes a voice message, video or file of a reported chat message as `Meldungen/fall-<id>-anhang-<n>` (a file
     * with its [name] appended, slashes replaced) for the platform player or the app that opens it. A file name has at
     * most 255 bytes, which 120 characters of Cyrillic or emoji exceed: a longer name is cut before its extension.
     */
    fun reportedAttachment(caseId: Int, index: Int, bytes: ByteArray, name: String? = null): File {
        val directory = File(cacheDirectory, REPORTED).apply { mkdirs() }
        val suffix = name?.let { "-" + it.replace('/', '_').replace('\\', '_') }.orEmpty()
        return File(directory, fitted("fall-$caseId-anhang-${index + 1}", suffix)).apply { writeBytes(bytes) }
    }

    /** Deletes the played files of a case once its page is closed. */
    fun deleteReportedAttachments(caseId: Int) {
        File(cacheDirectory, REPORTED).listFiles { file -> file.name.startsWith("fall-$caseId-anhang-") }?.forEach { it.delete() }
    }

    /** Deletes every evidence archive and every played file. */
    fun clear() {
        File(cacheDirectory, EVIDENCE).deleteRecursively()
        File(cacheDirectory, REPORTED).deleteRecursively()
    }

    private companion object {
        const val EVIDENCE = "Beweise"
        const val REPORTED = "Meldungen"

        /** The longest file name ext4 and f2fs take, in UTF-8 bytes. */
        const val MAX_NAME_BYTES = 255

        /** A longer part after the last dot is no extension ("Bericht vom 1.10. über …"). */
        const val MAX_EXTENSION = 16

        fun archiveName(caseId: Int) = "oasis-fall-$caseId-beweise.zip"

        /** [prefix] and [suffix] as a name the file system takes: whole characters go from the end, before the extension. */
        fun fitted(prefix: String, suffix: String): String {
            val dot = suffix.lastIndexOf('.')
            val extension = if (dot >= 0 && suffix.length - dot <= MAX_EXTENSION) suffix.substring(dot) else ""
            var stem = suffix.dropLast(extension.length)
            while ((prefix + stem + extension).toByteArray().size > MAX_NAME_BYTES) {
                stem = stem.substring(0, Character.offsetByCodePoints(stem, stem.length, -1))
            }
            return prefix + stem + extension
        }
    }
}
