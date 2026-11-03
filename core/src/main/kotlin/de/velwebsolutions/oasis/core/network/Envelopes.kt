package de.velwebsolutions.oasis.core.network

import kotlinx.serialization.Serializable

/** `{ "data": … }` – the shape of every Laravel API resource answer. */
@Serializable
data class DataEnvelope<T>(val data: T)

/**
 * A cursor-paginated list (`->cursorPaginate()` wrapped in a resource collection):
 * `{ "data": [...], "meta": { "next_cursor": "…" | null, "per_page": 20 } }`.
 */
@Serializable
data class Page<T>(val data: List<T>, val meta: Meta? = null) {
    @Serializable
    data class Meta(
        val nextCursor: String? = null,
        val perPage: Int? = null,
        val currentPage: Int? = null,
        val lastPage: Int? = null,
        val total: Int? = null
    ) {
        /**
         * The cursor for the next request, or null on the last page. Page-number pagination maps to "page" numbers; a
         * page beyond the end (`current_page` 5 of `last_page` 1) has no next one either.
         */
        val next: String?
            get() {
                nextCursor?.let { return it }
                val current = currentPage
                val last = lastPage
                return if (current != null && last != null && current < last) (current + 1).toString() else null
            }
    }

    /** The cursor for the next request, or null on the last page ([Meta.next]). */
    val nextCursor: String? get() = meta?.next
}

/** `{ "message": "…", "data": … }` – the answer of an action that changed something. */
@Serializable
data class Confirmed<T>(val data: T, val message: String? = null)

/** `{ "message": "…" }` answers (e.g. neutral password reset). */
@Serializable
data class MessageResponse(val message: String)
