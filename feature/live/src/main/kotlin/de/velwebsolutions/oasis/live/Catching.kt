package de.velwebsolutions.oasis.live

import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs [block] and returns its failure as the result. Unlike `runCatching`, cancellation passes through, so a screen
 * that goes away stops its requests instead of showing an error.
 */
internal suspend fun <T> catching(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
