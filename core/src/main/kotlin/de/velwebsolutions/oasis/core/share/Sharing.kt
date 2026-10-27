package de.velwebsolutions.oasis.core.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One file another app shared into Oasis, copied into the app cache right away (the share's read permission is brief). */
class SharedFile(val file: File, val name: String, val mime: String)

/** What another app shared: files and text, for the chat the member picks under "Teilen mit …". */
class Share(val files: List<SharedFile>, val text: String?)

/**
 * The share waiting for "Teilen mit …" – in memory only, also while the member signs in or unlocks the chat; the copies
 * live in the cache until a chat takes the share or it is discarded (docs/oasis-chat.md, "Sharing into Oasis").
 */
object PendingShare {
    private val state = MutableStateFlow<Share?>(null)

    /** The share not yet handed to a chat. */
    val share: StateFlow<Share?> = state.asStateFlow()

    private var target: String? = null

    /** A new share replaces an older one that was never used. */
    fun offer(share: Share) {
        discard()
        state.value = share
    }

    /** The member picked this chat: it takes the share when it opens. */
    fun target(conversationId: String) {
        target = conversationId
    }

    /** The share, once, for the chat it was meant for; the caller deletes the copies after reading them ([discard]). */
    fun take(conversationId: String): Share? {
        if (target != conversationId) return null
        target = null
        return state.value.also { state.value = null }
    }

    /** "Abbrechen", or a newer share: the copies are deleted. */
    fun discard() {
        state.value?.let(::delete)
        state.value = null
        target = null
    }

    /** Deletes the cache copies of a share. */
    fun delete(share: Share) = share.files.forEach { it.file.delete() }
}

/**
 * Reads ACTION_SEND / ACTION_SEND_MULTIPLE: copies every shared stream into the cache (names and types from the
 * sending app) and keeps the shared text. Of a file larger than [maxBytes] only a little more than that is copied, so the
 * chat can tell it is too large without holding all of it.
 */
object ShareInbox {
    /** The largest file the chat takes (50 MB of ciphertext minus the tag). */
    const val MAX_BYTES = 50L * 1024 * 1024 - 16

    private const val UNKNOWN_TYPE = "application/octet-stream"

    /** The share of an intent, or null when it carries nothing Oasis can use. */
    fun read(context: Context, intent: Intent, maxBytes: Long = MAX_BYTES): Share? {
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return null
        }
        val folder = File(context.cacheDir, "shared-in").apply {
            deleteRecursively()
            mkdirs()
        }
        val files = uris.mapIndexedNotNull { index, uri -> copy(context, uri, File(folder, index.toString()), maxBytes) }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }
        return if (files.isEmpty() && text == null) null else Share(files, text)
    }

    private fun copy(context: Context, uri: Uri, folder: File, maxBytes: Long): SharedFile? {
        val resolver = context.contentResolver
        // openInputStream only returns null when the provider crashed: that, like a missing file, is skipped.
        val input = runCatching { resolver.openInputStream(uri)!! }.getOrNull() ?: return null
        val name = displayName(context, uri) ?: uri.lastPathSegment ?: "Datei"
        val file = File(folder.apply { mkdirs() }, name.replace('/', '_'))
        // A stream that breaks off while reading is skipped like a missing one.
        val copied = runCatching {
            input.use { stream ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    var count = stream.read(buffer)
                    while (count >= 0 && total <= maxBytes) {
                        output.write(buffer, 0, count)
                        total += count
                        count = stream.read(buffer)
                    }
                }
            }
        }.isSuccess
        if (!copied) {
            file.delete()
            return null
        }
        return SharedFile(file, name, resolver.getType(uri) ?: UNKNOWN_TYPE)
    }

    /** The name the providing app gives a content URI (`OpenableColumns.DISPLAY_NAME`), or null. */
    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
}
