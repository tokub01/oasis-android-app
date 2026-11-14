package de.velwebsolutions.oasis.chat.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.flow.first

/**
 * The personal chat backgrounds (docs/mildcrowd-chat-client.md, "Backgrounds and effects"; ui/chat-look.js): stable keys,
 * German names, and whether the background is dark (own bubbles get an outline there). Only for oneself on this device.
 */
enum class ChatBackground(val key: String, val label: String, val dark: Boolean = false) {
    Standard("standard", "Standard"),
    Duene("duene", "Nebel"),
    Oase("oase", "Morgen"),
    Abendsonne("abendsonne", "Pfirsich"),
    Nachtwasser("nachtwasser", "Nacht", dark = true),
    Sandkoerner("sandkoerner", "Punkte"),
    Photo("photo", "Eigenes Foto");

    companion object {
        /** The background of a stored key, or null for anything else. */
        fun of(key: String?): ChatBackground? = entries.firstOrNull { it.key == key }
    }
}

/** An own background photo as stored: a JPEG without camera metadata, at most 2048 px on the longest edge. */
class LookPhoto(val jpeg: ByteArray)

/** What a chat shows: the background, where the choice comes from (the chat id or "default") and the own photo. */
data class LookChoice(val background: ChatBackground, val scope: String, val photo: LookPhoto? = null)

/** The settings of the chat look on this device (the web's IndexedDB `oasis-chat-look`): small texts by key. */
interface LookStore {
    suspend fun get(key: String): String?

    suspend fun set(key: String, value: String)

    suspend fun remove(key: String)

    /** Everything, for every account (sign-out). */
    suspend fun clear()
}

/** The own background photos (JPEG) on this device, by key. */
interface LookPhotos {
    fun read(key: String): ByteArray?

    fun write(key: String, jpeg: ByteArray)

    fun delete(key: String)

    /** Every photo of every account (sign-out). */
    fun deleteAll()
}

/** [LookStore] and [LookPhotos] in memory (previews, tests). */
class MemoryLook : LookStore, LookPhotos {
    private val values = mutableMapOf<String, String>()
    private val photos = mutableMapOf<String, ByteArray>()

    override suspend fun get(key: String): String? = values[key]

    override suspend fun set(key: String, value: String) {
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }

    override suspend fun clear() {
        values.clear()
    }

    override fun read(key: String): ByteArray? = photos[key]

    override fun write(key: String, jpeg: ByteArray) {
        photos[key] = jpeg
    }

    override fun delete(key: String) {
        photos.remove(key)
    }

    override fun deleteAll() {
        photos.clear()
    }
}

/** [LookStore] in the chat's DataStore, every key under `look:`. */
class DataStoreLookStore(private val dataStore: DataStore<Preferences>) : LookStore {
    private fun key(name: String) = stringPreferencesKey("look:$name")

    override suspend fun get(key: String): String? = dataStore.data.first()[key(key)]

    override suspend fun set(key: String, value: String) {
        dataStore.edit { it[key(key)] = value }
    }

    override suspend fun remove(key: String) {
        dataStore.edit { it.remove(key(key)) }
    }

    override suspend fun clear() {
        dataStore.edit { preferences -> preferences.asMap().keys.filter { it.name.startsWith("look:") }.forEach { preferences.remove(it) } }
    }
}

/**
 * [LookPhotos] as files in the app's private storage (`files/chat-look`), never in a backup (the backup rules exclude all
 * of the app's storage) and deleted when the member signs out.
 */
class FileLookPhotos(private val folder: File) : LookPhotos {
    private fun file(key: String) = File(folder, key.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jpg")

    override fun read(key: String): ByteArray? = file(key).takeIf { it.exists() }?.readBytes()

    override fun write(key: String, jpeg: ByteArray) {
        folder.mkdirs()
        file(key).writeBytes(jpeg)
    }

    override fun delete(key: String) {
        file(key).delete()
    }

    override fun deleteAll() {
        folder.deleteRecursively()
    }
}

/**
 * The background choices and the switch „Effekte abspielen“ of one account on this device (ui/chat-look.js `ChatLook`):
 * a chat's own choice wins, otherwise the default („Für alle Chats verwenden“), otherwise „Standard“. Every key carries
 * the account, so two accounts on one device keep their own. Nothing of it is sent anywhere.
 */
class ChatLook(private val store: LookStore, private val photos: LookPhotos, userId: String) {
    private val prefix = "$userId:"

    private fun key(name: String) = prefix + name

    /** What a chat shows; a photo choice whose photo is gone shows „Standard“. */
    suspend fun choice(conversationId: String): LookChoice {
        val own = ChatBackground.of(store.get(key("bg:$conversationId")))
        val scope = if (own != null) conversationId else DEFAULT
        val background = own ?: ChatBackground.of(store.get(key("bg:$DEFAULT"))) ?: ChatBackground.Standard
        if (background != ChatBackground.Photo) return LookChoice(background, scope)
        val photo = photos.read(key("photo:$scope")) ?: return LookChoice(ChatBackground.Standard, scope)
        return LookChoice(background, scope, LookPhoto(photo))
    }

    /** Choose for one chat, or – [forAll] – as the default, which the chat then follows. */
    suspend fun choose(conversationId: String, background: ChatBackground, forAll: Boolean) {
        if (forAll) {
            store.set(key("bg:$DEFAULT"), background.key)
            store.remove(key("bg:$conversationId"))
        } else {
            store.set(key("bg:$conversationId"), background.key)
        }
    }

    /** Keep a prepared photo (JPEG) for the chat or as the default, and choose it. */
    suspend fun choosePhoto(conversationId: String, jpeg: ByteArray, forAll: Boolean) {
        photos.write(key("photo:${if (forAll) DEFAULT else conversationId}"), jpeg)
        choose(conversationId, ChatBackground.Photo, forAll)
    }

    /** „Foto entfernen“: the photo the chat shows goes; that chat (or the default) returns to „Standard“. */
    suspend fun removePhoto(conversationId: String) {
        val scope = choice(conversationId).scope
        photos.delete(key("photo:$scope"))
        store.set(key("bg:$scope"), ChatBackground.Standard.key)
    }

    /** „Effekte abspielen“ (default on). */
    suspend fun effectsEnabled(): Boolean = store.get(key("effects")) != "false"

    suspend fun setEffectsEnabled(enabled: Boolean) {
        store.set(key("effects"), enabled.toString())
    }

    private companion object {
        const val DEFAULT = "default"
    }
}
