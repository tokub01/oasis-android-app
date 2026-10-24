package de.velwebsolutions.oasis.core.network

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.IOException
import retrofit2.Call
import retrofit2.CallAdapter
import retrofit2.Callback
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** How keys are translated between Kotlin and the API. */
enum class KeyCoding {
    /** camelCase in Kotlin ⇄ snake_case on the wire (default for all Laravel resources). */
    SnakeCase,

    /** Keys exactly as declared (chat crypto envelopes, which are camelCase on the wire and signed byte for byte). */
    Verbatim
}

/** Where the client talks to and how it introduces itself. */
data class ApiConfig(
    /** The server root, e.g. `http://10.0.2.2` in the emulator (the client appends `/api/v1/`). */
    val baseUrl: String,
    /** `OasisApp/<version> (Android; Android <release>)` – Laravel's DeviceLabel shows it as "Oasis-App auf Android". */
    val userAgent: String,
    /** The staging gate's password, sent as `X-Oasis-Gate` to the server only (Laravel's StagingGate). */
    val gateKey: String? = null
)

/** Supplies the bearer token and hears about a 401, so the session can sign out once, wherever it happened. */
interface TokenProvider {
    fun currentToken(): String?

    fun onUnauthenticated()
}

/**
 * The Oasis API client: adds the bearer token, JSON headers and the app's user agent, decodes answers and maps HTTP
 * errors to [ApiError] (also from Retrofit service calls). Feature modules create their Retrofit services with
 * [create]; images and invoices that need the token go through [okHttp] or [download].
 */
class ApiClient(
    val config: ApiConfig,
    private val tokens: TokenProvider,
    baseOkHttp: OkHttpClient = OkHttpClient()
) {
    val baseUrl: HttpUrl = config.baseUrl.trimEnd('/').toHttpUrl()
    private val apiRoot: HttpUrl = baseUrl.newBuilder().addPathSegments("api/v1/").build()

    /** OkHttp with the Oasis headers; also used by the image loader so members-only images load with the token. */
    val okHttp: OkHttpClient = baseOkHttp.newBuilder()
        .apply { interceptors().add(0, HeaderInterceptor()) }
        .build()

    private val snakeRetrofit = retrofit(OasisJson.snake)
    private val verbatimRetrofit = retrofit(OasisJson.verbatim)

    private fun retrofit(json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(apiRoot)
        .client(okHttp)
        .addCallAdapterFactory(ApiErrorCallAdapterFactory)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    /** A Retrofit service of a feature module; paths are relative to `/api/v1/`. */
    fun <T> create(service: Class<T>, keyCoding: KeyCoding): T =
        (if (keyCoding == KeyCoding.Verbatim) verbatimRetrofit else snakeRetrofit).create(service)

    inline fun <reified T> create(keyCoding: KeyCoding = KeyCoding.SnakeCase): T = create(T::class.java, keyCoding)

    /** The absolute URL of a path below `/api/v1/`. */
    fun url(path: String): HttpUrl = apiRoot.resolve(path.trimStart('/')) ?: apiRoot

    /** Resolves a URL the API returned (absolute, or relative to the server root like `/tropfen/1/bild/2`). */
    fun absoluteUrl(raw: String): HttpUrl? = raw.toHttpUrlOrNullSafe() ?: baseUrl.resolve(raw)

    /** Fetches binary content (images that need the token, invoices) from an absolute or root-relative URL. */
    suspend fun download(raw: String): ByteArray {
        val url = absoluteUrl(raw) ?: throw ApiError.Transport("Ungültige Adresse")
        val request = Request.Builder().url(url).header("Accept", "*/*").build()
        val response = okHttp.newCall(request).await()
        response.use {
            if (!it.isSuccessful) throw errorFor(it.code, it.body.string(), it.header("Retry-After"))
            return it.body.bytes()
        }
    }

    /** Adds the headers to every request (the gate key only for the Oasis host) and reports 401s. */
    private inner class HeaderInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val builder = original.newBuilder()
            if (original.header("Accept") == null) builder.header("Accept", "application/json")
            builder.header("Accept-Language", "de")
            builder.header("User-Agent", config.userAgent)
            tokens.currentToken()?.let { builder.header("Authorization", "Bearer $it") }
            val gateKey = config.gateKey
            if (!gateKey.isNullOrEmpty() && original.url.host == baseUrl.host) builder.header("X-Oasis-Gate", gateKey)
            val response = chain.proceed(builder.build())
            if (response.code == 401) tokens.onUnauthenticated()
            return response
        }
    }

    companion object {
        /** Maps an error answer to [ApiError] (Laravel JSON: `{ "message": "…", "errors": { field: [..] } }`). */
        fun errorFor(status: Int, body: String?, retryAfter: String?): ApiError {
            val parsed = runCatching { body?.let { OasisJson.verbatim.parseToJsonElement(it).jsonObject } }.getOrNull()
            val message = parsed?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty()
            return when (status) {
                401 -> ApiError.Unauthenticated
                403 -> ApiError.Forbidden(message)
                404 -> ApiError.NotFound
                409 -> ApiError.Conflict(message)
                413 -> ApiError.PayloadTooLarge
                422 -> ApiError.Validation(message, fieldErrors(parsed))
                429 -> ApiError.TooManyRequests(retryAfter?.toIntOrNull())
                else -> ApiError.Server(status, message.ifEmpty { null })
            }
        }

        private fun fieldErrors(body: JsonObject?): Map<String, List<String>> {
            val errors = runCatching { body?.get("errors")?.jsonObject }.getOrNull() ?: return emptyMap()
            return errors.mapValues { (_, value) ->
                runCatching { value.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrDefault(emptyList())
            }
        }

        /** Any failure of a call as [ApiError]. */
        fun map(error: Throwable): ApiError = when (error) {
            is ApiError -> error
            is SerializationException -> ApiError.Decoding(error.message ?: "Unerwartete Antwort")
            is IllegalArgumentException -> ApiError.Decoding(error.message ?: "Unerwartete Antwort")
            else -> ApiError.Transport(error.message ?: error.toString())
        }
    }
}

private fun String.toHttpUrlOrNullSafe(): HttpUrl? =
    if (startsWith("http://") || startsWith("https://")) runCatching { toHttpUrl() }.getOrNull() else null

private suspend fun okhttp3.Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(
        object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                continuation.resumeWithException(ApiError.Transport(e.message ?: "Keine Verbindung"))
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                continuation.resume(response)
            }
        }
    )
    continuation.invokeOnCancellation { cancel() }
}

/**
 * Lets `suspend` Retrofit functions throw [ApiError] instead of `HttpException`/`IOException`, so every repository
 * sees the same errors as on iOS.
 */
internal object ApiErrorCallAdapterFactory : CallAdapter.Factory() {
    override fun get(returnType: Type, annotations: Array<out Annotation>, retrofit: Retrofit): CallAdapter<*, *>? {
        if (getRawType(returnType) != Call::class.java) return null
        // Kotlin has no raw types, so a Call return type always carries its body type.
        val responseType = getParameterUpperBound(0, returnType as ParameterizedType)
        return object : CallAdapter<Any, Call<Any>> {
            override fun responseType(): Type = responseType

            override fun adapt(call: Call<Any>): Call<Any> = ApiErrorCall(call)
        }
    }
}

private class ApiErrorCall(private val delegate: Call<Any>) : Call<Any> by delegate {
    override fun enqueue(callback: Callback<Any>) {
        delegate.enqueue(
            object : Callback<Any> {
                override fun onResponse(call: Call<Any>, response: retrofit2.Response<Any>) {
                    if (response.isSuccessful) {
                        callback.onResponse(this@ApiErrorCall, response)
                    } else {
                        // Retrofit gives every non-2xx response an error body.
                        val error = ApiClient.errorFor(response.code(), response.errorBody()!!.string(), response.headers()["Retry-After"])
                        callback.onFailure(this@ApiErrorCall, error)
                    }
                }

                override fun onFailure(call: Call<Any>, t: Throwable) {
                    callback.onFailure(this@ApiErrorCall, ApiClient.map(t))
                }
            }
        )
    }

    override fun clone(): Call<Any> = ApiErrorCall(delegate.clone())
}

/**
 * Where the realtime socket goes when the server's announced Reverb host is not reachable from this device: the
 * emulator reaches the Mac only as `10.0.2.2`, while the local server announces its Bonjour name. Null keeps the host.
 */
data class RealtimeConfig(val hostOverride: String? = null)
