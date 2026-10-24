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
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

/** Runs crypto on [Dispatchers.Default] after [before] – e.g. a lock that happens while Argon2id works. */
class HookedDispatcher(private val before: () -> Unit) : CoroutineDispatcher() {
    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
        before()
        Dispatchers.Default.dispatch(context, block)
    }
}
