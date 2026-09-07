package com.boxleits.vikunjaandroid.core.repository

/**
 * Whether a failed write is worth queueing for another attempt.
 *
 * The distinction matters because a queued edit that can never succeed —
 * a task someone deleted, a payload the server refuses — would otherwise be
 * retried forever while the local copy shows a change that will never stick.
 */
fun VikunjaSyncException.isRetryable(): Boolean = when (this) {
    // Offline, DNS, timeout: exactly what the queue exists for.
    is VikunjaSyncException.Network -> true

    // The token may have been rotated or the instance may be mid-restart.
    // Worth keeping: dropping the edit would lose the user's intent over
    // something they can fix in Settings.
    is VikunjaSyncException.Unauthorized -> true

    is VikunjaSyncException.Server -> when (statusCode) {
        // 408 Request Timeout and 429 Too Many Requests are explicitly
        // "come back later"; 5xx is the server's problem, not the payload's.
        408, 429 -> true
        in 500..599 -> true
        // Any other 4xx is a statement about this request: retrying it
        // unchanged will fail identically.
        else -> false
    }

    // An unrecognised failure is not proof the write is impossible, but
    // retrying blind risks looping on a bug. Treat it as permanent so it
    // surfaces rather than hides in the queue.
    is VikunjaSyncException.Unexpected -> false
}
