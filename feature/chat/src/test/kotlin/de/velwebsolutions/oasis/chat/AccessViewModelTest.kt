package de.velwebsolutions.oasis.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import de.velwebsolutions.oasis.chat.api.SafetyLists
import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.core.MemoryDeviceSecretStore
import de.velwebsolutions.oasis.chat.ui.ChatAccessViewModel
import de.velwebsolutions.oasis.chat.ui.ChatPhase
import de.velwebsolutions.oasis.chatcrypto.approveLink
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Clears a view model the way leaving its screen does (`onCleared`). */
fun clear(model: ViewModel) {
    val store = ViewModelStore()
    ViewModelProvider.create(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: androidx.lifecycle.viewmodel.CreationExtras): T = model as T
        }
    )[model::class]
    store.clear()
}

/** The gate's view model in every branch: loading, setup, unlock, biometrics, the offer and device links. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccessViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val support = ChatTestSupport()
    private val server get() = support.server

    private fun failing(key: String, status: Int = 500) {
        server.failures[key] = status to """{"message":"kaputt"}"""
    }

    /**
     * One polling round of the view model: its delay passes in virtual time, the request in real time. Waits (at most a
     * few seconds) until [done] holds and no request is open, running what the answers resumed on the test dispatcher.
     */
    private suspend fun poll(done: () -> Boolean) {
        val scheduler = mainRule.dispatcher.scheduler
        scheduler.advanceTimeBy(15)
        scheduler.runCurrent()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (!(done() && server.inFlight.get() == 0)) {
                    delay(10)
                    withContext(Dispatchers.Main) { scheduler.runCurrent() }
                }
            }
        }
        scheduler.runCurrent()
    }

    private suspend fun loaded(context: ChatContext) = ChatAccessViewModel(context).also { it.load() }

    @Test
    fun loadingDecidesTheScreen() = runTest {
        val open = support.member(1)
        open.vault.safety = SafetyLists(lists = mapOf("insult" to listOf("x")))
        assertEquals("an open vault goes straight in", ChatPhase.Open, loaded(open).state.value.phase)
        assertEquals("the word lists are loaded once", listOf("x"), open.vault.safety.lists["insult"])

        val offline = support.context(2)
        failing("GET safety")
        failing("GET identity")
        assertEquals(
            ChatPhase.Failed("Da ist etwas schiefgegangen. Versuch es gleich noch einmal."),
            loaded(offline).state.value.phase
        )

        // Another screen opened the vault while the identity was loading.
        val fresh = support.context(3)
        val vault = fresh.vault
        server.onRequest = { if (it == "GET identity") vault.unlock(testCrypto.createIdentity(), vault.generation) }
        assertEquals(ChatPhase.Open, loaded(fresh).state.value.phase)
        server.onRequest = {}
    }

    @Test
    fun resetGoesToSetupAndBack() = runTest {
        val context = support.member(1, locked = true)
        val model = loaded(context)
        assertEquals(ChatPhase.Unlock, model.state.value.phase)
        model.setPassphrase("x")
        model.startReset()
        assertEquals(ChatPhase.Setup, model.state.value.phase)
        assertEquals("", model.state.value.passphrase)
        assertTrue(model.state.value.canCancelReset)
        model.cancelReset()
        assertEquals(ChatPhase.Unlock, model.state.value.phase)
    }

    @Test
    fun setupFailuresStayOnTheSetupScreen() = runTest {
        val vault = support.context(1).vault
        val racing = support.context(1, vault = vault, cryptoDispatcher = HookedDispatcher { vault.lock(announce = false) })
        val model = loaded(racing)
        model.setPassphrase(TEST_PASSPHRASE)
        model.setConfirmation(TEST_PASSPHRASE)
        model.setUp()
        assertEquals("locked meanwhile: nothing opens", ChatPhase.Setup, model.state.value.phase)

        val refused = loaded(support.context(2))
        refused.setPassphrase(TEST_PASSPHRASE)
        refused.setConfirmation(TEST_PASSPHRASE)
        failing("PUT identity", 409)
        refused.setUp()
        assertEquals("Der Chat bekommt gerade einen neuen Schlüssel. Versuch es gleich noch einmal.", refused.state.value.formError)
        assertFalse(refused.state.value.busy)
    }

    @Test
    fun biometricSetupReportsEveryOutcome() = runTest {
        val unsupported = loaded(support.context(1, secrets = MemoryDeviceSecretStore(available = false)))
        unsupported.setUpWithDevice()
        assertTrue(unsupported.state.value.deviceSetupStatus.orEmpty().startsWith("Das hat nicht geklappt."))

        val refused = loaded(support.context(2, secrets = MemoryDeviceSecretStore(available = true)))
        failing("PUT identity", 429)
        refused.setUpWithDevice()
        assertEquals("Zu viele Anfragen in kurzer Zeit. Warte eine Minute.", refused.state.value.deviceSetupStatus)

        val context = support.context(3, secrets = MemoryDeviceSecretStore(available = true))
        val racing = loaded(context)
        server.onRequest = { if (it == "POST device-unlocks") context.vault.lock(announce = false) }
        racing.setUpWithDevice()
        server.onRequest = {}
        assertEquals(ChatPhase.Setup, racing.state.value.phase)
    }

    @Test
    fun unlockingNeedsAPassphraseAStoredCopyAndNoLockInBetween() = runTest {
        val context = support.member(1, locked = true)
        val model = loaded(context)
        model.unlock()
        assertEquals("Bitte gib deine Chat-Passphrase ein.", model.state.value.formError)

        val vault = context.vault
        val racing = loaded(support.context(1, vault = vault, cryptoDispatcher = HookedDispatcher { vault.lock(announce = false) }))
        racing.setPassphrase(TEST_PASSPHRASE)
        racing.unlock()
        assertEquals(ChatPhase.Unlock, racing.state.value.phase)

        val notSetUp = loaded(support.context(2))
        notSetUp.setPassphrase(TEST_PASSPHRASE)
        notSetUp.unlock()
        notSetUp.unlockWithDevice()
        assertEquals(ChatPhase.Setup, notSetUp.state.value.phase)
    }

    @Test
    fun biometricUnlockExplainsWhatWentWrong() = runTest {
        val secrets = MemoryDeviceSecretStore(available = true)
        val context = support.member(1, secrets = secrets)
        context.devices.register()
        context.vault.lock()
        val model = loaded(context)
        assertTrue(model.state.value.canUnlockWithDevice)
        failing("GET device-unlocks")
        model.unlockWithDevice(quietOnCancel = true)
        assertEquals(
            "a server error is not a cancelled prompt",
            "Das hat nicht geklappt. Versuch es noch einmal oder nimm deine Passphrase.",
            model.state.value.deviceUnlockError
        )

        // A lock while the copy opens: the screen stays.
        val vault = context.vault
        val racing =
            loaded(
                support.context(
                    1,
                    vault = vault,
                    secrets = secrets,
                    cryptoDispatcher = HookedDispatcher {
                        vault.lock(announce = false)
                    }
                )
            )
        racing.unlockWithDevice()
        assertEquals(ChatPhase.Unlock, racing.state.value.phase)

        // The automatic start waits while a prompt is open …
        val busy = loaded(support.context(1, vault = vault, secrets = secrets))
        server.onRequest = { if (it == "GET device-unlocks") kotlinx.coroutines.runBlocking { busy.autoUnlockWithDevice() } }
        busy.unlockWithDevice()
        server.onRequest = {}
        assertEquals(ChatPhase.Open, busy.state.value.phase)
        vault.lock()
        // … or another screen shows.
        racing.startReset()
        racing.autoUnlockWithDevice()
        assertEquals(ChatPhase.Setup, racing.state.value.phase)
    }

    @Test
    fun theUnlockScreenOnlyOffersBiometricsWhereThisDeviceHasACopy() = runTest {
        val secrets = MemoryDeviceSecretStore(available = true)
        val context = support.member(1, secrets = secrets)
        context.devices.register()
        server.deviceUnlocks.clear()
        context.vault.lock()
        assertFalse("the server forgot this device", loaded(context).state.value.canUnlockWithDevice)
        failing("GET device-unlocks")
        assertFalse(loaded(context).state.value.canUnlockWithDevice)
    }

    @Test
    fun theOfferAppearsOnlyWhereItHelps() = runTest {
        val withoutBiometrics = support.member(1, locked = true)
        val model = loaded(withoutBiometrics)
        model.setPassphrase(TEST_PASSPHRASE)
        model.unlock()
        assertEquals(ChatPhase.Open, model.state.value.phase)
        assertFalse(model.state.value.offersDeviceUnlock)

        val secrets = MemoryDeviceSecretStore(available = true)
        val context = support.member(2, secrets = secrets, locked = true)
        val offered = loaded(context)
        offered.setPassphrase(TEST_PASSPHRASE)
        offered.unlock()
        assertTrue(offered.state.value.offersDeviceUnlock)
        secrets.denies = true
        offered.acceptDeviceOffer()
        assertTrue(context.vault.announcement.value.orEmpty().startsWith("Da ist etwas"))
        secrets.denies = false

        // With a copy on this device, "Passphrase verwenden" needs no offer.
        context.devices.register()
        context.vault.lock()
        val withCopy = loaded(context)
        withCopy.setPassphrase(TEST_PASSPHRASE)
        withCopy.unlock()
        assertFalse(withCopy.state.value.offersDeviceUnlock)
    }

    @Test
    fun deviceLinksWaitForTheApprovalAndHandleEveryEnding() = runBlocking {
        val phone = support.member(1)
        val laptop = support.context(1)
        laptop.vault.setStoredIdentity(phone.vault.storedIdentity)
        val model = ChatAccessViewModel(laptop).apply { pollInterval = 10.milliseconds }
        model.load()
        assertTrue("nothing pending", model.pollOnce())

        failing("POST device-links")
        model.requestLink()
        assertEquals("Da ist etwas schiefgegangen. Versuch es gleich noch einmal.", model.state.value.linkStatus)

        model.requestLink()
        model.requestLink()
        assertNotNull(model.state.value.linkCheck)
        // Not approved yet: the polling goes on.
        val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.onRequest = { asked += it }
        poll { asked.count { it.startsWith("GET device-links/") } == 1 }
        poll { asked.count { it.startsWith("GET device-links/") } == 2 }
        server.onRequest = {}
        assertEquals(asked.toString(), 2, asked.count { it.startsWith("GET device-links/") })
        assertNotNull(model.state.value.linkCheck)

        // Leaving the screen while a round waits for the server: the round ends quietly.
        var stopped = false
        server.onRequest = {
            if (it.startsWith("GET device-links/")) {
                model.stop()
                stopped = true
            }
        }
        poll { stopped }
        server.onRequest = {}
        assertNull(model.state.value.linkStatus)
        model.requestLink()

        // The loop ends with the request: gone on the server.
        server.deviceLinks.clear()
        val gone = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.onRequest = { gone += it }
        poll { model.state.value.linkCheck == null }
        server.onRequest = {}
        assertEquals(1, gone.count { it.startsWith("GET device-links/") })
        assertNull(model.state.value.linkCheck)
        model.requestLink()
        model.cancelLink()
        assertNull(model.state.value.linkCheck)
        model.cancelLink()

        // Rejected (or expired): the request is gone on the server.
        model.requestLink()
        val id = server.deviceLinks.keys.max()
        server.deviceLinks.remove(id)
        assertTrue(model.pollOnce())
        assertEquals("Die Anfrage ist abgelaufen oder wurde abgelehnt. Versuch es noch einmal.", model.state.value.linkStatus)

        model.requestLink()
        failing("GET device-links/${server.deviceLinks.keys.max()}", 500)
        assertTrue(model.pollOnce())
        assertEquals("Da ist etwas schiefgegangen. Versuch es gleich noch einmal.", model.state.value.linkStatus)

        // Approved, but the vault was locked meanwhile: nothing opens.
        model.requestLink()
        val pending = server.deviceLinks.keys.max()
        val publicKey = server.deviceLinks.getValue(pending).getValue("public_key").jsonPrimitive.content
        val approval = testCrypto.approveLink(checkNotNull(phone.vault.identity), publicKey)
        server.deviceLinks.getValue(pending)["approval"] = Json.parseToJsonElement(
            """{"locked_identity":${Json.encodeToString(
                de.velwebsolutions.oasis.chatcrypto.LockedIdentity.serializer(),
                approval.lockedIdentity
            )},"sealed_key":"${approval.sealedKey}"}"""
        )
        server.onRequest = { if (it == "GET device-links/$pending") laptop.vault.lock(announce = false) }
        assertTrue(model.pollOnce())
        server.onRequest = {}
        assertEquals(ChatPhase.Unlock, model.state.value.phase)

        // Approved: the chat opens.
        model.requestLink()
        val approved = server.deviceLinks.keys.max()
        val key = server.deviceLinks.getValue(approved).getValue("public_key").jsonPrimitive.content
        val handed = testCrypto.approveLink(checkNotNull(phone.vault.identity), key)
        server.deviceLinks.getValue(approved)["approval"] = Json.parseToJsonElement(
            """{"locked_identity":${Json.encodeToString(
                de.velwebsolutions.oasis.chatcrypto.LockedIdentity.serializer(),
                handed.lockedIdentity
            )},"sealed_key":"${handed.sealedKey}"}"""
        )
        assertTrue(model.pollOnce())
        assertEquals(ChatPhase.Open, model.state.value.phase)
        assertEquals("Freigegeben – dein Chat ist offen.", laptop.vault.announcement.value)
        laptop.vault.lock()

        // The asking screen goes away: polling stops, the one-time key is wiped.
        model.requestLink()
        clear(model)
        assertNull(model.state.value.linkCheck)
        val noStored = ChatAccessViewModel(support.context(2))
        noStored.requestLink()
        assertTrue("no stored identity to compare with", noStored.pollOnce())
    }
}
