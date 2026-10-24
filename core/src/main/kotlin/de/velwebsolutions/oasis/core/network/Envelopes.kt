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
    )

    /** The cursor for the next request, or null on the last page. Page-number pagination maps to "page" numbers. */
    val nextCursor: String?
        get() {
            meta?.nextCursor?.let { return it }
            val current = meta?.currentPage
            val last = meta?.lastPage
            return if (current != null && last != null && current < last) (current + 1).toString() else null
        }
}

/** `{ "message": "…", "data": … }` – the answer of an action that changed something. */
@Serializable
data class Confirmed<T>(val data: T, val message: String? = null)

/** `{ "message": "…" }` answers (e.g. neutral password reset). */
@Serializable
data class MessageResponse(val message: String)
