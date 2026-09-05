package com.boxleits.vikunjaandroid.core.model

/**
 * Mirrors Vikunja's integer task priority (0-5). [orgMarker] gives it the
 * Orgzly-style `[#A]`/`[#B]`/`[#C]` look in the UI.
 */
enum class Priority(val value: Int, val orgMarker: String) {
    UNSET(0, ""),
    LOW(1, "[#D]"),
    MEDIUM(2, "[#C]"),
    HIGH(3, "[#B]"),
    URGENT(4, "[#A]"),
    DO_NOW(5, "[#A]");

    companion object {
        fun fromValue(value: Int): Priority = entries.firstOrNull { it.value == value } ?: UNSET
    }
}
