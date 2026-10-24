package de.velwebsolutions.oasis.core.model

import kotlinx.serialization.Serializable

/**
 * The named text of a push ("Jonas Weber" · "Hat auf deinen Tropfen geantwortet."). The push service only carries a
 * generic line and a random `preview` reference; the app swaps in this text from the Oasis server (Laravel's
 * PushDelivery and PushPreviewController), so no name passes through Google or Apple.
 */
@Serializable
data class PushPreview(val title: String, val body: String) {
    companion object {
        private val Reference = Regex("[A-Za-z0-9]{40}")

        /** The reference in a push's data: 40 ASCII letters and digits, anything else is ignored. */
        fun reference(data: Map<String, String>): String? = data["preview"]?.takeIf { Reference.matches(it) }
    }
}
