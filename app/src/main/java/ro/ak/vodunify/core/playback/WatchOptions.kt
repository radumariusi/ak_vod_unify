package ro.ak.vodunify.core.playback

import ro.ak.vodunify.core.catalog.MarketAvailability
import ro.ak.vodunify.core.catalog.ProviderInfo
import ro.ak.vodunify.core.prefs.Market

enum class OfferType(val label: String) {
    SUBSCRIPTION("Abonament"),
    FREE("Gratuit"),
    RENT("Închiriere"),
    BUY("Cumpărare"),
}

/** Un serviciu oficial pe care e disponibil titlul, în piața de redare. */
data class ServiceOption(
    val provider: ProviderInfo,
    val offer: OfferType,
    /** Null = serviciu fără integrare (ex. Voyo, SkyShowtime): îl afișăm, dar nu-l putem deschide. */
    val service: StreamingService?,
    /** Aplicația care se deschide (preferința din Setări sau aplicația oficială). */
    val app: TargetApp?,
    val installed: Boolean,
    /** True dacă în Setări ai ales un player în locul aplicației oficiale. */
    val usesPreferredPlayer: Boolean,
)

sealed interface MarketWarning {
    /** Nu e în piața ta, dar e în alta (ex. doar în US). */
    data class OnlyElsewhere(val playbackMarket: Market, val otherMarket: Market, val services: List<String>) : MarketWarning

    /** Nu apare la niciun serviciu cu abonament în nicio piață. */
    data object NowhereStreaming : MarketWarning
}

data class WatchOptions(
    val market: Market,
    val services: List<ServiceOption>,
    val players: List<TargetApp>,
    val missingPlayers: List<TargetApp>,
    val warning: MarketWarning?,
    /** Ce se deschide la „Vezi acum”: serviciul preferat instalat, altfel primul player. */
    val recommendedService: ServiceOption?,
    val recommendedPlayer: TargetApp?,
)

object WatchOptionsBuilder {
    fun build(
        availability: List<MarketAvailability>,
        settings: PlaybackSettings,
        installed: Set<TargetApp>,
    ): WatchOptions {
        val market = settings.playbackMarket
        val here = availability.firstOrNull { it.market == market }
        val services = here?.let { offers(it, settings, installed) }.orEmpty()

        val streamingHere = services.any { it.offer == OfferType.SUBSCRIPTION || it.offer == OfferType.FREE }
        val warning = if (streamingHere) {
            null
        } else {
            val other = availability.firstOrNull { it.market != market && (it.flatrate.isNotEmpty() || it.free.isNotEmpty()) }
            if (other != null) {
                MarketWarning.OnlyElsewhere(market, other.market, (other.flatrate + other.free).map { it.name }.distinct())
            } else {
                MarketWarning.NowhereStreaming
            }
        }

        val players = TargetApp.players.filter { it in installed }
        val recommendedService = services.firstOrNull {
            it.installed && it.app != null && (it.offer == OfferType.SUBSCRIPTION || it.offer == OfferType.FREE)
        }
        return WatchOptions(
            market = market,
            services = services,
            players = players,
            missingPlayers = TargetApp.players.filter { it !in installed },
            warning = warning,
            recommendedService = recommendedService,
            recommendedPlayer = if (recommendedService == null) players.firstOrNull() else null,
        )
    }

    private fun offers(m: MarketAvailability, settings: PlaybackSettings, installed: Set<TargetApp>): List<ServiceOption> {
        val seenServices = mutableSetOf<StreamingService>()
        val seenProviders = mutableSetOf<Int>()
        val out = mutableListOf<ServiceOption>()
        val groups = listOf(OfferType.SUBSCRIPTION to m.flatrate, OfferType.FREE to m.free, OfferType.RENT to m.rent, OfferType.BUY to m.buy)
        for ((offer, providers) in groups) {
            for (p in providers) {
                val service = StreamingService.fromProvider(p.id, p.name)
                if (service != null && !seenServices.add(service)) continue
                if (service == null && !seenProviders.add(p.id)) continue
                val app = service?.let { settings.preferredApp(it) }
                val official = service?.let { TargetApp.byId(it.appId) }
                // Dacă playerul preferat nu e instalat, revenim la aplicația oficială.
                val effective = if (app != null && app != official && app !in installed) official else app
                out += ServiceOption(
                    provider = p,
                    offer = offer,
                    service = service,
                    app = effective,
                    installed = effective != null && effective in installed,
                    usesPreferredPlayer = effective != null && effective != official,
                )
            }
        }
        // Serviciile integrate și instalate primele, apoi ordinea TMDb (după popularitate).
        return out.sortedWith(compareBy({ it.offer.ordinal >= OfferType.RENT.ordinal }, { !it.installed }, { it.service == null }))
    }
}
