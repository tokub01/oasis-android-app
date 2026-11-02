package de.velwebsolutions.oasis.chat.core

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The chat look on this device (ui/chat-look.js `ChatLook`): own choice, default, photos, the effect switch, storage. */
@RunWith(RobolectricTestRunner::class)
class ChatLookTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun <T> run(block: suspend () -> T): T = runBlocking { block() }

    @Test
    fun aChatsOwnChoiceWinsThenTheDefaultThenStandard() = run {
        val memory = MemoryLook()
        val look = ChatLook(memory, memory, "1")
        assertEquals(LookChoice(ChatBackground.Standard, "default"), look.choice("7"))
        look.choose("7", ChatBackground.Duene, forAll = false)
        assertEquals(LookChoice(ChatBackground.Duene, "7"), look.choice("7"))
        look.choose("8", ChatBackground.Nachtwasser, forAll = true)
        assertEquals("the chat that set it follows the default", LookChoice(ChatBackground.Nachtwasser, "default"), look.choice("8"))
        assertEquals(LookChoice(ChatBackground.Duene, "7"), look.choice("7"))
        assertEquals(LookChoice(ChatBackground.Nachtwasser, "default"), look.choice("9"))
        // Another account on the device keeps its own.
        assertEquals(LookChoice(ChatBackground.Standard, "default"), ChatLook(memory, memory, "2").choice("7"))
        // Something unknown stored counts as nothing.
        memory.set("1:bg:7", "regen")
        assertEquals(LookChoice(ChatBackground.Nachtwasser, "default"), look.choice("7"))
        assertEquals(ChatBackground.Sandkoerner, ChatBackground.of("sandkoerner"))
        assertNull(ChatBackground.of(null))
    }

    @Test
    fun anOwnPhotoForOneChatOrAllAndItsRemoval() = run {
        val memory = MemoryLook()
        val look = ChatLook(memory, memory, "1")
        look.choosePhoto("7", byteArrayOf(1, 2), forAll = false)
        val chosen = look.choice("7")
        assertEquals(ChatBackground.Photo, chosen.background)
        assertArrayEquals(byteArrayOf(1, 2), chosen.photo?.jpeg)
        look.choosePhoto("8", byteArrayOf(3), forAll = true)
        assertArrayEquals(byteArrayOf(3), look.choice("9").photo?.jpeg)

        // A photo choice whose photo is gone shows „Standard“.
        memory.delete("1:photo:7")
        assertEquals(LookChoice(ChatBackground.Standard, "7"), look.choice("7"))

        look.removePhoto("9")
        assertEquals(LookChoice(ChatBackground.Standard, "default"), look.choice("9"))
        assertNull(memory.read("1:photo:default"))
        memory.deleteAll()
        memory.clear()
        assertNull(memory.get("1:bg:default"))
    }

    @Test
    fun effectsPlayUnlessSwitchedOff() = run {
        val memory = MemoryLook()
        val look = ChatLook(memory, memory, "1")
        assertTrue(look.effectsEnabled())
        look.setEffectsEnabled(false)
        assertFalse(look.effectsEnabled())
        look.setEffectsEnabled(true)
        assertTrue(look.effectsEnabled())
    }

    @Test
    fun settingsInTheDataStoreAndPhotosInPrivateFilesGoOnSignOut() = run {
        val file = File(context.filesDir, "look-test.preferences_pb").apply { delete() }
        val store = DataStoreLookStore(
            PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { file }
        )
        store.set("1:bg:7", "oase")
        store.set("1:effects", "false")
        assertEquals("oase", store.get("1:bg:7"))
        store.remove("1:bg:7")
        assertNull(store.get("1:bg:7"))
        store.clear()
        assertNull(store.get("1:effects"))

        val photos = FileLookPhotos(File(context.filesDir, "chat-look"))
        assertNull(photos.read("1:photo:7"))
        photos.write("1:photo:7", byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), photos.read("1:photo:7"))
        assertTrue("a file name without colons", File(context.filesDir, "chat-look/1_photo_7.jpg").exists())
        photos.delete("1:photo:7")
        assertNull(photos.read("1:photo:7"))
        photos.write("2:photo:default", byteArrayOf(1))
        photos.deleteAll()
        assertFalse(File(context.filesDir, "chat-look").exists())
    }
}
