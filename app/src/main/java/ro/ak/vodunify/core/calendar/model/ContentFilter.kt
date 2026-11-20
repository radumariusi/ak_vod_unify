package ro.ak.vodunify.core.calendar.model

import kotlinx.serialization.Serializable

/** Categories the user can hide from the calendar (series and movies). */
@Serializable
enum class ContentFilter(val label: String) {
    ANIME("Anime"),
    ANIMATION("Animație (non-anime)"),
    TURKISH("Seriale turcești"),
    INDIAN("Seriale indiene"),
    KOREAN("K-drama (coreene)"),
    CHINESE("Seriale chinezești"),
    TELENOVELA("Telenovele"),
    SOAP("Soap opera"),
    REALITY("Reality show"),
    TALK_SHOW("Talk show"),
    NEWS("Știri"),
    DOCUMENTARY("Documentare"),
    KIDS("Copii"),
    TV_MOVIE("Filme TV (de televiziune)"),
    ;

    companion object {
        val defaultHidden: Set<ContentFilter> = setOf(
            ANIME, TURKISH, INDIAN, CHINESE, TELENOVELA, SOAP, REALITY, TALK_SHOW, NEWS, KIDS, TV_MOVIE,
        )
    }
}
