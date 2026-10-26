package de.velwebsolutions.oasis.core.model

import kotlinx.serialization.Serializable

/** The signed-in member (`App\Http\Resources\Api\V1\MeResource`). */
@Serializable
data class Me(
    val id: Int,
    val username: String,
    val displayName: String,
    val initials: String,
    val email: String,
    val emailVerified: Boolean = true,
    val role: String = "member",
    val isStaff: Boolean = false,
    val isAdmin: Boolean = false,
    val isSuperAdmin: Boolean = false,
    val isSuspended: Boolean = false,
    val canParticipate: Boolean = true,
    /** False until a membership is paid or gifted; null from servers before the members-only change (then true). */
    val hasPaidAccess: Boolean? = true,
    val twoFactorEnabled: Boolean = false,
    val onboarded: Boolean = true,
    val membershipTier: String? = null,
    val isFoundingMember: Boolean = false
) {
    /** Whether the member may use Oasis; without a membership the app only offers "Mitglied werden". */
    val mayUseOasis: Boolean
        get() = hasPaidAccess ?: true
}
