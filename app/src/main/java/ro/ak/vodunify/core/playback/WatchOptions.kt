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

/** Un serviciu pe care e disponibil titlul, în biblioteca (piața) în care îl folosești. */
data class ServiceOption(
    val provider: ProviderInfo,
    val offer: OfferType,
    /** Null = serviciu fără aplicație în registru: îl afișăm, dar nu-l putem deschide. */
    val service: StreamingService?,
    /** Aplicația care se deschide (preferința din Setări sau aplicația oficială). */
    val app: TargetApp?,
    val installed: Boolean,
    /** True dacă în Setări ai ales un player în locul aplicației oficiale. */
    val usesPreferredPlayer: Boolean,
    /** Biblioteca în care e titlul: RO (direct) sau US (prin VPN). */
    val market: Market,
)

sealed interface MarketWarning {
    /** E pe servicii cu abonament, dar niciunul nu e în bibliotecile tale (ex. HBO Max doar în US, iar tu îl ai pe RO). */
    data class NotInYourLibraries(val services: List<String>) : MarketWarning

    /** Nu apare la niciun serviciu cu abonament sau gratuit, nici în RO, nici în US. */
    data object NowhereStreaming : MarketWarning
}

data class WatchOptions(
    val market: Market,
    val services: List<ServiceOption>,
    /** Playerele instalate (tip Stremio și IPTV), cu playerul standard primul. */
    val players: List<TargetApp>,
    val missingPlayers: List<TargetApp>,
    val warning: MarketWarning?,
    /** Ce se deschide la „Vezi acum”: serviciul instalat, altfel playerul standard. */
    val recommendedService: ServiceOption?,
    val recommendedPlayer: TargetApp?,
)

object WatchOptionsBuilder {
    /**
     * Fiecare serviciu cunoscut se verifică în biblioteca lui (Setări → Aplicații și servicii: RO sau US),
     * folosind disponibilitatea TMDb/JustWatch din acea țară. Serviciile necunoscute apar din piața principală.
     */
    fun build(
        availability: List<MarketAvailability>,
        settings: PlaybackSettings,
        installed: Set<TargetApp>,
    ): WatchOptions {
        val primary = settings.playbackMarket
        val markets = listOf(primary) + Market.entries.filter { it != primary }
        val seenServices = mutableSetOf<StreamingService>()
        val seenProviders = mutableSetOf<Int>()
        val services = markets.flatMap { m ->
            availability.firstOrNull { it.market == m }
                ?.let { offers(it, settings, installed, primary, seenServices, seenProviders) }
                .orEmpty()
        }.sortedWith(
            compareBy({ it.offer.ordinal >= OfferType.RENT.ordinal }, { !it.installed }, { it.service == null }),
        )

        val streaming = services.any { it.offer == OfferType.SUBSCRIPTION || it.offer == OfferType.FREE }
        val warning = when {
            streaming -> null
            else -> {
                val elsewhere = availability.flatMap { it.flatrate + it.free }.map { it.name }.distinct()
                if (elsewhere.isNotEmpty()) MarketWarning.NotInYourLibraries(elsewhere) else MarketWarning.NowhereStreaming
            }
        }

        val standard = settings.defaultPlayerApp?.takeIf { it.isPlayer && it in installed }
        val players = TargetApp.players.filter { it in installed }.sortedBy { it != standard }
        val recommendedService = services.firstOrNull {
            it.installed && it.app != null && (it.offer == OfferType.SUBSCRIPTION || it.offer == OfferType.FREE)
        }
        return WatchOptions(
            market = primary,
            services = services,
            players = players,
            missingPlayers = TargetApp.players.filter { it !in installed },
            warning = warning,
            recommendedService = recommendedService,
            recommendedPlayer = if (recommendedService == null) players.firstOrNull() else null,
        )
    }

    private fun offers(
        m: MarketAvailability,
        settings: PlaybackSettings,
        installed: Set<TargetApp>,
        primary: Market,
        seenServices: MutableSet<StreamingService>,
        seenProviders: MutableSet<Int>,
    ): List<ServiceOption> {
        val out = mutableListOf<ServiceOption>()
        val groups = listOf(OfferType.SUBSCRIPTION to m.flatrate, OfferType.FREE to m.free, OfferType.RENT to m.rent, OfferType.BUY to m.buy)
        for ((offer, providers) in groups) {
            for (p in providers) {
                val service = StreamingService.fromProvider(p.id, p.name)
                if (service != null) {
                    // Doar din biblioteca în care folosești serviciul.
                    if (settings.marketOf(service) != m.market || !seenServices.add(service)) continue
                } else {
                    if (m.market != primary || !seenProviders.add(p.id)) continue
                }
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
                    market = m.market,
                )
            }
        }
        return out
    }
}
