package de.velwebsolutions.oasis.chat.core

import de.velwebsolutions.oasis.chat.data.ChatRepository
import de.velwebsolutions.oasis.chatcrypto.ChatCrypto
import de.velwebsolutions.oasis.core.model.Me
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Everything a chat screen needs: the API, the in-memory vault, the crypto, the device secret store and who is signed in. */
class ChatContext(
    val repository: ChatRepository,
    val vault: ChatVault,
    val crypto: ChatCrypto,
    val secrets: DeviceSecretStore,
    val preferences: ChatPreferences,
    val me: Me,
    /** The label of this device in the device lists (iOS: "iPhone (Oasis-App)"). */
    val deviceLabel: String = DEFAULT_DEVICE_LABEL,
    /** Where Argon2id and the other expensive crypto runs (iOS: a detached task). */
    val cryptoDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    /** The signed-in member's id as a string, as in all crypto objects. */
    val userId: String get() = vault.userId

    /** False for read-only accounts (trial ended, no membership): reading only, no new chats or sending. */
    val canParticipate: Boolean get() = me.canParticipate

    val keyring: ChatKeyring get() = ChatKeyring(this)

    val devices: ChatDevices get() = ChatDevices(this)

    companion object {
        const val DEFAULT_DEVICE_LABEL = "Android (Oasis-App)"
    }
}
