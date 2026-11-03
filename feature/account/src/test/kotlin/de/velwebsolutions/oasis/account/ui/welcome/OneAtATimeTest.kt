package de.velwebsolutions.oasis.account.ui.welcome

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The welcome tour's buttons run one action at a time: a tap while one runs is ignored, the next after it counts. */
class OneAtATimeTest {
    @Test
    fun aTapWhileTheActionRunsIsIgnored() = runTest {
        val once = OneAtATime(this)
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        once.run {
            runs += 1
            gate.await()
        }
        runCurrent()
        once.run { runs += 1 }
        gate.complete(Unit)
        runCurrent()
        once.run { runs += 1 }
        runCurrent()
        assertEquals(2, runs)
    }
}
