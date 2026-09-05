package com.boxleits.vikunjaandroid.data.settings

/** User-supplied connection details for a self-hosted Vikunja instance. */
data class VikunjaSettings(
    val baseUrl: String,
    val apiToken: String,
)
