package ro.ak.vodunify.core.playback

import ro.ak.vodunify.core.catalog.MarketAvailability
import ro.ak.vodunify.core.catalog.ProviderInfo
import ro.ak.vodunify.core.prefs.Market

/** Un serviciu pe care e disponibil titlul, în biblioteca (piața) în care folosești serviciul. */
data class ServiceOption(
    val provider: ProviderInfo,
    val service: StreamingService,
    /** Aplicația care se deschide (preferința din Setări sau aplicația oficială). */
    val app: TargetApp?,
    /** Aplicația serviciului (sau playerul ales pentru el) e instalată: serviciul e „al tău”. */
    val installed: Boolean,
    /** True dacă în Setări ai ales un player în locul aplicației oficiale. */
    val usesPreferredPlayer: Boolean,
    /** Biblioteca în care e titlul: RO (direct) sau US (prin VPN). */
    val market: Market,
) {
    /** Ex. „Netflix (RO)”. */
    val label: String get() = "${service.displayName} (${market.code})"
}

data class WatchOptions(
    /** Serviciile tale (instalate) pe care e titlul, fiecare în biblioteca lui. */
    val services: List<ServiceOption>,
    /**
     * Doar când nu e pe niciun serviciu al tău: serviciile (neinstalate) pe care se găsește, tot în biblioteca
     * setată pentru fiecare. Altfel goală: nu-ți arătăm servicii pe care nu le ai, dacă îl ai deja pe unul.
     */
    val elsewhere: List<ServiceOption>,
    /** Playerele instalate (tip Stremio și IPTV), cu playerul standard primul. */
    val players: List<TargetApp>,
    val missingPlayers: List<TargetApp>,
    /** Ce se deschide direct la „Vezi”: primul serviciu al tău, altfel playerul standard. */
    val recommendedService: ServiceOption?,
    val recommendedPlayer: TargetApp?,
) {
    val onYourServices: Boolean get() = services.isNotEmpty()
}

object WatchOptionsBuilder {
    /**
     * Fiecare serviciu cunoscut se verifică doar în biblioteca lui (Setări → Aplicații și servicii: RO sau US),
     * folosind disponibilitatea TMDb/JustWatch din acea țară. Contează doar abonamentul și gratuitul
     * (fără închiriere / cumpărare) și doar serviciile din registru (cele pe care le poți deschide).
     */
    fun build(
        availability: List<MarketAvailability>,
        settings: PlaybackSettings,
        installed: Set<TargetApp>,
    ): WatchOptions {
        val all = available(availability, settings, installed)
        val mine = all.filter { it.installed }
        val standard = settings.defaultPlayerApp?.takeIf { it.isPlayer && it in installed }
        val players = TargetApp.players.filter { it in installed }.sortedBy { it != standard }
        val recommended = mine.firstOrNull { it.app != null }
        return WatchOptions(
            services = mine,
            elsewhere = if (mine.isEmpty()) all else emptyList(),
            players = players,
            missingPlayers = TargetApp.players.filter { it !in installed },
            recommendedService = recommended,
            recommendedPlayer = if (recommended == null) players.firstOrNull() else null,
        )
    }

    /** Toate serviciile din registru pe care e titlul, în biblioteca setată pentru fiecare (instalate primele). */
    fun available(
        availability: List<MarketAvailability>,
        settings: PlaybackSettings,
        installed: Set<TargetApp>,
    ): List<ServiceOption> {
        val seen = mutableSetOf<StreamingService>()
        val out = mutableListOf<ServiceOption>()
        for (m in availability) {
            for (p in m.flatrate + m.free) {
                val service = StreamingService.fromProvider(p.id, p.name) ?: continue
                // Doar din biblioteca în care folosești serviciul; alte țări nu contează.
                if (settings.marketOf(service) != m.market || !seen.add(service)) continue
                val official = TargetApp.byId(service.appId)
                val preferred = settings.preferredApp(service)
                // Dacă playerul preferat nu e instalat, revenim la aplicația oficială.
                val effective = if (preferred != official && preferred !in installed) official else preferred
                out += ServiceOption(
                    provider = p,
                    service = service,
                    app = effective,
                    installed = effective != null && effective in installed,
                    usesPreferredPlayer = effective != null && effective != official,
                    market = m.market,
                )
            }
        }
        return out.sortedBy { !it.installed }
    }
}
