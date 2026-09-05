package com.boxleits.vikunjaandroid.core.api

import okhttp3.Interceptor
import okhttp3.Response

/** Attaches the current personal API token, re-read on every request so a token refresh takes effect immediately. */
class AuthInterceptor(private val tokenProvider: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider()
        val request = chain.request()
        val authenticated = if (token.isNullOrBlank()) {
            request
        } else {
            request.newBuilder().header("Authorization", "Bearer $token").build()
        }
        return chain.proceed(authenticated)
    }
}
