package com.boxleits.vikunjaandroid.core.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LabelDto(
    val id: Long,
    val title: String = "",
    @SerialName("hex_color") val hexColor: String? = null,
)
