package ro.ak.vodunify.core.prefs

/** Limba în care se afișează titlurile și descrierile. */
enum class ContentLanguage(val label: String) {
    /** Titluri și descrieri traduse în română (unde TMDb are traducere). */
    ROMANIAN("Română"),

    /** Titluri și descrieri în engleză. */
    ENGLISH("Engleză"),

    /** Titlul original al producției, descrieri în engleză. */
    ORIGINAL("Original"),
}

/** Biblioteca (țara) unui serviciu: RO direct sau US prin VPN. */
enum class Market(val code: String, val label: String) {
    RO("RO", "România"),
    US("US", "SUA"),
}

data class CatalogSettings(
    val language: ContentLanguage = ContentLanguage.ROMANIAN,
    /** Nefolosit în interfață: fiecare serviciu are biblioteca lui (Setări → Aplicații și servicii). */
    val market: Market = Market.RO,
) {
    /** Parametrul `language` trimis la TMDb. */
    val apiLanguage: String get() = if (language == ContentLanguage.ROMANIAN) "ro-RO" else "en-US"

    /** Limba preferată pentru sigla titlului (logo-ul TMDb). */
    val logoLanguage: String get() = if (language == ContentLanguage.ROMANIAN) "ro" else "en"
}
