package de.velwebsolutions.oasis.chat.core

import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.api.PublicUser
import de.velwebsolutions.oasis.chat.api.StoredIdentity
import de.velwebsolutions.oasis.chatcrypto.ChatCryptoException
import de.velwebsolutions.oasis.chatcrypto.ChatIdentity
import de.velwebsolutions.oasis.chatcrypto.createConversationKey
import de.velwebsolutions.oasis.chatcrypto.createKeyGrant
import de.velwebsolutions.oasis.chatcrypto.openKeyGrant
import kotlinx.coroutines.withContext

/**
 * Identity setup/unlock, the member key directory, grants and owner key distribution, following the rotation procedure
 * in docs/oasis-chat.md – the twin of resources/js/chat/client/keyring.js and iOS `ChatKeyring`. Call from the main
 * thread; the expensive crypto moves to [ChatContext.cryptoDispatcher].
 */
class ChatKeyring(private val context: ChatContext) {
    private val vault get() = context.vault
    private val crypto get() = context.crypto
    private val api get() = context.repository

    /**
     * New keys locked with the passphrase (production Argon2id costs) and published. Replaces an existing identity
     * (forgotten passphrase): old messages become unreadable and every chat needs a new key.
     * @return the stored identity, or null if the vault was locked meanwhile.
     */
    suspend fun setUp(passphrase: String): StoredIdentity? {
        val generation = vault.generation
        val identity = crypto.createIdentity()
        val (locked, bundle) = try {
            withContext(context.cryptoDispatcher) { crypto.lockIdentity(identity, passphrase) to crypto.exportPublicKeys(identity) }
        } catch (error: Exception) {
            identity.wipe()
            throw error
        }
        if (generation != vault.generation) {
            identity.wipe()
            return null
        }
        try {
            val stored = api.saveIdentity(locked, bundle)
            if (!vault.unlock(identity, generation)) return null
            vault.setStoredIdentity(stored)
            return stored
        } catch (error: Exception) {
            identity.wipe()
            throw error
        }
    }

    /** Unlock the stored passphrase copy and check that the published bundle belongs to it. */
    suspend fun unlock(stored: StoredIdentity, passphrase: String): Boolean {
        val locked = stored.lockedIdentity ?: throw ChatCryptoException.WrongPassphrase
        val generation = vault.generation
        val identity = withContext(context.cryptoDispatcher) { crypto.unlockIdentity(locked, passphrase) }
        return adopt(identity, stored, generation)
    }

    /** Adopt an identity opened from any copy (passphrase, biometrics, device link) after the published-keys check. */
    fun adopt(identity: ChatIdentity, stored: StoredIdentity, generation: Int): Boolean {
        try {
            val published = crypto.verifyPublicKeys(stored.publicKeys)
            if (!published.signing.contentEquals(identity.signingPublicKey) ||
                !published.encryption.contentEquals(identity.encryptionPublicKey)
            ) {
                throw ChatKeyringException.PublishedKeysMismatch
            }
        } catch (_: Exception) {
            identity.wipe()
            throw ChatKeyringException.PublishedKeysMismatch
        }
        return vault.unlock(identity, generation)
    }

    /** Directory of a conversation's (also past) members, verified and remembered. */
    data class Directory(
        val users: List<PublicUser>,
        /** Ids whose signing key changed while the app was open. */
        val changedInSession: List<String>,
        /** Ids whose bundle failed verification. */
        val invalid: List<String>
    ) {
        fun user(id: String): PublicUser? = users.firstOrNull { it.id == id }
    }

    suspend fun loadDirectory(conversationId: String): Directory {
        val users = api.publicKeys(conversationId)
        val changed = mutableListOf<String>()
        val invalid = mutableListOf<String>()
        for (user in users) {
            val bundle = user.publicKeys ?: continue
            try {
                if (vault.rememberPublicKeys(user.id, crypto.verifyPublicKeys(bundle))) changed += user.id
            } catch (_: ChatCryptoException) {
                invalid += user.id
            }
        }
        return Directory(users, changed, invalid)
    }

    /** Open every grant addressed to the caller that is not open yet; only grants signed by the owner (`created_by`) count. */
    suspend fun openGrants(conversation: ChatConversation) {
        val owner = vault.publicKeys(conversation.createdBy) ?: return
        val identity = vault.identity ?: return
        val generation = vault.generation
        for (grant in api.keyGrants(conversation.id)) {
            if (vault.conversationKey(conversation.id, grant.keyVersion) != null) continue
            val opened = try {
                crypto.openKeyGrant(grant, identity, owner.signing)
            } catch (_: ChatCryptoException) {
                continue
            }
            val addressed = opened.conversationId == conversation.id && opened.recipientId == vault.userId &&
                opened.granterId == conversation.createdBy
            // A lock since the start wiped the identity, so nothing opens afterwards; the vault checks once more.
            if (addressed) vault.rememberConversationKey(conversation.id, opened.keyVersion, opened.conversationKey, generation)
        }
    }

    /** Owner: a fresh random key for the current version, granted to every active member, then activated. */
    suspend fun distributeKey(conversation: ChatConversation): ChatConversation {
        val recipients = conversation.members.mapNotNull { member -> vault.publicKeys(member.userId)?.let { member to it } }
        val missing = conversation.members.filter { member -> recipients.none { it.first == member } }
        if (missing.isNotEmpty()) throw ChatKeyringException.MissingMemberKeys(missing.map { it.username })
        val identity = vault.identity ?: throw ChatCryptoException.InvalidInput("identity must be an identity object.")
        val generation = vault.generation
        val key = crypto.createConversationKey()
        val grants = recipients.map { (member, keys) ->
            crypto.createKeyGrant(
                conversationKey = key,
                conversationId = conversation.id,
                keyVersion = conversation.keyVersion,
                recipientId = member.userId,
                recipientEncryptionPublicKey = keys.encryption,
                granterId = vault.userId,
                identity = identity
            )
        }
        api.storeKeyGrants(conversation.id, conversation.keyVersion, grants)
        val activated = api.activateKey(conversation.id, conversation.keyVersion)
        vault.rememberConversationKey(conversation.id, conversation.keyVersion, key, generation)
        return activated
    }

    /** Safety number with another member, or null if a key is unknown. */
    fun safetyNumber(userId: String): String? {
        val identity = vault.identity ?: return null
        val other = vault.publicKeys(userId) ?: return null
        return crypto.fingerprint(identity.signingPublicKey, other.signing)
    }
}
