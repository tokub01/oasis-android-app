package de.velwebsolutions.oasis.chat.core

import de.velwebsolutions.oasis.chat.api.DeviceLinkRequest
import de.velwebsolutions.oasis.chat.api.DeviceUnlockRow
import de.velwebsolutions.oasis.chat.api.StoredIdentity
import de.velwebsolutions.oasis.chatcrypto.ChatIdentity
import de.velwebsolutions.oasis.chatcrypto.LinkKeyPair
import de.velwebsolutions.oasis.chatcrypto.approveLink
import de.velwebsolutions.oasis.chatcrypto.checkNumber
import de.velwebsolutions.oasis.chatcrypto.createLinkKeyPair
import de.velwebsolutions.oasis.chatcrypto.lockForDevice
import de.velwebsolutions.oasis.chatcrypto.openLinkApproval
import de.velwebsolutions.oasis.chatcrypto.unlockWithDeviceSecret
import kotlinx.coroutines.withContext

/**
 * "Mit Fingerabdruck entsperren" and "Mit anderem Gerät freigeben" (client/device-unlock.js, client/device-link.js) with
 * the app's keystore secret in place of the browser's passkey PRF secret. The server rows are identical, so web, iOS and
 * Android list each other's devices and can hand the chat to each other.
 */
class ChatDevices(private val context: ChatContext) {
    private val vault get() = context.vault
    private val crypto get() = context.crypto
    private val api get() = context.repository
    private val secrets get() = context.secrets

    /** Whether this device can use a strong biometric at all. */
    val isAvailable: Boolean get() = secrets.isAvailable()

    /** This device's row among the member's devices, if it was set up here. */
    suspend fun ownRow(rows: List<DeviceUnlockRow>): DeviceUnlockRow? {
        val credential = secrets.credentialId(vault.userId) ?: return null
        return rows.firstOrNull { it.credentialId == credential }
    }

    /**
     * First use without a passphrase: the secret first (nothing is stored when the device cannot), then fresh keys
     * published without a passphrase copy, then the device copy.
     */
    suspend fun setUpWithDevice(): StoredIdentity? {
        val generation = vault.generation
        val device = secrets.create(vault.userId)
        val identity = crypto.createIdentity()
        try {
            val stored = api.saveIdentity(null, crypto.exportPublicKeys(identity))
            storeCopy(identity, device)
            if (!vault.unlock(identity, generation)) return null
            vault.setStoredIdentity(stored)
            return stored
        } catch (error: Exception) {
            identity.wipe()
            secrets.delete(vault.userId)
            throw error
        }
    }

    /** Add this device to the unlocked chat. */
    suspend fun register(): DeviceUnlockRow {
        val identity = vault.identity ?: throw ChatKeyringException.DeviceUnlockUnsupported
        val device = secrets.create(vault.userId)
        try {
            return storeCopy(identity, device)
        } catch (error: Exception) {
            secrets.delete(vault.userId)
            throw error
        }
    }

    private suspend fun storeCopy(identity: ChatIdentity, device: DeviceSecret): DeviceUnlockRow {
        val copy = try {
            withContext(context.cryptoDispatcher) { crypto.lockForDevice(identity, device.secret) }
        } finally {
            device.secret.fill(0)
        }
        return api.saveDeviceUnlock(device.credentialId, crypto.toBase64(crypto.randomBytes(32)), context.deviceLabel, copy)
    }

    /** Unlock with the biometric prompt: the keystore secret opens this device's copy (same checks as every unlock). */
    suspend fun unlock(stored: StoredIdentity, rows: List<DeviceUnlockRow>): Boolean {
        val row = ownRow(rows) ?: throw ChatKeyringException.DeviceUnlockUnsupported
        val generation = vault.generation
        val secret = secrets.secret(vault.userId, "Nachrichten entsperren") ?: throw ChatKeyringException.DeviceUnlockUnsupported
        val identity = try {
            withContext(context.cryptoDispatcher) { crypto.unlockWithDeviceSecret(row.lockedIdentity, secret) }
        } finally {
            secret.fill(0)
        }
        return context.keyring.adopt(identity, stored, generation)
    }

    /** Remove a device; this device's own keystore secret goes with its row. */
    suspend fun remove(row: DeviceUnlockRow) {
        api.deleteDeviceUnlock(row.id)
        if (secrets.credentialId(vault.userId) == row.credentialId) secrets.delete(vault.userId)
    }

    // Device links

    /** A pending request of this device. */
    class PendingLink(val id: String, val check: String, internal val keyPair: LinkKeyPair)

    /** Asking device: create the one-time key and the request. */
    suspend fun requestLink(): PendingLink {
        val keyPair = crypto.createLinkKeyPair()
        val created = api.requestDeviceLink(keyPair.publicKeyBase64, context.deviceLabel)
        return PendingLink(created.id, crypto.checkNumber(keyPair.publicKeyBase64), keyPair)
    }

    /** Asking device: null while not approved; true once the chat is open. */
    suspend fun pickUp(link: PendingLink, stored: StoredIdentity): Boolean? {
        val generation = vault.generation
        val approval = api.deviceLink(link.id) ?: return null
        val identity = withContext(context.cryptoDispatcher) {
            crypto.openLinkApproval(approval.lockedIdentity, approval.sealedKey, link.keyPair)
        }
        return context.keyring.adopt(identity, stored, generation)
    }

    suspend fun cancel(link: PendingLink) {
        link.keyPair.wipe()
        runCatching { api.rejectDeviceLink(link.id) }
    }

    /** Unlocked device: hand the keys to a request whose check number the member confirmed. */
    suspend fun approve(request: DeviceLinkRequest) {
        val identity = vault.identity ?: throw ChatKeyringException.DeviceUnlockUnsupported
        val approval = withContext(context.cryptoDispatcher) { crypto.approveLink(identity, request.publicKey) }
        api.approveDeviceLink(request.id, approval.lockedIdentity, approval.sealedKey)
    }
}
