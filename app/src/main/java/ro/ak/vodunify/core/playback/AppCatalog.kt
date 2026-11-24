package ro.ak.vodunify.core.playback

import ro.ak.vodunify.core.prefs.Market
import java.net.URLEncoder

/**
 * Serviciu de streaming (abonament sau gratuit), recunoscut din TMDb Watch Providers (date JustWatch,
 * separat pentru RO și US). [defaultMarket] = biblioteca în care îl folosești implicit (RO direct sau
 * US prin VPN); se poate schimba din Setări → Aplicații și servicii.
 */
enum class StreamingService(
    val id: String,
    private val baseName: String,
    /** Id-urile TMDb ale furnizorului (inclusiv variantele „cu reclame”). */
    private val baseProviderIds: Set<Int>,
    val appId: String,
    val defaultMarket: Market,
    val note: String? = null,
) {
    NETFLIX("netflix", "Netflix", setOf(8, 175, 1796), "netflix", Market.RO),
    PRIME_VIDEO("prime", "Prime Video", setOf(9, 10, 119, 2100), "primevideo", Market.RO),
    DISNEY_PLUS("disney", "Disney+", setOf(337, 390), "disneyplus", Market.RO),
    HBO_MAX("hbomax", "HBO Max", setOf(384, 1899, 1825), "hbomax", Market.RO),
    SKYSHOWTIME("skyshowtime", "SkyShowtime", setOf(1773), "skyshowtime", Market.RO),
    APPLE_TV("appletv", "Apple TV", setOf(350), "appletv", Market.RO),
    VOYO("voyo", "VOYO", setOf(1859), "voyo", Market.RO),
    PEACOCK("peacock", "Peacock", setOf(386, 387), "peacock", Market.US),
    TUBI("tubi", "Tubi", setOf(73), "tubi", Market.US),
    PLUTO("pluto", "Pluto TV", setOf(300), "pluto", Market.US),
    PLEX("plex", "Plex", setOf(538), "plex", Market.US),
    XUMO("xumo", "Xumo Play", emptySet(), "xumo", Market.US),
    FAWESOME("fawesome", "Fawesome", emptySet(), "fawesome", Market.US),
    FANDANGO("fandango", "Fandango at Home", setOf(7), "fandango", Market.US),
    PBS("pbs", "PBS", emptySet(), "pbs", Market.US),
    SLING("sling", "Sling TV", setOf(299), "sling", Market.US),
    ARTE("arte", "ARTE", emptySet(), "arte", Market.RO),
    MAGELLAN("magellan", "MagellanTV", emptySet(), "magellan", Market.US, "documentare"),
    CURIOSITY("curiosity", "CuriosityStream", setOf(190), "curiosity", Market.US, "documentare"),

    // Locuri pentru serviciile adăugate de tine (Setări → Aplicații și servicii), cu furnizorul TMDb ales.
    CUSTOM_1("custom1", "Serviciu adăugat 1", emptySet(), "customservice1", Market.RO),
    CUSTOM_2("custom2", "Serviciu adăugat 2", emptySet(), "customservice2", Market.RO),
    CUSTOM_3("custom3", "Serviciu adăugat 3", emptySet(), "customservice3", Market.RO),
    ;

    /** Loc pentru un serviciu adăugat de tine. */
    val isCustom: Boolean get() = id.startsWith("custom")

    val displayName: String get() = if (isCustom) AppRegistry.customName(id) ?: baseName else baseName

    val tmdbProviderIds: Set<Int> get() = if (isCustom) setOfNotNull(AppRegistry.customProvider(id)) else baseProviderIds

    /** Un serviciu adăugat ocupă un loc după asocierea cu aplicația instalată. */
    val isActive: Boolean get() = !isCustom || TargetApp.byId(appId)?.let(AppRegistry::packageFor) != null

    companion object {
        fun byId(id: String): StreamingService? = entries.firstOrNull { it.id == id }

        /** Serviciile din liste (fără locurile libere). */
        val active: List<StreamingService> get() = entries.filter { it.isActive }

        /** Un loc liber pentru un serviciu nou; null = toate ocupate. */
        fun freeSlot(): StreamingService? = entries.firstOrNull { it.isCustom && !it.isActive }

        /** Serviciile „tale”: cele cu aplicația oficială instalată pe dispozitiv. */
        fun owned(installed: Set<TargetApp>): List<StreamingService> =
            active.filter { s -> TargetApp.byId(s.appId)?.let { it in installed } == true }

        /**
         * Potrivire după id TMDb, apoi după nume (TMDb mai adaugă variante noi). Canalele vândute prin alt
         * magazin („… Amazon Channel”, „… Apple TV Channel”) nu sunt aplicația serviciului, deci nu se potrivesc.
         */
        fun fromProvider(providerId: Int, providerName: String): StreamingService? {
            val n = providerName.lowercase().trim()
            if (n.endsWith(" channel") || " channel " in n) return null
            active.firstOrNull { providerId in it.tmdbProviderIds }?.let { return it }
            fun word(w: String) = n == w || n.startsWith("$w ")
            return when {
                "netflix" in n -> NETFLIX
                "prime video" in n || n == "amazon video" -> PRIME_VIDEO
                "disney" in n -> DISNEY_PLUS
                n == "max" || "hbo max" in n || n.startsWith("max ") || n == "hbo" -> HBO_MAX
                "skyshowtime" in n -> SKYSHOWTIME
                n == "apple tv" || n == "apple tv+" || n == "apple tv plus" -> APPLE_TV
                "voyo" in n -> VOYO
                "peacock" in n -> PEACOCK
                "tubi" in n -> TUBI
                "pluto" in n -> PLUTO
                word("plex") -> PLEX
                "xumo" in n -> XUMO
                "fawesome" in n -> FAWESOME
                "fandango" in n || "vudu" in n -> FANDANGO
                word("pbs") -> PBS
                "sling" in n -> SLING
                n == "arte" || n == "arte.tv" -> ARTE
                "magellan" in n -> MAGELLAN
                "curiosity" in n -> CURIOSITY
                else -> null
            }
        }
    }
}

/** OFFICIAL = aplicația unui serviciu; PLAYER = tip Stremio (surse proprii); IPTV = player IPTV cu hub VOD. */
enum class AppRole(val label: String) {
    OFFICIAL("Serviciu de streaming"),
    PLAYER("Player tip Stremio"),
    IPTV("Player IPTV cu VOD"),
}

/**
 * O aplicație pe care o putem detecta și deschide. [labelHints]: numele din launcher, fără spații și cu
 * litere mici; de la 5 litere în sus ajunge începutul numelui, altfel trebuie să fie exact (vezi [matchesLabel]).
 * Pachetele multor playere nu sunt publice sau diferă între versiuni. [method]: cum obținem
 * „Continuă vizionarea” din ea, după testele de până acum (AK Scrobbler confirmă singur ce merge).
 */
enum class TargetApp(
    val id: String,
    private val baseName: String,
    val role: AppRole,
    /** Pachete cunoscute (TV întâi, apoi telefon). */
    val packages: List<String>,
    val labelHints: List<String> = emptyList(),
    val method: String = "Netestat încă: pornește ceva în aplicație și verifică aici dacă AK Scrobbler l-a prins",
) {
    NETFLIX(
        "netflix", "Netflix", AppRole.OFFICIAL, listOf("com.netflix.ninja", "com.netflix.mediaclient"), listOf("netflix"),
        "Minutul din AK Scrobbler, titlul din activitatea profilului Netflix",
    ),
    PRIME_VIDEO(
        "primevideo", "Prime Video", AppRole.OFFICIAL,
        listOf("com.amazon.amazonvideo.livingroom", "com.amazon.firebat", "com.amazon.avod.thirdpartyclient", "com.amazon.avod"),
        listOf("primevideo"),
        "Sesiune media (minut) + codul titlului, căutat pe pagina Prime Video",
    ),
    DISNEY_PLUS("disneyplus", "Disney+", AppRole.OFFICIAL, listOf("com.disney.disneyplus"), listOf("disney+"), "Sesiune media (titlu + minut)"),
    HBO_MAX("hbomax", "HBO Max", AppRole.OFFICIAL, listOf("com.wbd.stream", "com.hbo.hbonow"), listOf("hbomax", "max"), "Sesiune media (titlu + minut)"),
    SKYSHOWTIME(
        "skyshowtime", "SkyShowtime", AppRole.OFFICIAL,
        listOf("com.skyshowtime.skyshowtime.google", "com.skyshowtime.skyshowtime"), listOf("skyshowtime"),
        "Sesiune media (titlu + minut)",
    ),
    APPLE_TV("appletv", "Apple TV", AppRole.OFFICIAL, listOf("com.apple.atve.androidtv.appletv"), listOf("appletv"), "Cititor de ecran (titlul și minutul din player)"),
    VOYO("voyo", "VOYO", AppRole.OFFICIAL, listOf("net.cme.voyo.ro.tvapp", "net.cme.voyo.ro"), listOf("voyo", "voyo.ro", "voyoro", "voyoromania", "voyotv")),
    PEACOCK("peacock", "Peacock", AppRole.OFFICIAL, listOf("com.peacocktv.peacockandroid"), listOf("peacock"), "Sesiune media (titlu + minut)"),
    TUBI("tubi", "Tubi", AppRole.OFFICIAL, listOf("com.tubitv"), listOf("tubi", "tubitv")),
    PLUTO("pluto", "Pluto TV", AppRole.OFFICIAL, listOf("tv.pluto.android"), listOf("plutotv")),
    PLEX("plex", "Plex", AppRole.OFFICIAL, listOf("com.plexapp.android"), listOf("plex")),
    XUMO("xumo", "Xumo Play", AppRole.OFFICIAL, emptyList(), listOf("xumoplay", "xumo")),
    FAWESOME("fawesome", "Fawesome", AppRole.OFFICIAL, emptyList(), listOf("fawesome")),
    FANDANGO("fandango", "Fandango at Home", AppRole.OFFICIAL, listOf("air.com.vudu.air.DownloaderTablet"), listOf("fandango", "vudu")),
    PBS("pbs", "PBS", AppRole.OFFICIAL, listOf("com.pbs.video"), listOf("pbsvideo", "pbs")),
    SLING("sling", "Sling TV", AppRole.OFFICIAL, listOf("com.sling"), listOf("sling")),
    ARTE("arte", "ARTE", AppRole.OFFICIAL, listOf("tv.arte.plus7"), listOf("arte")),
    MAGELLAN("magellan", "MagellanTV", AppRole.OFFICIAL, emptyList(), listOf("magellan")),
    CURIOSITY("curiosity", "CuriosityStream", AppRole.OFFICIAL, emptyList(), listOf("curiositystream", "curiosity")),

    NUVIO("nuvio", "Nuvio", AppRole.PLAYER, listOf("com.nuvio.tv", "com.nuvio.app"), listOf("nuvio"), "Cititor de ecran (din ecranul playerului)"),
    STREMIO("stremio", "Stremio", AppRole.PLAYER, listOf("com.stremio.one"), listOf("stremio")),
    DEBRID_STREAM(
        "debridstream", "DebridStream", AppRole.PLAYER,
        listOf("com.debridstream.tv", "com.debridstream.app", "com.debridstream"),
        listOf("debridstream"),
        "Cititor de ecran (din ecranul playerului)",
    ),
    WUPLAY("wuplay", "Wuplay", AppRole.PLAYER, emptyList(), listOf("wuplay"), "Sesiune media (titlu + minut)"),
    REALSTREAM("realstream", "RealStream", AppRole.PLAYER, emptyList(), listOf("realstream")),
    ARVIO("arvio", "Arvio", AppRole.PLAYER, emptyList(), listOf("arvio")),
    DODOSTREAM("dodostream", "DodoStream", AppRole.PLAYER, emptyList(), listOf("dodostream")),
    FLOWVID("flowvid", "FlowVid", AppRole.PLAYER, emptyList(), listOf("flowvid")),
    DEBRIFY("debrify", "Debrify", AppRole.PLAYER, emptyList(), listOf("debrify")),
    CLIPBOX("clipbox", "ClipBox", AppRole.PLAYER, emptyList(), listOf("clipbox")),
    ALDER("alder", "Alder", AppRole.PLAYER, emptyList(), listOf("alder")),
    NUVIO_RS("nuviors", "Nuvio RS", AppRole.PLAYER, emptyList(), listOf("nuviors")),
    PARKY("parky", "Parky", AppRole.PLAYER, emptyList(), listOf("parky")),

    AK_VOD_HUB("akvodhub", "AK VoD Hub", AppRole.IPTV, listOf("io.github.radumariusi.akvodhub"), listOf("akvodhub")),
    WAVEO("waveo", "Waveo", AppRole.IPTV, emptyList(), listOf("waveo")),
    SHOVA_TV("shovatv", "Sohva TV", AppRole.IPTV, listOf("com.streammate.tv"), listOf("sohvatv", "sohva", "shovatv", "shova")),
    PLAYER_8K_VIP("8kvip", "8K Player VIP+", AppRole.IPTV, emptyList(), listOf("8kplayervip")),
    PLAYER_8K_PRIME("8kprime", "8K Player Prime", AppRole.IPTV, emptyList(), listOf("8kplayerprime")),

    // Locuri pentru aplicațiile adăugate de tine (Setări → Aplicații și servicii): pachetul și numele le alegi tu.
    CUSTOM_PLAYER_1("customplayer1", "Player adăugat 1", AppRole.PLAYER, emptyList()),
    CUSTOM_PLAYER_2("customplayer2", "Player adăugat 2", AppRole.PLAYER, emptyList()),
    CUSTOM_PLAYER_3("customplayer3", "Player adăugat 3", AppRole.PLAYER, emptyList()),
    CUSTOM_PLAYER_4("customplayer4", "Player adăugat 4", AppRole.PLAYER, emptyList()),
    CUSTOM_IPTV_1("customiptv1", "Player IPTV adăugat 1", AppRole.IPTV, emptyList()),
    CUSTOM_IPTV_2("customiptv2", "Player IPTV adăugat 2", AppRole.IPTV, emptyList()),
    CUSTOM_IPTV_3("customiptv3", "Player IPTV adăugat 3", AppRole.IPTV, emptyList()),
    CUSTOM_SERVICE_1("customservice1", "Serviciu adăugat 1", AppRole.OFFICIAL, emptyList()),
    CUSTOM_SERVICE_2("customservice2", "Serviciu adăugat 2", AppRole.OFFICIAL, emptyList()),
    CUSTOM_SERVICE_3("customservice3", "Serviciu adăugat 3", AppRole.OFFICIAL, emptyList()),
    ;

    /** Loc pentru o aplicație adăugată de tine. */
    val isCustom: Boolean get() = id.startsWith("custom")

    val displayName: String get() = if (isCustom) AppRegistry.customName(id) ?: baseName else baseName

    /** În liste: neeliminată și, la locurile pentru aplicațiile tale, ocupată. */
    val isActive: Boolean get() = !AppRegistry.isHidden(id) && (!isCustom || AppRegistry.packageFor(this) != null)

    /** Numele din launcher se potrivește cu unul din [labelHints]. */
    fun matchesLabel(label: String): Boolean = labelScore(label) > 0

    /** Cât de bine se potrivește numele (lungimea indiciului potrivit; 0 = deloc): „Nuvio RS” e Nuvio RS, nu Nuvio. */
    fun labelScore(label: String): Int {
        val n = label.lowercase().replace(" ", "")
        return labelHints.filter { h -> n == h || (h.length >= 5 && n.startsWith(h)) }
            .maxOfOrNull { h -> if (n == h) h.length + 100 else h.length } ?: 0
    }

    /** Poate reda titluri pentru care nu ai serviciul (playere tip Stremio și IPTV cu VOD). */
    val isPlayer: Boolean get() = role != AppRole.OFFICIAL

    companion object {
        fun byId(id: String): TargetApp? = entries.firstOrNull { it.id == id }

        /** Aplicațiile din liste (fără cele eliminate și fără locurile libere). */
        val active: List<TargetApp> get() = entries.filter { it.isActive }
        val players: List<TargetApp> get() = active.filter { it.isPlayer }

        /** Un loc liber pentru o aplicație nouă cu rolul [role]; null = toate ocupate. */
        fun freeSlot(role: AppRole): TargetApp? =
            entries.firstOrNull { it.isCustom && it.role == role && AppRegistry.packageFor(it) == null }

        /** Aplicația unui pachet (cel ales de tine, apoi pachetele cunoscute). */
        fun byPackage(pkg: String): TargetApp? =
            entries.firstOrNull { app -> AppRegistry.packageFor(app) == pkg && !AppRegistry.isHidden(app.id) }
                ?: entries.firstOrNull { app -> app.packages.any { pkg == it || pkg.startsWith("$it.") } }
    }
}

/**
 * Linkurile candidate pentru o aplicație, de la cel mai precis la cel mai slab.
 * Funcție pură: validarea (dacă aplicația instalată acceptă linkul) o face [TargetResolver].
 *
 * Formatele:
 * - Stremio: `stremio:///detail/{movie|series}/{imdb}/{videoId}`, videoId pentru episod = `{imdb}:{sezon}:{episod}`.
 * - Nuvio (NuvioTV DeepLinkParser): `nuvio://movie/{imdb}`, `nuvio://series/{imdb}`, `nuvio://tmdb/{tip}/{id}`.
 *   Nuvio deschide pagina titlului; nu are link direct la episod.
 * - Netflix / Prime Video / Disney+ / HBO Max: nu există un id public al titlului în TMDb,
 *   deci folosim căutarea după nume în aplicație (unde e acceptată), apoi simpla pornire.
 * - Alte playere (DebridStream, Wuplay…): format nedocumentat; încercăm schemele Stremio/Nuvio, apoi căutarea
 *   (ACTION_SEARCH, dacă aplicația o acceptă, ex. AK VoD Hub), apoi pornirea.
 */
object LinkBuilder {
    fun candidates(app: TargetApp, r: PlaybackRequest): List<LinkCandidate> = when (app) {
        TargetApp.STREMIO -> stremio(r)
        TargetApp.NUVIO -> nuvio(r)
        TargetApp.NETFLIX -> search(r, "https://www.netflix.com/search?q=")
        TargetApp.PRIME_VIDEO -> search(r, "https://app.primevideo.com/search?phrase=", "https://www.primevideo.com/search?phrase=")
        TargetApp.DISNEY_PLUS -> search(r, "https://www.disneyplus.com/search?q=")
        TargetApp.HBO_MAX -> search(r, "https://play.hbomax.com/search?q=", "https://play.max.com/search?q=")
        // Playerele tip Stremio acceptă adesea aceleași linkuri ca Stremio/Nuvio; se folosesc doar dacă aplicația
        // instalată le declară (validat în TargetResolver), altfel căutare în aplicație sau simpla pornire.
        else -> if (app.role == AppRole.PLAYER) stremio(r).filter { it.precision != LinkPrecision.SEARCH } + nuvio(r) else emptyList()
    }

    private fun stremio(r: PlaybackRequest): List<LinkCandidate> {
        val imdb = r.imdbId?.takeIf { it.startsWith("tt") }
            ?: return listOf(LinkCandidate(LinkPrecision.SEARCH, "stremio:///search?search=${enc(r.title)}"))
        return when (r.type) {
            PlaybackRequest.Type.MOVIE -> listOf(LinkCandidate(LinkPrecision.TITLE, "stremio:///detail/movie/$imdb/$imdb"))
            PlaybackRequest.Type.SHOW -> listOf(LinkCandidate(LinkPrecision.SERIES, "stremio:///detail/series/$imdb"))
            PlaybackRequest.Type.EPISODE -> buildList {
                if (r.season != null && r.episode != null) {
                    add(LinkCandidate(LinkPrecision.EPISODE, "stremio:///detail/series/$imdb/$imdb:${r.season}:${r.episode}"))
                }
                add(LinkCandidate(LinkPrecision.SERIES, "stremio:///detail/series/$imdb"))
            }
        }
    }

    private fun nuvio(r: PlaybackRequest): List<LinkCandidate> {
        val isMovie = r.type == PlaybackRequest.Type.MOVIE
        val precision = if (isMovie) LinkPrecision.TITLE else LinkPrecision.SERIES
        val type = if (isMovie) "movie" else "series"
        return buildList {
            r.imdbId?.takeIf { it.startsWith("tt") }?.let { add(LinkCandidate(precision, "nuvio://$type/$it")) }
            add(LinkCandidate(precision, "nuvio://tmdb/$type/${r.tmdbId}"))
        }
    }

    private fun search(r: PlaybackRequest, vararg prefixes: String): List<LinkCandidate> =
        prefixes.map { LinkCandidate(LinkPrecision.SEARCH, it + enc(r.title)) }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
