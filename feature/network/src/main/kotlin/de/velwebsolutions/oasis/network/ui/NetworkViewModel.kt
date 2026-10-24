package de.velwebsolutions.oasis.network.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.model.refreshing
import de.velwebsolutions.oasis.core.network.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The shared parts of the network view models: the status toast (confirmation and error of the last action), running
 * work that outlives a tap, and following [de.velwebsolutions.oasis.network.NetworkSignals].
 */
abstract class NetworkViewModel : ViewModel() {
    protected val mutableStatus = MutableStateFlow<String?>(null)
    protected val mutableError = MutableStateFlow<ApiError?>(null)

    /** The confirmation of the last action, shown as a toast. */
    val status: StateFlow<String?> = mutableStatus.asStateFlow()

    /** The last failed action or refresh. */
    val error: StateFlow<ApiError?> = mutableError.asStateFlow()

    fun dismissStatus() {
        mutableStatus.value = null
    }

    fun dismissError() {
        mutableError.value = null
    }

    /** Runs work in the view model's scope, so it finishes even when the screen recomposes or the user navigates. */
    fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    /** Calls [onChange] whenever a signal counter changes after this view model was created. */
    protected fun observe(signal: Flow<Int>, onChange: suspend () -> Unit) {
        viewModelScope.launch { signal.drop(1).collect { onChange() } }
    }

    /** Runs an action whose answer is the server's confirmation; an error goes to [error]. Returns whether it worked. */
    protected suspend fun confirm(action: suspend () -> String): Boolean = try {
        mutableStatus.value = action()
        mutableError.value = null
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        mutableError.value = ApiError.from(error)
        false
    }

    /**
     * Loads into a [Loadable]: a placeholder before the first answer, the loaded value stays while it refreshes, and a
     * failed refresh keeps the screen and shows the error as a toast.
     */
    protected suspend fun <T> MutableStateFlow<Loadable<T>>.load(fetch: suspend () -> T): T? {
        value = value.refreshing()
        return try {
            fetch().also { value = Loadable.Loaded(it) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (value.valueOrNull == null) value = Loadable.Failed(ApiError.from(error)) else mutableError.value = ApiError.from(error)
            null
        }
    }
}
