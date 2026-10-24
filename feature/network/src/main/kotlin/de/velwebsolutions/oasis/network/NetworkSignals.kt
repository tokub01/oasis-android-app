package de.velwebsolutions.oasis.network

import kotlinx.coroutines.flow.MutableStateFlow

/** Signals of network actions whose screen has closed (a published Tropfen, a closed oasis), shown by the tab shell. */
object NetworkSignals {
    /** The confirmation shown as a toast on the tab in front, then cleared. */
    val confirmation = MutableStateFlow<String?>(null)
}
