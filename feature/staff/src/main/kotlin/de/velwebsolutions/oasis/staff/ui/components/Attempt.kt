package de.velwebsolutions.oasis.staff.ui.components

import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.asApiError
import kotlin.coroutines.cancellation.CancellationException

/** Runs a request and hands back its answer or its failure as [ApiError]; cancellation passes through. */
internal suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error.asApiError())
}

/** The failure of an [attempt] as [ApiError]. */
internal val Result<*>.apiError: ApiError? get() = exceptionOrNull() as? ApiError
