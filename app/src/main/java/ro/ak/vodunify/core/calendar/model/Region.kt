package ro.ak.vodunify.core.calendar.model

import kotlinx.serialization.Serializable

/** Catalog region a streaming app is used with (natively or via VPN). */
@Serializable
enum class Region(val code: String, val label: String) {
    RO("RO", "România"),
    US("US", "SUA");

    fun toggled(): Region = if (this == RO) US else RO

    companion object {
        fun fromCode(code: String): Region? = entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}
