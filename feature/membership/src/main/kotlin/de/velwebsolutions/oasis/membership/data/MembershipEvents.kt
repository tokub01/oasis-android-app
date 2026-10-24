package de.velwebsolutions.oasis.membership.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the overview should show after another membership screen finished (the web's flash messages after "Jetzt
 * kündigen", "Jetzt widerrufen" or a purchase) and a counter that makes it reload.
 */
@Singleton
class MembershipEvents @Inject constructor() {
    /** One pending outcome: confirmation (`role="status"`) and error (`role="alert"`). */
    data class Outcome(val message: String? = null, val error: String? = null)

    private val pending = MutableStateFlow(Outcome())
    private val mutableVersion = MutableStateFlow(0)

    /** Increased whenever the membership changed; the overview reloads on change. */
    val version: StateFlow<Int> = mutableVersion.asStateFlow()

    fun changed(message: String? = null, error: String? = null) {
        pending.value = Outcome(message, error)
        mutableVersion.update { it + 1 }
    }

    /** Hands over the pending messages once. */
    fun take(): Outcome {
        val outcome = pending.value
        pending.value = Outcome()
        return outcome
    }
}
