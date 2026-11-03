package de.velwebsolutions.oasis.account.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async

/**
 * A retry that runs in the view model's scope: the button that starts it disappears as soon as the screen shows that it
 * loads again, and the button's own scope would cancel the load halfway (the screen then stays on „Lädt …“).
 */
fun ViewModel.retry(load: suspend () -> Unit): suspend () -> Unit = { viewModelScope.async { load() }.await() }
