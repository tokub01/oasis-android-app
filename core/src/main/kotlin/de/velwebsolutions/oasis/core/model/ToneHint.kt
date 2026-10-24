package de.velwebsolutions.oasis.core.model

import kotlinx.serialization.Serializable

/**
 * The tone check of a text before it is published (`POST network/tone`, App\Support\Moderation\ToneHint): a gentle hint
 * and whether to ask "Nochmal lesen?" before sending.
 */
@Serializable
data class ToneHint(val hint: String? = null, val nudge: Boolean = false)
