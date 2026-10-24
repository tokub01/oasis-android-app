package de.velwebsolutions.oasis.live

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Failures become results; cancellation is never mistaken for one. */
class CatchingTest {
    @Test
    fun returnsValuesAndFailuresButLetsCancellationThrough() = runTest {
        assertEquals(3, catching { 3 }.getOrNull())
        assertTrue(catching { error("kaputt") }.exceptionOrNull() is IllegalStateException)
        val cancelled = runCatching { catching { throw CancellationException("weg") } }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)
    }

    @Test
    fun tellsTwoNoticesWithTheSameTextApart() {
        val first = ChatToast("Neue Chat-Nachricht", null)
        val second = ChatToast("Neue Chat-Nachricht", null)
        assertTrue(first.id != second.id)
        assertTrue(first != second)
    }
}
