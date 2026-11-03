package de.velwebsolutions.oasis.network

import androidx.lifecycle.ViewModel
import de.velwebsolutions.oasis.network.ui.retry
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** „Noch einmal versuchen“ runs in the view model's scope: the button going away with the error does not cancel the load. */
class RetryTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private class Screen : ViewModel()

    @Test
    fun aRetryGoesOnWhenItsButtonGoesAway() = runTest {
        val answer = CompletableDeferred<Unit>()
        var loaded = false
        val retry = Screen().retry {
            answer.await()
            loaded = true
        }
        val button = launch { retry() }
        runCurrent()
        // The screen shows „Lädt …“: the error view and its button leave the composition.
        button.cancel()
        answer.complete(Unit)
        runCurrent()
        assertTrue(loaded)
    }
}
