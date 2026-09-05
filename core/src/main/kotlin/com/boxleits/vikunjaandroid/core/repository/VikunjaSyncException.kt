package com.boxleits.vikunjaandroid.core.repository

sealed class VikunjaSyncException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unauthorized(cause: Throwable? = null) : VikunjaSyncException("Invalid or expired API token", cause)
    class Network(cause: Throwable) : VikunjaSyncException("Could not reach the Vikunja server", cause)
    class Unexpected(cause: Throwable) : VikunjaSyncException("Unexpected error while syncing with Vikunja", cause)
}
