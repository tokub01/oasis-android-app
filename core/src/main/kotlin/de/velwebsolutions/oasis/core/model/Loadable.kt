package de.velwebsolutions.oasis.core.model

import de.velwebsolutions.oasis.core.network.ApiError

/** The loading state of a screen's data. */
sealed interface Loadable<out T> {
    data object Idle : Loadable<Nothing>

    data object Loading : Loadable<Nothing>

    data class Loaded<T>(val value: T) : Loadable<T>

    data class Failed(val error: ApiError) : Loadable<Nothing>

    val valueOrNull: T?
        get() = (this as? Loaded<T>)?.value

    val errorOrNull: ApiError?
        get() = (this as? Failed)?.error

    val isLoading: Boolean
        get() = this is Loading
}

/** `Loading` before the first answer; a loaded value stays on screen while it refreshes. */
fun <T> Loadable<T>.refreshing(): Loadable<T> = if (valueOrNull == null) Loadable.Loading else this

/** After a failed load: the error only while there is nothing to show yet, so a failed refresh keeps the screen. */
fun <T> Loadable<T>.failing(error: Throwable): Loadable<T> = if (valueOrNull == null) Loadable.Failed(ApiError.from(error)) else this

/** Maps a loaded value. */
inline fun <T, R> Loadable<T>.map(transform: (T) -> R): Loadable<R> = when (this) {
    is Loadable.Loaded -> Loadable.Loaded(transform(value))
    is Loadable.Failed -> this
    Loadable.Idle -> Loadable.Idle
    Loadable.Loading -> Loadable.Loading
}
