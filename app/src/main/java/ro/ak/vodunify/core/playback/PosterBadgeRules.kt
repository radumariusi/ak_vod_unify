package ro.ak.vodunify.core.playback

import ro.ak.vodunify.core.prefs.Market

enum class PosterStage(val label: String) {
    IN_PROGRESS("În curs"),
    UNWATCHED("Nevăzute"),
    WATCHED("Văzute"),
}

enum class PosterBadgeMode(val label: String) {
    FIRST("O singură iconiță"),
    ALL("Toate platformele de streaming"),
    ALL_WITH_LAST("Toate platformele + ultima aplicație"),
    NONE("Fără iconițe"),
}

enum class BadgePriority(val label: String) {
    LAST_APP("Ultima aplicație folosită"),
    STREAMING_RO("Streaming din biblioteca RO"),
    STREAMING_US("Streaming din biblioteca US"),
    DEFAULT_PLAYER("Playerul standard"),
}

data class PosterBadgePolicy(val mode: PosterBadgeMode, val priorities: List<BadgePriority>) {
    companion object {
        fun default(stage: PosterStage) = PosterBadgePolicy(
            if (stage == PosterStage.UNWATCHED) PosterBadgeMode.ALL else PosterBadgeMode.ALL_WITH_LAST,
            BadgePriority.entries.filter { it != BadgePriority.LAST_APP || stage != PosterStage.UNWATCHED },
        )
    }
}

data class PosterBadgeSettings(
    val policies: Map<PosterStage, PosterBadgePolicy> = emptyMap(),
    val serviceOrder: List<String> = defaultServiceOrder,
) {
    fun policy(stage: PosterStage) = policies[stage] ?: PosterBadgePolicy.default(stage)

    companion object {
        val defaultServiceOrder = listOf("netflix", "disney", "hbomax", "prime", "skyshowtime")
    }
}

data class PosterActivity(
    val stage: PosterStage,
    val lastPlaybackPackage: String?,
    val lastWatchedPackage: String?,
    val watchedEpisodes: Int? = null,
    val hasActiveResume: Boolean = false,
)

data class PosterBadge(val packageName: String?, val label: String, val serviceId: String? = null)

data class ServiceBadge(val badge: PosterBadge, val market: Market) {
    val serviceId: String? get() = badge.serviceId
}

object PosterBadgeRules {
    fun select(
        policy: PosterBadgePolicy,
        serviceOrder: List<String>,
        services: List<ServiceBadge>,
        last: PosterBadge?,
        player: PosterBadge?,
    ): List<PosterBadge> {
        if (policy.mode == PosterBadgeMode.NONE) return emptyList()
        val fallbackPlayer = player.takeIf { services.isEmpty() }
        val sorted = services.sortedBy { serviceOrder.indexOf(it.serviceId).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
        val groups = policy.priorities.map { priority ->
            when (priority) {
                BadgePriority.LAST_APP -> listOfNotNull(last)
                BadgePriority.STREAMING_RO -> sorted.filter { it.market == Market.RO }.map { it.badge }
                BadgePriority.STREAMING_US -> sorted.filter { it.market == Market.US }.map { it.badge }
                BadgePriority.DEFAULT_PLAYER -> listOfNotNull(fallbackPlayer)
            }
        }
        if (policy.mode == PosterBadgeMode.FIRST) return listOfNotNull(groups.firstNotNullOfOrNull { it.firstOrNull() })
        val showLast = policy.mode == PosterBadgeMode.ALL_WITH_LAST
        val all = policy.priorities.zip(groups).filter { (priority, _) ->
            priority == BadgePriority.STREAMING_RO || priority == BadgePriority.STREAMING_US ||
                (priority == BadgePriority.LAST_APP && showLast)
        }.flatMap { it.second }
        return all.ifEmpty { listOfNotNull(fallbackPlayer) }.distinctBy { it.serviceId ?: it.packageName }
    }
}
