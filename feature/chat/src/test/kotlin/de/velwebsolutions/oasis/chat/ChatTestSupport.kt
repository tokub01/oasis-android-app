package de.velwebsolutions.oasis.chat

import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.core.ChatPreferences
import de.velwebsolutions.oasis.chat.core.ChatVault
import de.velwebsolutions.oasis.chat.core.DeviceSecretStore
import de.velwebsolutions.oasis.chat.core.MemoryChatPreferences
import de.velwebsolutions.oasis.chat.core.MemoryDeviceSecretStore
import de.velwebsolutions.oasis.chat.data.ChatRepository
import de.velwebsolutions.oasis.chat.data.DefaultChatRepository
import de.velwebsolutions.oasis.chatcrypto.ChatCrypto
import de.velwebsolutions.oasis.chatcrypto.JvmSodium
import de.velwebsolutions.oasis.chatcrypto.KdfLimits
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/** The real crypto core on lazysodium-java (libsodium is initialised once per test JVM). */
val testCrypto: ChatCrypto by lazy { ChatCrypto(JvmSodium()) }

/** Contexts of members against one [FakeChatServer]: each with its own vault, like two devices. */
class ChatTestSupport(val server: FakeChatServer = FakeChatServer()) {
    /** Timers of the vault never fire in these tests (no idle lock while a test waits for the fake server). */
    private val vaultScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun context(
        userId: Int,
        secrets: DeviceSecretStore = MemoryDeviceSecretStore(available = false),
        canParticipate: Boolean = true,
        preferences: ChatPreferences = MemoryChatPreferences(),
        vault: ChatVault = ChatVault(vaultScope, idleLock = Duration.INFINITE),
        cryptoDispatcher: CoroutineDispatcher = Dispatchers.Default,
        repository: (ChatRepository) -> ChatRepository = { it }
    ): ChatContext {
        vault.prepare(userId.toString())
        return ChatContext(
            repository = repository(DefaultChatRepository(server.client(userId))),
            vault = vault,
            crypto = testCrypto,
            secrets = secrets,
            preferences = preferences,
            me = server.me(userId, canParticipate),
            cryptoDispatcher = cryptoDispatcher
        )
    }
}

/**
 * A member whose chat is set up (passphrase copy with the minimal Argon2id costs, so tests stay fast) and open.
 * [locked] leaves the vault locked after publishing the keys.
 */
fun ChatTestSupport.member(
    userId: Int,
    secrets: DeviceSecretStore = MemoryDeviceSecretStore(available = false),
    passphrase: String = TEST_PASSPHRASE,
    locked: Boolean = false,
    preferences: ChatPreferences = MemoryChatPreferences()
): ChatContext {
    val context = context(userId, secrets = secrets, preferences = preferences)
    val identity = testCrypto.createIdentity()
    val copy = testCrypto.lockIdentity(identity, passphrase, KdfLimits.Minimum)
    val stored = runBlocking { context.repository.saveIdentity(copy, testCrypto.exportPublicKeys(identity)) }
    context.vault.setStoredIdentity(stored)
    if (locked) identity.wipe() else context.vault.unlock(identity, context.vault.generation)
    return context
}

/** The passphrase of [member]. */
const val TEST_PASSPHRASE = "Palme Regen Kompass Tee"

/** Runs crypto on [Dispatchers.Default] after [before] – e.g. a lock that happens while Argon2id works. */
class HookedDispatcher(private val before: () -> Unit) : CoroutineDispatcher() {
    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
        before()
        Dispatchers.Default.dispatch(context, block)
    }
}

/**
 * Waits in real time (at most a few seconds) until [done] holds and the fake server answered every request, running
 * what the answers resumed on the test dispatcher of [scheduler].
 */
suspend fun settle(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler, server: FakeChatServer, done: () -> Boolean) {
    kotlinx.coroutines.withContext(Dispatchers.Default) {
        kotlinx.coroutines.delay(30)
        kotlinx.coroutines.withTimeout(5_000) {
            while (!(done() && server.inFlight.get() == 0)) {
                kotlinx.coroutines.delay(10)
                kotlinx.coroutines.withContext(Dispatchers.Main) { scheduler.runCurrent() }
            }
        }
    }
    scheduler.runCurrent()
}
