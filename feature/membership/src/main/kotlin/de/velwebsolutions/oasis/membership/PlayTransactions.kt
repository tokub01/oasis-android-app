package de.velwebsolutions.oasis.membership

import javax.inject.Inject
import javax.inject.Singleton

/** Google Play purchases that arrive outside the checkout (renewals, other devices, pending payments). */
@Singleton
class PlayTransactions @Inject constructor() {
    /** Listens while the member is signed in; called by the tab shell. */
    suspend fun observe() = Unit
}
