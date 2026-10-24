package de.velwebsolutions.oasis.live.push

import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.live.data.LiveRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why push could not be switched on or off on this device, in the words the screen shows. */
class PushFailure(override val message: String) : Exception(message)

/**
 * Push hints on this device (PushRegistrar on iOS, docs/oasis-pwa.md "Native App" in the Laravel project).
 *
 * After sign-in it asks the server whether app pushes are set up (`app-config`); if so, Firebase is configured in this
 * build and the member has not switched them off on this device, it registers the FCM token with `POST push/devices`,
 * bound to this sign-in's API token, so signing out or revoking the device removes it on the server too. "Auf diesem
 * Gerät ausschalten" in Hinweise einstellen removes it and remembers the choice; "einschalten" undoes that.
 *
 * The Oasis server takes APNs tokens only today ([BACKEND_ACCEPTS_FCM]): until it accepts FCM tokens, [isSupported]
 * is false, nothing is sent and the settings say that push hints are not set up yet.
 */
class PushRegistrar(
    private val system: PushSystem,
    private val store: PushStore,
    private val backendAcceptsFcm: Boolean = BACKEND_ACCEPTS_FCM
) {
    private val mutableAuthorization = MutableStateFlow(PushAuthorization.NotDetermined)
    val authorization: StateFlow<PushAuthorization> = mutableAuthorization.asStateFlow()

    /** Whether the server sends app pushes at all (`app-config` → `push.available`). */
    var isAvailable = false
        private set

    private var repository: LiveRepository? = null

    /** The id of this installation in the push device list, once registered. */
    val deviceId: Int? get() = store.deviceId

    /** The member switched push off on this device. */
    val isOptedOut: Boolean get() = store.optedOut

    /** Whether this build can get push hints from this server at all (Firebase set up, FCM accepted by Laravel). */
    val isSupported: Boolean get() = system.isConfigured && backendAcceptsFcm

    /** After sign-in (and at every start while signed in): register when allowed. */
    suspend fun signedIn(repository: LiveRepository) {
        this.repository = repository
        isAvailable = try {
            repository.appConfig().push.available
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        refreshAuthorization()
        if (!isAvailable || !isSupported || isOptedOut) return
        if (authorization.value == PushAuthorization.Authorized) register()
    }

    /** Firebase handed out a new token (FirebaseMessagingService.onNewToken). */
    suspend fun tokenChanged() {
        if (repository != null && isAvailable && isSupported && !isOptedOut && authorization.value == PushAuthorization.Authorized) {
            register()
        }
    }

    /** On signing out: forget this installation. The server removes it together with the sign-in's API token. */
    fun signedOut() {
        store.deviceId = null
        repository = null
    }

    /** The screen asked for the notification permission. */
    fun permissionAnswered(granted: Boolean) {
        system.permissionAnswered(granted)
        refreshAuthorization()
    }

    /** "Auf diesem Gerät einschalten": permission (asked by the screen before), FCM, server. Returns the confirmation. */
    suspend fun enableOnThisDevice(): String {
        store.optedOut = false
        refreshAuthorization()
        if (authorization.value != PushAuthorization.Authorized) throw PushFailure(DENIED_TEXT)
        if (!register()) throw PushFailure(FAILED_TEXT)
        return "Push-Hinweise sind auf diesem Gerät eingeschaltet."
    }

    /** "Auf diesem Gerät ausschalten": remove this installation and remember the choice. Returns the confirmation. */
    suspend fun disableOnThisDevice(): String {
        store.optedOut = true
        val id = store.deviceId
        val repository = repository
        if (id != null && repository != null) {
            try {
                repository.removeAppDevice(id)
            } catch (_: ApiError.NotFound) {
                // Already gone (removed on the web).
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                store.optedOut = false
                throw PushFailure(FAILED_TEXT)
            }
        }
        store.deviceId = null
        system.deleteToken()
        return "Push-Hinweise sind auf diesem Gerät ausgeschaltet."
    }

    /** The device was removed from the list (here or on the web). */
    fun forgetDevice(id: Int) {
        if (store.deviceId == id) store.deviceId = null
    }

    fun openSystemSettings() = system.openSettings()

    /** Refresh the permission state (e.g. when the settings screen appears after a visit to the Android settings). */
    fun refreshAuthorization() {
        mutableAuthorization.value = system.authorization()
    }

    private suspend fun register(): Boolean {
        val repository = repository ?: return false
        if (!isSupported) return false
        val token = system.token() ?: return false
        return try {
            store.deviceId = repository.registerDevice(token).id
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        /**
         * Laravel's PushDeviceController validates APNs hex tokens and stores `platform => ios`; FCM tokens would be
         * refused. Switch this on together with the server side (see the report of the Android port).
         */
        const val BACKEND_ACCEPTS_FCM = false

        /** Shown while Android does not allow notifications for Oasis. */
        const val DENIED_TEXT =
            "Du hast Mitteilungen für Oasis in den Android-Einstellungen ausgeschaltet. Erlaube sie dort, um Push-Hinweise zu bekommen."
        const val FAILED_TEXT = "Das hat nicht geklappt. Bitte versuche es später noch einmal."
    }
}
