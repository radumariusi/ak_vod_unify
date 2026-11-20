package ro.ak.vodunify.core.calendar.filter

import ro.ak.vodunify.core.calendar.model.ContentFilter

/**
 * Maps TMDB metadata of a show or movie to the [ContentFilter] categories it
 * belongs to. Movie and TV genre ids share the common genres (animation 16,
 * drama 18, documentary 99); 10770 is the movie genre "TV Movie".
 */
object ContentClassifier {

    private const val ANIMATION = 16
    private const val DRAMA = 18
    private const val DOCUMENTARY = 99
    private const val KIDS = 10762
    private const val NEWS = 10763
    private const val REALITY = 10764
    private const val SOAP = 10766
    private const val TALK = 10767
    private const val TV_MOVIE = 10770

    private val indianLanguages = setOf("hi", "ta", "te", "ml", "kn", "bn", "mr", "pa", "gu", "ur")
    private val chineseLanguages = setOf("zh", "cn", "yue")
    private val chineseCountries = setOf("CN", "TW", "HK")
    private val latinAmerica = setOf("MX", "CO", "BR", "AR", "VE", "CL", "PE", "EC", "UY", "PY", "BO", "DO", "CU", "PR", "GT", "CR", "PA")

    fun classify(
        originalLanguage: String,
        originCountries: List<String>,
        genreIds: Collection<Int>,
        tmdbType: String? = null,
    ): Set<ContentFilter> {
        val lang = originalLanguage.lowercase()
        val countries = originCountries.map { it.uppercase() }.toSet()
        val genres = genreIds.toSet()
        val type = tmdbType?.lowercase()
        val result = mutableSetOf<ContentFilter>()

        val isJapanese = lang == "ja" || "JP" in countries
        if (ANIMATION in genres) result += if (isJapanese) ContentFilter.ANIME else ContentFilter.ANIMATION
        if (lang == "tr" || "TR" in countries) result += ContentFilter.TURKISH
        if (lang in indianLanguages || "IN" in countries) result += ContentFilter.INDIAN
        if (lang == "ko" || "KR" in countries) result += ContentFilter.KOREAN
        if (lang in chineseLanguages || countries.any { it in chineseCountries }) result += ContentFilter.CHINESE
        if (countries.any { it in latinAmerica } && (SOAP in genres || DRAMA in genres)) result += ContentFilter.TELENOVELA
        if (SOAP in genres) result += ContentFilter.SOAP
        if (REALITY in genres || type == "reality") result += ContentFilter.REALITY
        if (TALK in genres || type == "talk show") result += ContentFilter.TALK_SHOW
        if (NEWS in genres || type == "news") result += ContentFilter.NEWS
        if (DOCUMENTARY in genres || type == "documentary") result += ContentFilter.DOCUMENTARY
        if (KIDS in genres) result += ContentFilter.KIDS
        if (TV_MOVIE in genres) result += ContentFilter.TV_MOVIE
        return result
    }
}
