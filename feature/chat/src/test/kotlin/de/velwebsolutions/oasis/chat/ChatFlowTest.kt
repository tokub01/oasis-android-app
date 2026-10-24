package de.velwebsolutions.oasis.chat

import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.core.ChatKeyring
import de.velwebsolutions.oasis.chat.core.ChatMessageItem
import de.velwebsolutions.oasis.chat.core.ChatVault
import de.velwebsolutions.oasis.chat.core.MemoryChatPreferences
import de.velwebsolutions.oasis.chat.core.MemoryDeviceSecretStore
import de.velwebsolutions.oasis.chat.ui.ChatAccessViewModel
import de.velwebsolutions.oasis.chat.ui.ChatPhase
import de.velwebsolutions.oasis.chat.ui.ChatSettingsViewModel
import de.velwebsolutions.oasis.chat.ui.ConversationDetailsViewModel
import de.velwebsolutions.oasis.chat.ui.ConversationListViewModel
import de.velwebsolutions.oasis.chat.ui.ConversationViewModel
import de.velwebsolutions.oasis.chat.ui.NewConversationViewModel
import de.velwebsolutions.oasis.chat.ui.ReportModel
import de.velwebsolutions.oasis.core.realtime.NoRealtime
import de.velwebsolutions.oasis.core.realtime.RealtimeEvent
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Two app clients against the in-memory server: the full protocol of docs/oasis-chat.md through the view models. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatFlowTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val support = ChatTestSupport()
    private val server get() = support.server
    private val passphrase = "Palme Regen Kompass Tee"

    private suspend fun setUp(context: ChatContext, secret: String = passphrase) {
        val access = ChatAccessViewModel(context)
        access.load()
        assertEquals(ChatPhase.Setup, access.state.value.phase)
        access.setPassphrase(secret)
        access.setConfirmation(secret)
        access.setUp()
        assertEquals(ChatPhase.Open, access.state.value.phase)
        assertTrue("typed passphrases are cleared", access.state.value.passphrase.isEmpty())
    }

    private fun conversation(context: ChatContext, id: String) = ConversationViewModel(id, context, NoRealtime()).apply {
        pollInterval = Duration.INFINITE
        sweepInterval = Duration.INFINITE
    }

    private suspend fun startChat(owner: ChatContext, username: String = "jonas"): String {
        val new = NewConversationViewModel(null, owner)
        new.setUsername(username)
        new.addFromInput()
        return checkNotNull(new.create())
    }

    @Test
    fun setupRejectsShortOrMismatchedPassphrases() = runTest {
        val access = ChatAccessViewModel(support.context(1))
        access.load()
        access.setPassphrase("zu kurz")
        access.setConfirmation("zu kurz")
        access.setUp()
        assertEquals("Die Passphrase braucht mindestens 12 Zeichen.", access.state.value.formError)
        access.setPassphrase("Palme Regen Kompass Tee")
        access.setConfirmation("Palme Regen Kompass Ted")
        access.setUp()
        assertEquals("Die beiden Eingaben stimmen nicht überein.", access.state.value.formError)
        assertNull(server.identities[1])
    }

    @Test
    fun membersExchangeEncryptedMessages() = runTest {
        val mira = support.context(1)
        val jonas = support.context(2)
        setUp(mira)
        setUp(jonas)
        // Only ciphertext and production KDF costs reach the server.
        val locked = server.identities.getValue(1).getValue("locked_identity").jsonObject
        assertEquals(268_435_456, locked.getValue("kdf").jsonObject.getValue("memlimit").jsonPrimitive.int)

        val new = NewConversationViewModel(null, mira)
        new.setUsername("@Jonas")
        new.addFromInput()
        assertEquals(listOf("jonas"), new.state.value.selected.map { it.username })
        val id = checkNotNull(new.create())

        val miraView = conversation(mira, id)
        miraView.open()
        assertTrue("the owner distributed the first key", miraView.state.value.canSend)
        assertEquals(2, server.grants[id.toInt()]?.size)
        miraView.setDraft("Hallo Jonas 🌴")
        miraView.send()
        assertTrue(miraView.state.value.draft.isEmpty())
        assertEquals(listOf("Hallo Jonas 🌴"), miraView.state.value.timeline.items.map { it.plaintext })
        val stored = server.messages.getValue(id.toInt()).first().second
        assertFalse("the server only sees ciphertext", stored.toString().contains("Hallo"))

        val jonasView = conversation(jonas, id)
        jonasView.open()
        assertEquals("Nachrichtenanfrage von Mira", jonasView.state.value.requestTitle)
        assertEquals(listOf("Hallo Jonas 🌴"), jonasView.state.value.timeline.items.map { it.plaintext })
        assertEquals(ChatMessageItem.State.Readable, jonasView.state.value.timeline.items.first().state)

        jonasView.setDraft("Hallo Mira!")
        jonasView.send()
        assertNull("replying accepts the request", jonasView.state.value.requestTitle)

        // Mira receives the envelope live (MessageSent event with the envelope as payload).
        val envelope = server.messages.getValue(id.toInt()).last().second
        miraView.handle(RealtimeEvent("MessageSent", envelope))
        miraView.handle(RealtimeEvent("MessageSent", envelope))
        assertEquals(
            "deduplicated by signature",
            listOf("Hallo Jonas 🌴", "Hallo Mira!"),
            miraView.state.value.timeline.items.mapNotNull {
                it.plaintext
            }
        )
        assertEquals("Neue Nachricht von Jonas.", miraView.state.value.spokenNotice)

        // Safety numbers agree on both sides.
        val miraDetails = ConversationDetailsViewModel(id, mira)
        miraDetails.load()
        val jonasDetails = ConversationDetailsViewModel(id, jonas)
        jonasDetails.load()
        val fromMira = miraDetails.state.value.members.first { !it.isMe }.safetyNumber
        assertNotNull(fromMira)
        assertEquals(fromMira, jonasDetails.state.value.members.first { !it.isMe }.safetyNumber)
        assertEquals(12, fromMira!!.split(" ").size)

        // Muting is per member and uses the web's words.
        assertEquals(
            "Du bekommst Mitteilungen zu neuen Nachrichten, so wie unter Hinweise einstellen festgelegt.",
            miraDetails.state.value.muteState
        )
        miraDetails.mute()
        assertTrue(miraDetails.state.value.isMuted)
        assertEquals("Stumm bis Do., 22. Oktober um 22:00 Uhr.", miraDetails.state.value.muteState)
        assertEquals("Der Chat ist stummgeschaltet.", miraDetails.state.value.status)
        assertEquals("the running mute is preselected", "8h", miraDetails.state.value.muteDuration)
        miraDetails.setMuteDuration("always")
        miraDetails.mute()
        assertEquals("Dauerhaft stumm.", miraDetails.state.value.muteState)
        miraDetails.load()
        assertEquals("always", miraDetails.state.value.muteDuration)
        jonasDetails.load()
        assertFalse("only Mira muted the chat", jonasDetails.state.value.isMuted)
        val list = ConversationListViewModel(mira, NoRealtime())
        list.load()
        assertEquals(true, list.state.value.conversations.first().muted)
        miraDetails.unmute()
        assertFalse(miraDetails.state.value.isMuted)
        assertEquals("Du bekommst wieder Mitteilungen zu diesem Chat.", miraDetails.state.value.status)
        miraView.close()
        jonasView.close()
    }

    @Test
    fun tamperedEnvelopesAreShownAsUnverifiable() = runTest {
        val mira = support.context(1)
        val jonas = support.context(2)
        setUp(mira)
        setUp(jonas)
        val id = startChat(mira)
        val miraView = conversation(mira, id)
        miraView.open()
        miraView.setDraft("Echt")
        miraView.send()
        val messages = server.messages.getValue(id.toInt())
        val (messageId, envelope) = messages[0]
        messages[0] = messageId to JsonObject(envelope + ("sender_id" to kotlinx.serialization.json.JsonPrimitive("2")))

        val jonasView = conversation(jonas, id)
        jonasView.open()
        assertEquals(ChatMessageItem.State.Unverifiable, jonasView.state.value.timeline.items.first().state)
        assertNull(jonasView.state.value.timeline.items.first().plaintext)
        miraView.close()
        jonasView.close()
    }

    @Test
    fun lockingWipesKeysAndUnlockNeedsTheRightPassphrase() = runTest {
        val mira = support.context(1)
        setUp(mira)
        val identity = checkNotNull(mira.vault.identity)
        mira.vault.lock()
        assertTrue(identity.isWiped)
        assertEquals("Nachrichten gesperrt. Deine Schlüssel sind aus dem Speicher gelöscht.", mira.vault.announcement.value)

        val access = ChatAccessViewModel(mira)
        access.load()
        assertEquals(ChatPhase.Unlock, access.state.value.phase)
        access.setPassphrase("falsch falsch falsch")
        access.unlock()
        assertEquals("Die Passphrase stimmt nicht.", access.state.value.formError)
        access.setPassphrase(passphrase)
        access.unlock()
        assertEquals(ChatPhase.Open, access.state.value.phase)
    }

    @Test
    fun idleTimeLocksAndInteractionRestartsTheTimer() = runTest {
        // A vault whose timer runs on virtual time.
        val scheduler = TestCoroutineScheduler()
        val vault = ChatVault(CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler)), idleLock = 300.milliseconds)
        val mira = support.context(1, vault = vault)
        setUp(mira)
        scheduler.advanceTimeBy(200)
        mira.vault.touch()
        scheduler.advanceTimeBy(200)
        scheduler.runCurrent()
        assertTrue("the tap restarted the timer", mira.vault.unlocked)
        scheduler.advanceTimeBy(300)
        scheduler.runCurrent()
        assertFalse(mira.vault.unlocked)
        assertEquals("Nachrichten gesperrt. Deine Schlüssel sind aus dem Speicher gelöscht.", mira.vault.announcement.value)
    }

    @Test
    fun textTypedWhileSendingStaysInTheComposer() {
        assertEquals("", ConversationViewModel.remainder("Hallo", "Hallo"))
        assertEquals("wie geht's?", ConversationViewModel.remainder("Hallo\nwie geht's?", "Hallo"))
        assertEquals("a changed draft is kept", "Hallöchen", ConversationViewModel.remainder("Hallöchen", "Hallo"))
    }

    @Test
    fun biometricSetupWithoutPassphraseAndUnlock() = runTest {
        val secrets = MemoryDeviceSecretStore(available = true)
        val mira = support.context(1, secrets = secrets)
        val access = ChatAccessViewModel(mira)
        access.load()
        access.setUpWithDevice()
        assertEquals(ChatPhase.Open, access.state.value.phase)
        assertEquals(JsonNull, server.identities.getValue(1)["locked_identity"])
        val row = server.deviceUnlocks.first().second
        assertTrue(row.getValue("credential_id").jsonPrimitive.content.startsWith("app-"))
        assertEquals(1, row.getValue("locked_identity").jsonObject.getValue("kdf").jsonObject.getValue("opslimit").jsonPrimitive.int)

        mira.vault.lock()
        val again = ChatAccessViewModel(mira)
        again.load()
        assertEquals(ChatPhase.Unlock, again.state.value.phase)
        assertFalse(again.state.value.hasPassphrase)
        assertTrue(again.state.value.canUnlockWithDevice)
        secrets.denies = true
        again.unlockWithDevice()
        assertEquals("Das hat nicht geklappt. Versuch es noch einmal.", again.state.value.deviceUnlockError)
        secrets.denies = false
        again.unlockWithDevice()
        assertEquals(ChatPhase.Open, again.state.value.phase)
    }

    @Test
    fun passphraseUnlockOffersBiometricsAndTheNextUnlockStartsItByItself() = runTest {
        val secrets = MemoryDeviceSecretStore(available = true)
        val mira = support.context(1, secrets = secrets)
        setUp(mira)
        mira.vault.lock()

        val access = ChatAccessViewModel(mira)
        access.load()
        assertFalse(access.state.value.canUnlockWithDevice)
        access.autoUnlockWithDevice()
        assertEquals("no biometric copy yet: nothing starts by itself", ChatPhase.Unlock, access.state.value.phase)
        access.setPassphrase(passphrase)
        access.unlock()
        assertEquals(ChatPhase.Open, access.state.value.phase)
        assertTrue(access.state.value.offersDeviceUnlock)
        access.acceptDeviceOffer()
        assertFalse(access.state.value.offersDeviceUnlock)
        assertEquals(1, server.deviceUnlocks.size)

        mira.vault.lock()
        val again = ChatAccessViewModel(mira)
        again.load()
        assertTrue(again.state.value.canUnlockWithDevice)
        secrets.denies = true
        again.autoUnlockWithDevice()
        assertEquals(ChatPhase.Unlock, again.state.value.phase)
        assertNull("a cancelled automatic prompt stays quiet", again.state.value.deviceUnlockError)
        secrets.denies = false
        again.autoUnlockWithDevice()
        assertEquals("only one automatic try per locked episode", ChatPhase.Unlock, again.state.value.phase)
        again.unlockWithDevice()
        assertEquals(ChatPhase.Open, again.state.value.phase)
        assertFalse(again.state.value.offersDeviceUnlock)
    }

    @Test
    fun declinedBiometricOfferIsNotRepeated() = runTest {
        val preferences = MemoryChatPreferences()
        val jonas = support.context(2, secrets = MemoryDeviceSecretStore(available = true), preferences = preferences)
        setUp(jonas)
        jonas.vault.lock()
        val access = ChatAccessViewModel(jonas)
        access.load()
        access.setPassphrase(passphrase)
        access.unlock()
        assertTrue(access.state.value.offersDeviceUnlock)
        access.declineDeviceOffer()
        assertTrue(preferences.biometricOfferDeclined("2"))
        jonas.vault.lock()
        access.load()
        assertEquals(ChatPhase.Unlock, access.state.value.phase)
        access.setPassphrase(passphrase)
        access.unlock()
        assertEquals(ChatPhase.Open, access.state.value.phase)
        assertFalse(access.state.value.offersDeviceUnlock)
    }

    @Test
    fun passphraseBackupCanBeAddedAndSwitchedOff() = runTest {
        val mira = support.context(1, secrets = MemoryDeviceSecretStore(available = true))
        setUp(mira)
        val settings = ChatSettingsViewModel(mira)
        settings.load()
        assertEquals(listOf("members", "contacts", "following", "nobody"), settings.state.value.settings?.choices?.map { it.first })
        settings.setFriendRequests(false)
        assertEquals("a failed save puts the switch back", true, settings.state.value.settings?.friendRequests)
        assertNotNull(settings.state.value.error)
        assertFalse(settings.state.value.savingSetting)
        assertFalse("no device yet", settings.state.value.canRemovePassphrase)
        settings.addThisDevice()
        assertTrue(settings.state.value.thisDeviceIsSetUp)
        assertTrue(settings.state.value.canRemovePassphrase)
        settings.removePassphrase()
        assertFalse(settings.state.value.hasPassphrase)
        assertEquals("Reserve-Passphrase festlegen (optional)", settings.state.value.backupTitle)
        settings.setPassphrase("Neue Reserve Passphrase")
        settings.setConfirmation("Neue Reserve Passphrase")
        settings.saveBackup()
        assertTrue(settings.state.value.hasPassphrase)
        assertNull(settings.state.value.backupError)
    }

    @Test
    fun anotherDeviceOpensTheChatAfterApproval() = runTest {
        val phone = support.context(1)
        setUp(phone)
        val laptop = support.context(1)
        val asking = ChatAccessViewModel(laptop).apply { pollInterval = Duration.INFINITE }
        asking.load()
        assertEquals(ChatPhase.Unlock, asking.state.value.phase)
        asking.requestLink()
        val check = checkNotNull(asking.state.value.linkCheck)

        val list = ConversationListViewModel(phone, NoRealtime())
        list.load()
        assertEquals("both devices show the same check number", listOf(check), list.state.value.linkRequests.map { it.check })
        list.approve(list.state.value.linkRequests[0].request)
        assertTrue(asking.pollOnce())
        assertEquals(ChatPhase.Open, asking.state.value.phase)
        assertArrayEquals(phone.vault.identity?.signingPublicKey, laptop.vault.identity?.signingPublicKey)
    }

    @Test
    fun reportsSendTheEnvelopeAndPlaintext() = runTest {
        val mira = support.context(1)
        val jonas = support.context(2)
        setUp(mira)
        setUp(jonas)
        val id = startChat(mira)
        val miraView = conversation(mira, id)
        miraView.open()
        miraView.setDraft("Du Vollidiot")
        miraView.send()

        val jonasView = conversation(jonas, id)
        jonasView.open()
        val flagged = checkNotNull(jonasView.state.value.flagged)
        assertEquals("Möglicherweise Belästigung erkannt. Mira blockieren und Chat löschen?", flagged.title)
        val report = ReportModel(jonas, id, flagged.item, "Mira")
        report.send()
        assertEquals("Wähl bitte aus, was nicht stimmt.", report.state.value.error)
        report.setCategory(ReportModel.categories[1])
        report.setComment("Bitte prüfen")
        report.send()
        assertTrue(report.state.value.sent)
        assertNull(report.state.value.quote)
        val sent = server.reports.first()
        assertEquals("Du Vollidiot", sent.getValue("plaintext").jsonPrimitive.content)
        assertEquals("Belästigung oder Beleidigung\n\nBitte prüfen", sent.getValue("reason").jsonPrimitive.content)
        assertEquals(flagged.item.signature, sent.getValue("envelope").jsonObject.getValue("signature").jsonPrimitive.content)
        miraView.close()
        jonasView.close()
    }

    @Test
    fun sensitiveDraftsNeedASecondPressAndReadOnlyAccountsCannotSend() = runTest {
        val mira = support.context(1)
        val jonas = support.context(2)
        setUp(mira)
        setUp(jonas)
        val id = startChat(mira)
        val view = conversation(mira, id)
        view.open()
        view.setDraft("mein Passwort ist sonne123")
        view.send()
        assertTrue(view.state.value.hint.orEmpty().startsWith("Du bist dabei, ein Passwort oder einen Code zu verschicken."))
        assertNull(server.messages[id.toInt()])
        view.send()
        assertEquals(1, server.messages.getValue(id.toInt()).size)
        view.setDraft("Digga, was geht")
        assertEquals("Kurzer Hinweis: „Digga“ wirkt schnell herablassend. Bitte achte auf einen freundlichen Ton.", view.state.value.hint)

        val readOnly = support.context(2, canParticipate = false)
        assertTrue(ChatKeyring(readOnly).unlock(checkNotNull(readOnly.repository.identity()), passphrase))
        val jonasView = conversation(readOnly, id)
        jonasView.open()
        assertFalse(jonasView.state.value.canSend)
        assertTrue(
            jonasView.state.value.notices.contains(
                "Dein Konto ist gerade nur zum Lesen. Mit einer Mitgliedschaft kannst du wieder schreiben."
            )
        )
        view.close()
        jonasView.close()
    }

    @Test
    fun newChatsNeedAChatReadyPerson() = runTest {
        val mira = support.context(1)
        setUp(mira)
        val new = NewConversationViewModel(null, mira)
        new.setUsername("aylin")
        new.addFromInput()
        assertEquals("@aylin gibt es nicht oder hat den Chat noch nicht eingerichtet.", new.state.value.error)
        assertNull(new.state.value.status)
        new.setUsername("mira")
        new.addFromInput()
        assertEquals("Du bist automatisch dabei.", new.state.value.error)
        assertNull(new.create())
        assertEquals("Füge mindestens eine Person hinzu.", new.state.value.error)
    }

    @Test
    fun aPreselectedUsernameIsAddedOnce() = runTest {
        val mira = support.context(1)
        setUp(mira)
        setUp(support.context(2))
        val new = NewConversationViewModel("jonas", mira)
        new.applyPreselection()
        new.applyPreselection()
        assertEquals(listOf("jonas"), new.state.value.selected.map { it.username })
        assertEquals("@jonas hinzugefügt.", new.state.value.status)
    }

    @Test
    fun leavingAndDecliningAnnounceTheResult() = runTest {
        val mira = support.context(1)
        val jonas = support.context(2)
        setUp(mira)
        setUp(jonas)
        val id = startChat(mira)
        val miraView = conversation(mira, id)
        miraView.open()
        miraView.setDraft("Hallo")
        miraView.send()

        val jonasView = conversation(jonas, id)
        jonasView.open()
        assertTrue(jonasView.declineRequest(block = true))
        assertEquals("Blockiert und gelöscht.", jonas.vault.announcement.value)
        assertEquals(listOf(2 to 1), server.blocks)
        assertFalse(2 in server.conversations.getValue(id.toInt()).members)
        miraView.close()
        jonasView.close()
    }
}
