package de.velwebsolutions.oasis.network.api

import de.velwebsolutions.oasis.core.network.OasisInstant
import kotlinx.serialization.Serializable

/** A Sammlung of a crowd (docs/mildcrowd-network.md, „Sammlungen“): its Post count and what the viewer may do. */
@Serializable
data class OasisCollection(
    val id: Int,
    val name: String,
    val description: String? = null,
    val postsCount: Int = 0,
    val creator: Member? = null,
    val canEdit: Boolean = false,
    val canAdd: Boolean = false
)

/** The Sammlungen of a crowd (alphabetical) and who may create one. */
@Serializable
data class OasisCollections(
    val canCreate: Boolean = false,
    val membersCreateCollections: Boolean = false,
    val collections: List<OasisCollection> = emptyList()
)

/** What a new or renamed collection sends. */
@Serializable
data class CollectionInput(val name: String = "", val description: String = "")

/** „Mitglieder dürfen Sammlungen anlegen“ as stored. */
@Serializable
data class CollectionSettings(val membersCreateCollections: Boolean)

@Serializable
internal data class CollectPostBody(val postId: Int)

/**
 * An invitation to someone else's gemeinsame Zeitkapsel (docs/mildcrowd-network.md, „Gemeinsame Zeitkapseln“): from whom,
 * where and when it opens, the deadline, where the own text stands and – while [canEdit] – the own text.
 */
@Serializable
data class CapsuleInvitation(
    val id: Int,
    val from: Member,
    val oasis: NamedOasis? = null,
    val opensAt: OasisInstant,
    val contributeUntil: OasisInstant? = null,
    /** `invited`, `contributed`, `declined`, `published` or `failed`. */
    val status: String = "invited",
    val body: String? = null,
    val canEdit: Boolean = false,
    val postId: Int? = null,
    val failedReason: String? = null
)

@Serializable
internal data class ContributionBody(val body: String)
