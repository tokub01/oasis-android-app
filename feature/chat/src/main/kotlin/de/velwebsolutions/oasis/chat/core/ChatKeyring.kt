package de.velwebsolutions.oasis.chat.core

import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.api.PublicUser
import de.velwebsolutions.oasis.chat.api.StoredIdentity
import de.velwebsolutions.oasis.chatcrypto.ChatCryptoException
import de.velwebsolutions.oasis.chatcrypto.ChatEncoding
import de.velwebsolutions.oasis.chatcrypto.ChatIdentity
import de.velwebsolutions.oasis.chatcrypto.createConversationKey
import de.velwebsolutions.oasis.chatcrypto.createKeyGrant
import de.velwebsolutions.oasis.chatcrypto.openKeyGrant
import kotlinx.coroutines.withContext

/**
 * Identity setup/unlock, the member key directory, grants and owner key distribution, following the rotation procedure
 * in docs/oasis-chat.md – the twin of resources/js/chat/client/keyring.js and iOS `ChatKeyring`. Call from the main
 * thread; the expensive crypto moves to [ChatContext.cryptoDispatcher].
 *
 * The owner is checked like on the web ([ownerIsMember], [ownerTrusted]): a chat that can be written in lists its owner
 * (`created_by`) as an active member with the role owner – a group owner can neither leave nor be removed, a direct chat
 * has two people. A server naming someone else, or another owner than this app session saw first ([ChatVault.pinOwner]),
 * would hand out a key of its own past every safety number: then no grant is opened and nothing is sealed. Only an
 * `ended` direct chat whose starter left still opens the starter's older keys, for its history ([starterLeft]).
 */
class ChatKeyring(private val context: ChatContext) {
    private val vault get() = context.vault
    private val crypto get() = context.crypto
    private val api get() = context.repository

    /**
     * New keys locked with the passphrase (production Argon2id costs) and published. Replaces an existing identity
     * (forgotten passphrase): old messages become unreadable and every chat needs a new key. Published while Oasis went
     * to the background, they become the stored identity, to be unlocked with the passphrase on return.
     * @return the stored identity, or null if the vault was locked meanwhile or Oasis is in the background.
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
            if (!vault.unlock(identity, generation)) {
                // Only the background stood in the way (a lock or another member counts up the generation).
                if (generation == vault.generation) vault.setStoredIdentity(stored)
                return null
            }
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
        /** The member with [id], if the directory lists them. */
        fun user(id: String): PublicUser? = users.firstOrNull { it.id == id }
    }

    /** Loads the members' published keys, verifies and remembers them, and notes changed or invalid ones. */
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

    /**
     * Open every grant addressed to the caller that is not open yet; only grants signed by the owner (`created_by`)
     * count, and none while that owner is not believable (see the class notes; keyring.js `openGrants`). The pending
     * version stays closed: until its activation the owner may still replace that batch (an overlapping distribution,
     * the owner's other device), and nobody can write with it before. With [replaceCurrent] the current version is
     * opened again even when a key of it is known – the owner right after activating it.
     */
    suspend fun openGrants(conversation: ChatConversation, replaceCurrent: Boolean = false) {
        if (!(ownerIsMember(conversation) || starterLeft(conversation)) || !vault.pinOwner(conversation.id, conversation.createdBy)) return
        val identity = vault.identity ?: return
        // The own grants verify against the own key, never the directory's copy of it, which the server could replace.
        val ownerSigning = if (conversation.createdBy == vault.userId) {
            identity.signingPublicKey
        } else {
            vault.publicKeys(conversation.createdBy)?.signing ?: return
        }
        val generation = vault.generation
        for (grant in api.keyGrants(conversation.id)) {
            val current = grant.keyVersion == conversation.keyVersion
            if (current && conversation.rotationRequired) continue
            if (!(current && replaceCurrent) && vault.conversationKey(conversation.id, grant.keyVersion) != null) continue
            val opened = try {
                crypto.openKeyGrant(grant, identity, ownerSigning)
            } catch (_: ChatCryptoException) {
                continue
            }
            val addressed = opened.conversationId == conversation.id && opened.recipientId == vault.userId &&
                opened.granterId == conversation.createdBy
            // A lock since the start wiped the identity, so nothing opens afterwards; the vault checks once more.
            if (addressed) vault.rememberConversationKey(conversation.id, opened.keyVersion, opened.conversationKey, generation)
        }
    }

    /**
     * Owner: a fresh random key for the current version, granted to every active member, then activated. The key the
     * owner keeps is the one of its own grant as the server activated it: another distribution of the same pending
     * version (an overlapping refresh, the owner's browser) may have replaced this batch before the activation, and only
     * the activated batch is the one the others have.
     */
    suspend fun distributeKey(conversation: ChatConversation): ChatConversation {
        val identity = vault.identity ?: throw ChatCryptoException.InvalidInput("identity must be an identity object.")
        // The own grant goes to the own key, never to the directory's copy of it, which the server could replace.
        val recipients = conversation.members.mapNotNull { member ->
            val encryption = if (member.userId ==
                vault.userId
            ) {
                identity.encryptionPublicKey
            } else {
                vault.publicKeys(member.userId)?.encryption
            }
            encryption?.let { member to it }
        }
        val missing = conversation.members.filter { member -> recipients.none { it.first == member } }
        if (missing.isNotEmpty()) throw ChatKeyringException.MissingMemberKeys(missing.map { it.username })
        val key = crypto.createConversationKey()
        val grants = try {
            recipients.map { (member, encryption) ->
                crypto.createKeyGrant(
                    conversationKey = key,
                    conversationId = conversation.id,
                    keyVersion = conversation.keyVersion,
                    recipientId = member.userId,
                    recipientEncryptionPublicKey = encryption,
                    granterId = vault.userId,
                    identity = identity
                )
            }
        } finally {
            ChatEncoding.wipe(key)
        }
        api.storeKeyGrants(conversation.id, conversation.keyVersion, grants)
        val activated = api.activateKey(conversation.id, conversation.keyVersion)
        openGrants(activated, replaceCurrent = true)
        return activated
    }

    /** Safety number with another member, or null if a key is unknown. */
    fun safetyNumber(userId: String): String? {
        val identity = vault.identity ?: return null
        val other = vault.publicKeys(userId) ?: return null
        return crypto.fingerprint(identity.signingPublicKey, other.signing)
    }

    companion object {
        /** The [ChatKeyringException.UntrustedOwner] text, also shown instead of the composer (ui/errors.js). */
        const val UNTRUSTED_OWNER =
            "Die Person, die diesen Chat gestartet hat, ist nicht dabei oder eine andere als vorher. Zum Schutz deiner Nachrichten kannst du hier nichts schicken. Bitte melde dich bei uns."

        /** A 409 or [ChatKeyringException.StaleKeyVersion]: a key rotation is under way (ui/errors.js). */
        const val NEW_KEY = "Der Chat bekommt gerade einen neuen Schlüssel. Versuch es gleich noch einmal."

        /**
         * Whether `created_by` is an active member with the role owner, in a direct chat one of at most two members
         * (keyring.js `ownerIsMember`).
         */
        fun ownerIsMember(conversation: ChatConversation): Boolean {
            val owner = conversation.members.firstOrNull { it.userId == conversation.createdBy }
            return owner?.role == "owner" && (conversation.type != "direct" || conversation.members.size <= 2)
        }

        /**
         * Whether the conversation is a direct chat that ended because its starter left: it stays pending for good, and
         * only the starter's older keys open its history (keyring.js `starterLeft`).
         */
        internal fun starterLeft(conversation: ChatConversation): Boolean = conversation.type == "direct" &&
            conversation.members.size < 2 && conversation.rotationRequired &&
            conversation.members.none { it.userId == conversation.createdBy }

        /**
         * Whether the conversation's owner can be trusted with its keys (keyring.js `ownerTrusted`): an active owner
         * member ([ownerIsMember]) and the one this app session saw first in [vault] (pinned on the first such call). The
         * conversation screen shows [UNTRUSTED_OWNER] instead of the composer otherwise.
         */
        fun ownerTrusted(vault: ChatVault, conversation: ChatConversation): Boolean =
            ownerIsMember(conversation) && vault.pinOwner(conversation.id, conversation.createdBy)

        /**
         * Refuse to encrypt for a conversation state the server may have forged (keyring.js `assertSealable`): an owner
         * the members cannot see or another one than before, or an older key version than one already opened – a removed
         * member still holds the old key.
         * @throws ChatKeyringException.UntrustedOwner
         * @throws ChatKeyringException.StaleKeyVersion
         */
        fun assertSealable(vault: ChatVault, conversation: ChatConversation) {
            if (!ownerTrusted(vault, conversation)) throw ChatKeyringException.UntrustedOwner
            if (vault.newestKeyVersion(conversation.id) > conversation.keyVersion) throw ChatKeyringException.StaleKeyVersion
        }
    }
}
