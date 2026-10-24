package de.velwebsolutions.oasis.core.network

import de.velwebsolutions.oasis.core.model.PushPreview
import de.velwebsolutions.oasis.core.model.ToneHint
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

@Serializable
internal data class ToneBody(val text: String, val field: String)

internal interface CommonService {
    @POST("network/tone")
    suspend fun tone(@Body body: ToneBody): DataEnvelope<ToneHint>

    @GET("push/previews/{reference}")
    suspend fun pushPreview(@Path("reference") reference: String): DataEnvelope<PushPreview>
}

/** Calls several areas share: the tone check before publishing and the named text of a push. */
interface CommonApi {
    /** Checks `text` for the place it will appear (`post`, `profile`, …). */
    suspend fun toneHint(text: String, field: String): ToneHint

    /** The named text of a push; null on any error or after the server forgot it, so the generic line stays. */
    suspend fun pushPreview(reference: String): PushPreview?
}

class RemoteCommonApi(client: ApiClient) : CommonApi {
    private val service = client.create<CommonService>()

    override suspend fun toneHint(text: String, field: String) = service.tone(ToneBody(text, field)).data

    override suspend fun pushPreview(reference: String) = runCatching { service.pushPreview(reference).data }.getOrNull()
}
