package de.velwebsolutions.oasis.account.ui.settings

import de.velwebsolutions.oasis.account.data.AccountRepository
import de.velwebsolutions.oasis.account.data.needsPasswordConfirmation
import de.velwebsolutions.oasis.account.ui.auth.attempt
import de.velwebsolutions.oasis.core.network.ApiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** „Kurz bestätigen“ is open; [error] is the refusal of the last password. */
data class PasswordPrompt(val error: String? = null)

/**
 * Settings actions that need a recent password confirmation of this device (adding or removing a passkey, the mobile
 * number). A 423 parks the action and opens [prompt]; after the password it runs again by itself, like the web's
 * resume.
 */
class PasswordGate(private val repository: AccountRepository) {
    private val mutablePrompt = MutableStateFlow<PasswordPrompt?>(null)
    val prompt: StateFlow<PasswordPrompt?> = mutablePrompt.asStateFlow()

    private var parked: (suspend () -> Unit)? = null

    /** Runs [action]; a 423 waits for [confirm], any other error goes to [onFailure]. */
    suspend fun run(action: suspend () -> Unit, onFailure: (ApiError) -> Unit) {
        val failure = attempt(action) ?: return
        if (failure.needsPasswordConfirmation) {
            parked = { run(action, onFailure) }
            mutablePrompt.value = PasswordPrompt()
        } else {
            onFailure(failure)
        }
    }

    /** Confirms the password and runs the parked action again; false on a wrong password. */
    suspend fun confirm(password: String): Boolean {
        val failure = attempt { repository.confirmPassword(password) }
        if (failure != null) {
            mutablePrompt.value = PasswordPrompt(failure.message("password") ?: failure.userMessage)
            return false
        }
        // The sheet stays open (its button spinning) while the action runs; closing it first would cancel the action
        // with the sheet's coroutine scope. A second 423 keeps it open.
        mutablePrompt.value = PasswordPrompt()
        val action = parked
        parked = null
        action?.invoke()
        if (parked == null) mutablePrompt.value = null
        return true
    }

    /** „Abbrechen“: the parked action is dropped. */
    fun cancel() {
        parked = null
        mutablePrompt.value = null
    }
}
