package de.velwebsolutions.oasis.network.ui

import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import kotlinx.coroutines.CancellationException

/**
 * Runs [block] and returns its value, or its failure as [ApiError]. A cancellation (the screen closed, a newer search)
 * is no failure: it ends the caller as well.
 */
suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(ApiError.from(error))
}

/** The failure of an [attempt]; it only fails with an [ApiError]. */
val Result<*>.apiError: ApiError? get() = exceptionOrNull() as ApiError?

/** An [attempt] as what a page shows: the loaded value or the error with "Noch einmal laden". */
fun <T> Result<T>.loadable(): Loadable<T> = fold({ Loadable.Loaded(it) }, { Loadable.Failed(it as ApiError) })
