package com.boxleits.vikunjaandroid.core.repository

sealed class VikunjaSyncException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class Unauthorized(cause: Throwable? = null) : VikunjaSyncException("Invalid or expired API token", cause)

    class Network(cause: Throwable) : VikunjaSyncException("Could not reach the Vikunja server", cause)

    /**
     * The server answered, but rejected the request. Carries the status and
     * whatever the response body said, so the UI can show something more
     * useful than "something went wrong" — otherwise diagnosing this means
     * going to the server's own logs.
     */
    class Server(
        val statusCode: Int,
        val detail: String?,
    ) : VikunjaSyncException(
        buildString {
            append("Vikunja returned HTTP ")
            append(statusCode)
            detail?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append(": ")
                append(it)
            }
        },
    )

    class Unexpected(cause: Throwable) :
        VikunjaSyncException("Unexpected error while syncing with Vikunja: ${cause.message ?: cause::class.simpleName}", cause)
}
