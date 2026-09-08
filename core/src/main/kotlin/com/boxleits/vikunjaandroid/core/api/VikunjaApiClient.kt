package com.boxleits.vikunjaandroid.core.api

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * A configured client for one instance: the API, and the connection pool
 * behind it.
 *
 * The pool is exposed because it outlives a network. OkHttp keeps sockets
 * alive for reuse and has no idea when the device loses connectivity, so after
 * a drop the pool still holds connections that look usable and are not. The
 * next request picks one, writes to it, and waits for an answer that can never
 * come until TCP gives up — which is why an app can sit there for ten or
 * fifteen seconds while a browser on the same phone loads the same host at
 * once. Browsers drop their sockets on a connectivity change; OkHttp does not
 * do it for you.
 */
class VikunjaConnection internal constructor(
    val api: VikunjaApi,
    private val client: OkHttpClient,
) {
    /** Throws away pooled sockets, so the next request opens a fresh one. */
    fun evictPooledConnections() {
        client.connectionPool.evictAll()
    }
}

/** Builds a [VikunjaApi] pointed at a user-supplied, self-hosted instance URL. */
object VikunjaApiClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /** Just the API, for callers that never need to touch the pool (the tests). */
    fun create(
        baseUrl: String,
        tokenProvider: () -> String?,
        enableLogging: Boolean = false,
    ): VikunjaApi = connect(baseUrl, tokenProvider, enableLogging).api

    fun connect(
        baseUrl: String,
        tokenProvider: () -> String?,
        enableLogging: Boolean = false,
    ): VikunjaConnection {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokenProvider))
            .apply {
                if (enableLogging) {
                    addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
                }
            }
            .build()

        val api = Retrofit.Builder()
            .baseUrl(normalize(baseUrl))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(VikunjaApi::class.java)

        return VikunjaConnection(api, client)
    }

    /** Retrofit requires a base URL ending in `/`; users tend to paste one without it. */
    private fun normalize(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }
}
