package com.boxleits.vikunjaandroid.core.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.IOException

class RetryPolicyTest {

    @Test
    fun `network failures are retryable`() {
        assertThat(VikunjaSyncException.Network(IOException("offline")).isRetryable()).isTrue()
    }

    @Test
    fun `unauthorized is retryable so a fixable token does not lose the edit`() {
        assertThat(VikunjaSyncException.Unauthorized().isRetryable()).isTrue()
    }

    @Test
    fun `server errors are retryable`() {
        listOf(500, 502, 503, 504).forEach { status ->
            assertThat(VikunjaSyncException.Server(status, null).isRetryable()).isTrue()
        }
    }

    @Test
    fun `timeout and rate limiting are retryable`() {
        assertThat(VikunjaSyncException.Server(408, null).isRetryable()).isTrue()
        assertThat(VikunjaSyncException.Server(429, null).isRetryable()).isTrue()
    }

    @Test
    fun `client errors are permanent`() {
        // 404: the task is gone. 400/422: the server rejected this payload.
        // Retrying any of these unchanged just loops.
        listOf(400, 403, 404, 409, 422).forEach { status ->
            assertThat(VikunjaSyncException.Server(status, null).isRetryable()).isFalse()
        }
    }

    @Test
    fun `unexpected failures are treated as permanent`() {
        assertThat(VikunjaSyncException.Unexpected(IllegalStateException("bug")).isRetryable()).isFalse()
    }
}
