package de.velwebsolutions.oasis.live.push

import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.live.catching
import de.velwebsolutions.oasis.live.data.LiveRepository
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
 * Gerät ausschalten" in Hinweise einstellen removes it and remembers the choice, and so does "Entfernen" of this
 * installation in the device list; "einschalten" undoes that.
 *
 * Before Android 13 there is no system dialog, so nothing is registered until the member answered the shell's own question
 * „Push-Hinweise einschalten?“ with yes ([PushStore.inAppConsent]); „Später“ is remembered too, and "einschalten" in the
 * settings counts as that yes. From Android 13 on the POST_NOTIFICATIONS dialog is the consent.
 *
 * A build without google-services.json has no Firebase: [isSupported] is false, nothing is sent, and the settings say
 * that the app gets no push hints.
 */
class PushRegistrar(private val system: PushSystem, private val store: PushStore) {
    private val mutableAuthorization = MutableStateFlow(PushAuthorization.NotDetermined)
    val authorization: StateFlow<PushAuthorization> = mutableAuthorization.asStateFlow()

    private val mutableAsksForPermission = MutableStateFlow(false)

    /**
     * After sign-in the shell asks once for notifications, like iOS: true while the server sends app pushes, this build has
     * Firebase, the member has not switched push off here and nobody has asked yet – Android 13+ with its dialog, older
     * versions with the app's own question ([asksInApp]).
     */
    val asksForPermission: StateFlow<Boolean> = mutableAsksForPermission.asStateFlow()

    /** Whether this device needs the app's own question „Push-Hinweise einschalten?“ instead of the system dialog. */
    val asksInApp: Boolean get() = system.asksInApp

    /** Whether the server sends app pushes at all (`app-config` → `push.available`). */
    var isAvailable = false
        private set

    private var repository: LiveRepository? = null

    /** The id of this installation in the push device list, once registered. */
    val deviceId: Int? get() = store.deviceId

    /** The member switched push off on this device. */
    val isOptedOut: Boolean get() = store.optedOut

    /** Whether this build can get push hints at all (Firebase set up). */
    val isSupported: Boolean get() = system.isConfigured

    /** The member said yes to the app's own question; from Android 13 on the system dialog is the consent. */
    private val consented: Boolean get() = !system.asksInApp || store.inAppConsent == true

    /** After sign-in (and at every start while signed in): register when allowed. */
    suspend fun signedIn(repository: LiveRepository) {
        this.repository = repository
        isAvailable = catching { repository.appConfig().push.available }.getOrDefault(false)
        refreshAuthorization()
        mutableAsksForPermission.value = isAvailable && isSupported && !isOptedOut && nobodyAskedYet()
        registerIfAllowed()
    }

    /** Android 13+: the system has not asked yet. Before: the app has not asked yet and Android allows notifications. */
    private fun nobodyAskedYet(): Boolean = if (system.asksInApp) {
        store.inAppConsent == null && authorization.value == PushAuthorization.Authorized
    } else {
        authorization.value == PushAuthorization.NotDetermined
    }

    /** The answer to the prompt after sign-in: registers right away when notifications are allowed now. */
    suspend fun promptAnswered(granted: Boolean) {
        permissionAnswered(granted)
        registerIfAllowed()
    }

    /** Firebase handed out a new token (FirebaseMessagingService.onNewToken). */
    suspend fun tokenChanged() = registerIfAllowed()

    /** On signing out: forget this installation. The server removes it together with the sign-in's API token. */
    fun signedOut() {
        store.deviceId = null
        repository = null
    }

    /** The screen asked for the notification permission – the system dialog, or the app's own question before Android 13. */
    fun permissionAnswered(granted: Boolean) {
        if (system.asksInApp) store.inAppConsent = granted else system.permissionAnswered(granted)
        mutableAsksForPermission.value = false
        refreshAuthorization()
    }

    /** "Auf diesem Gerät einschalten": permission (asked by the screen before), FCM, server. Returns the confirmation. */
    suspend fun enableOnThisDevice(): String {
        store.optedOut = false
        // "einschalten" is the consent the app's own question asks for before Android 13.
        if (system.asksInApp) store.inAppConsent = true
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
            val failure = catching { repository.removeAppDevice(id) }.exceptionOrNull()
            // A device the server no longer knows was removed on the web already.
            if (failure != null && failure !is ApiError.NotFound) {
                store.optedOut = false
                throw PushFailure(FAILED_TEXT)
            }
        }
        store.deviceId = null
        system.deleteToken()
        return "Push-Hinweise sind auf diesem Gerät ausgeschaltet."
    }

    /**
     * The device was removed from the list. When it is this installation ([current]), the choice is remembered like
     * after "ausschalten": otherwise the next start would register it again.
     */
    fun forgetDevice(id: Int, current: Boolean = false) {
        if (current) store.optedOut = true
        if (current || store.deviceId == id) store.deviceId = null
    }

    fun openSystemSettings() = system.openSettings()

    /** Refresh the permission state (e.g. when the settings screen appears after a visit to the Android settings). */
    fun refreshAuthorization() {
        mutableAuthorization.value = system.authorization()
    }

    /** Registers when the server sends app pushes, the member wants them here (and said so) and Android allows them. */
    private suspend fun registerIfAllowed() {
        if (isAvailable && !isOptedOut && consented && authorization.value == PushAuthorization.Authorized) register()
    }

    /** Sends this installation's token; false without sign-in, support, token or a working server. */
    private suspend fun register(): Boolean {
        val repository = repository ?: return false
        if (!isSupported) return false
        val token = system.token() ?: return false
        val device = catching { repository.registerDevice(token) }.getOrNull() ?: return false
        store.deviceId = device.id
        return true
    }

    companion object {
        /** Shown while Android does not allow notifications for Oasis. */
        const val DENIED_TEXT =
            "Du hast Mitteilungen für Mildcrowd in den Android-Einstellungen ausgeschaltet. Erlaube sie dort, um Push-Hinweise zu bekommen."
        const val FAILED_TEXT = "Das hat nicht geklappt. Bitte versuche es später noch einmal."
    }
}
