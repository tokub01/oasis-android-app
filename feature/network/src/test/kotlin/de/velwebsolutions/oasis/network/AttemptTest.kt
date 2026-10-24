package de.velwebsolutions.oasis.network

import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.network.ui.apiError
import de.velwebsolutions.oasis.network.ui.attempt
import de.velwebsolutions.oasis.network.ui.loadable
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calls whose failure the screens show and whose cancellation ends them. */
class AttemptTest {
    @Test
    fun `a value, a failure as ApiError and both as a page state`() = runTest {
        val value = attempt { 7 }
        assertEquals(7, value.getOrNull())
        assertNull(value.apiError)
        assertEquals(Loadable.Loaded(7), value.loadable())

        val failure = attempt<Int> { throw IOException("offline") }
        assertTrue(failure.apiError is ApiError.Transport)
        assertTrue(failure.loadable() is Loadable.Failed)
    }

    @Test
    fun `a cancellation is no failure, it ends the caller`() = runTest {
        var reached = false
        val job = launch {
            attempt { awaitCancellation() }
            reached = true
        }
        runCurrent()
        job.cancel(CancellationException("Screen closed"))
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(false, reached)
    }
}
