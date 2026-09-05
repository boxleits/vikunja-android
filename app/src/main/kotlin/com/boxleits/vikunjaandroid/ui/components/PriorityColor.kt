package com.boxleits.vikunjaandroid.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.boxleits.vikunjaandroid.core.model.Priority

@Composable
fun priorityColor(priority: Priority): Color = when (priority) {
    Priority.DO_NOW, Priority.URGENT -> MaterialTheme.colorScheme.error
    Priority.HIGH -> Color(0xFFEF6C00)
    Priority.MEDIUM -> Color(0xFFF9A825)
    Priority.LOW, Priority.UNSET -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Parses a Vikunja label's hex color (with or without a leading '#'); null on anything unparsable. */
fun parseHexColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    val cleaned = hex.removePrefix("#")
    return try {
        when (cleaned.length) {
            6 -> Color(0xFF000000 or cleaned.toLong(16))
            8 -> Color(cleaned.toLong(16))
            else -> null
        }
    } catch (e: NumberFormatException) {
        null
    }
}

fun readableTextColor(background: Color): Color =
    if (background.luminance() > 0.5f) Color.Black else Color.White
