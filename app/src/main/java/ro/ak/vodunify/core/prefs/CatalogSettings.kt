package ro.ak.vodunify.core.prefs

/** Limba în care se afișează titlurile și descrierile. */
enum class ContentLanguage(val label: String) {
    /** Titluri și descrieri traduse în română (unde TMDb are traducere). */
    ROMANIAN("Română"),

    /** Titlul original al producției, descrieri în engleză. */
    ORIGINAL("Original"),
}

/** Piața pentru disponibilitate și cataloagele pe servicii. */
enum class Market(val code: String, val label: String) {
    RO("RO", "România"),
    US("US", "SUA"),
}

data class CatalogSettings(
    val language: ContentLanguage = ContentLanguage.ROMANIAN,
    val market: Market = Market.RO,
) {
    /** Parametrul `language` trimis la TMDb. */
    val apiLanguage: String get() = if (language == ContentLanguage.ROMANIAN) "ro-RO" else "en-US"
}
