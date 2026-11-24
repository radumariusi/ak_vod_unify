package ro.ak.vodunify.core.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import ro.ak.vodunify.core.playback.StreamingService
import ro.ak.vodunify.core.db.AvailabilityDao
import ro.ak.vodunify.core.db.AvailabilityEntity
import ro.ak.vodunify.core.db.MediaDao
import ro.ak.vodunify.core.db.MediaEntity
import ro.ak.vodunify.core.prefs.CatalogSettings
import ro.ak.vodunify.core.prefs.ContentLanguage
import ro.ak.vodunify.core.tmdb.TmdbClient
import ro.ak.vodunify.core.tmdb.TmdbGenreList
import ro.ak.vodunify.core.tmdb.TmdbImages
import ro.ak.vodunify.core.tmdb.TmdbListItem
import ro.ak.vodunify.core.tmdb.TmdbNotConfiguredException
import ro.ak.vodunify.core.tmdb.TmdbPage
import ro.ak.vodunify.core.tmdb.TmdbPersonDetails
import ro.ak.vodunify.core.tmdb.TmdbProviderList
import ro.ak.vodunify.core.tmdb.TmdbImageList
import ro.ak.vodunify.core.prefs.Market
import ro.ak.vodunify.core.tmdb.TmdbSeasonDetails
import ro.ak.vodunify.core.tmdb.TmdbStudioDetails
import ro.ak.vodunify.core.tmdb.TmdbTitleDetails
import ro.ak.vodunify.core.tmdb.TmdbWatchProviders
import ro.ak.vodunify.core.tmdb.TmdbUnauthorizedException
import javax.inject.Inject
import javax.inject.Singleton

/** Catalogul aplicației, construit peste TMDb + cache Room. */
@Singleton
class CatalogRepository @Inject constructor(
    private val tmdb: TmdbClient,
    private val mediaDao: MediaDao,
    private val availabilityDao: AvailabilityDao,
) {
    val isConfigured: Boolean get() = tmdb.isConfigured

    private val pageSerializer = TmdbPage.serializer(TmdbListItem.serializer())

    private suspend fun list(path: String, params: Map<String, String?>, ttl: Long = TmdbClient.DEFAULT_TTL) =
        tmdb.get(path, params + ("include_adult" to "false"), pageSerializer, ttl)

    // ---------- Acasă ----------

    /** [shelves]: serviciile tale (instalate), fiecare cu biblioteca lui: un rând „Pe Netflix (RO)” pentru fiecare. */
    suspend fun home(settings: CatalogSettings, shelves: List<ServiceShelf>): HomeCatalog = supervisorScope {
        val lang = settings.apiLanguage
        val l = settings.language
        val region = Market.RO.code

        val movies = async { list("movie/popular", mapOf("language" to lang, "region" to region)) }
        val shows = async { list("tv/popular", mapOf("language" to lang)) }
        val topMovies = async { list("movie/top_rated", mapOf("language" to lang, "region" to region)) }

        val errors = mutableListOf<Throwable>()
        suspend fun <T> safe(block: suspend () -> T): T? = try {
            block()
        } catch (e: TmdbNotConfiguredException) {
            throw e
        } catch (e: TmdbUnauthorizedException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors += e
            null
        }

        val directories = shelves.map { it.market }.distinct().associateWith { market ->
            TitleKind.entries.associateWith { kind ->
                async { safe { serviceDirectory(market, kind) } }
            }
        }

        // Fără „În tendințe”: amesteca filme apărute cu filme care încă nu au apărut.
        val rows = mutableListOf<CatalogRow>()
        val popularMovies = safe { TmdbMappers.items(movies.await().results, l, TitleKind.MOVIE) }
        val popularShows = safe { TmdbMappers.items(shows.await().results, l, TitleKind.TV) }
        popularMovies?.let { rows += CatalogRow("movies", "Filme populare", it) }
        popularShows?.let { rows += CatalogRow("shows", "Seriale populare", it) }
        safe { TmdbMappers.items(topMovies.await().results, l, TitleKind.MOVIE) }?.let { rows += CatalogRow("top_movies", "Filme apreciate", it) }

        val serviceRows = shelves.map { shelf ->
            async {
                val parts = TitleKind.entries.map { kind ->
                    async {
                        val directory = directories.getValue(shelf.market).getValue(kind).await()
                        val ids = homeProviderIds(shelf.service, directory)
                        val items = if (ids.isEmpty()) emptyList() else safe { serviceCatalog(shelf.market, kind, ids, settings) }
                        val status = when {
                            items == null || ids.isEmpty() && directory == null -> ServiceCatalogStatus.UNAVAILABLE
                            ids.isEmpty() -> ServiceCatalogStatus.NO_PROVIDER
                            else -> ServiceCatalogStatus.NO_TITLES
                        }
                        items.orEmpty() to status
                    }
                }.awaitAll()
                val items = TmdbMappers.interleave(parts[0].first, parts[1].first)
                val status = when {
                    items.isNotEmpty() -> null
                    parts.any { it.second == ServiceCatalogStatus.UNAVAILABLE } -> ServiceCatalogStatus.UNAVAILABLE
                    parts.any { it.second == ServiceCatalogStatus.NO_TITLES } -> ServiceCatalogStatus.NO_TITLES
                    else -> ServiceCatalogStatus.NO_PROVIDER
                }
                shelf.row(items, status)
            }
        }.awaitAll()

        val nonEmpty = rows.filter { it.items.isNotEmpty() } + serviceRows
        if (nonEmpty.isEmpty() && errors.isNotEmpty()) throw errors.first()
        HomeCatalog(
            hero = TmdbMappers.interleave(popularMovies.orEmpty(), popularShows.orEmpty()).filter { it.backdropUrl != null }.take(6),
            rows = nonEmpty,
        )
    }

    private suspend fun serviceDirectory(market: Market, kind: TitleKind): List<ProviderInfo> =
        tmdb.get(
            "watch/providers/${kind.path}", mapOf("watch_region" to market.code),
            TmdbProviderList.serializer(), TmdbClient.LONG_TTL,
        ).results.map(TmdbMappers::provider)

    private suspend fun serviceCatalog(market: Market, kind: TitleKind, ids: Set<Int>, settings: CatalogSettings): List<CatalogItem> {
        val params = mapOf(
            "language" to settings.apiLanguage,
            "watch_region" to market.code,
            "with_watch_providers" to ids.sorted().joinToString("|"),
            "with_watch_monetization_types" to "flatrate|free|ads",
            "sort_by" to "popularity.desc",
        )
        return TmdbMappers.items(list("discover/${kind.path}", params).results, settings.language, kind)
    }

    /**
     * Date minime (titlu în limba aleasă, poster, fundal) pentru un titlu după id-ul TMDb.
     * Folosit pentru rândurile personale (Trakt); cache 7 zile.
     */
    suspend fun basic(kind: TitleKind, id: Long, settings: CatalogSettings): CatalogItem {
        val dto = tmdb.get("${kind.path}/$id", mapOf("language" to settings.apiLanguage), TmdbTitleDetails.serializer(), TmdbClient.LONG_TTL)
        val isMovie = kind == TitleKind.MOVIE
        return CatalogItem(
            id = dto.id,
            kind = kind,
            title = TmdbMappers.displayTitle(
                if (isMovie) dto.title else dto.name,
                if (isMovie) dto.originalTitle else dto.originalName,
                settings.language,
            ),
            year = TmdbMappers.year(if (isMovie) dto.releaseDate else dto.firstAirDate),
            overview = TmdbMappers.cleanOverview(dto.overview).orEmpty(),
            posterUrl = TmdbImages.poster(dto.posterPath),
            backdropUrl = TmdbImages.backdrop(dto.backdropPath),
            rating = dto.voteAverage?.takeIf { it > 0 },
            episodeCount = dto.numberOfEpisodes,
        )
    }

    /** Unde e titlul (RO și US), fără restul detaliilor. */
    suspend fun watchAvailability(kind: TitleKind, id: Long): List<MarketAvailability> {
        val providers = tmdb.get("${kind.path}/$id/watch/providers", emptyMap(), TmdbWatchProviders.serializer())
        return Market.entries.map { TmdbMappers.availability(it, providers.results[it.code]) }
    }

    /**
     * Titlurile populare pentru „În curând” și „Noutăți”: filme care apar în următoarele [movieDays] zile sau au
     * apărut în ultimele [recentDays], seriale cu un episod între acum [recentDays] zile și peste [tvDays].
     */
    suspend fun upcomingPopular(settings: CatalogSettings, movieDays: Long, tvDays: Long, recentDays: Long = 0): List<TitleReleases> = supervisorScope {
        val today = java.time.LocalDate.now()
        val lang = settings.apiLanguage
        suspend fun movies(from: java.time.LocalDate, to: java.time.LocalDate): List<TitleReleases> {
            val r = list(
                "discover/movie",
                mapOf(
                    "language" to lang,
                    "primary_release_date.gte" to from.toString(),
                    "primary_release_date.lte" to to.toString(),
                    "sort_by" to "popularity.desc",
                ),
            )
            val dates = r.results.associate { it.id to it.releaseDate }
            return TmdbMappers.items(r.results, settings.language, TitleKind.MOVIE).map {
                TitleReleases(item = it, releaseDate = dates[it.id]?.takeIf { d -> d.isNotBlank() }, lastEpisode = null, nextEpisode = null)
            }
        }
        val upcoming = async { movies(today.plusDays(1), today.plusDays(movieDays)) }
        val recent = async { if (recentDays > 0) movies(today.minusDays(recentDays), today) else emptyList() }
        val shows = async {
            val r = list(
                "discover/tv",
                mapOf(
                    "language" to lang,
                    "air_date.gte" to today.minusDays(recentDays).toString(),
                    "air_date.lte" to today.plusDays(tvDays).toString(),
                    "sort_by" to "popularity.desc",
                ),
            )
            // Episodul următor vine din detaliile serialului (păstrate în cache).
            TmdbMappers.items(r.results, settings.language, TitleKind.TV).take(20).map { item ->
                async { runCatching { releases(TitleKind.TV, item.id, settings) }.getOrNull() }
            }.awaitAll().filterNotNull()
        }
        val m = runCatching { upcoming.await() }.getOrDefault(emptyList()) + runCatching { recent.await() }.getOrDefault(emptyList())
        val t = runCatching { shows.await() }.getOrDefault(emptyList())
        m + t
    }

    /** Titlul cu datele de lansare (pentru „Noutăți” și „În curând” din Acasă); se reîmprospătează la 6 ore. */
    suspend fun releases(kind: TitleKind, id: Long, settings: CatalogSettings): TitleReleases {
        val dto = tmdb.get("${kind.path}/$id", mapOf("language" to settings.apiLanguage), TmdbTitleDetails.serializer(), TmdbClient.DEFAULT_TTL)
        val isMovie = kind == TitleKind.MOVIE
        val item = CatalogItem(
            id = dto.id,
            kind = kind,
            title = TmdbMappers.displayTitle(
                if (isMovie) dto.title else dto.name,
                if (isMovie) dto.originalTitle else dto.originalName,
                settings.language,
            ),
            year = TmdbMappers.year(if (isMovie) dto.releaseDate else dto.firstAirDate),
            overview = TmdbMappers.cleanOverview(dto.overview).orEmpty(),
            posterUrl = TmdbImages.poster(dto.posterPath),
            backdropUrl = TmdbImages.backdrop(dto.backdropPath),
            rating = dto.voteAverage?.takeIf { it > 0 },
            episodeCount = dto.numberOfEpisodes,
        )
        fun air(e: ro.ak.vodunify.core.tmdb.TmdbEpisodeRef?) = e?.let { EpisodeAir(it.seasonNumber, it.episodeNumber, it.name, it.airDate) }
        return TitleReleases(
            item = item,
            releaseDate = if (isMovie) dto.releaseDate?.takeIf { it.isNotBlank() } else null,
            lastEpisode = air(dto.lastEpisodeToAir),
            nextEpisode = air(dto.nextEpisodeToAir),
        )
    }

    // ---------- Detalii ----------

    suspend fun details(kind: TitleKind, id: Long, settings: CatalogSettings): TitleDetails {
        val append = buildList {
            add("credits"); add("watch/providers"); add("recommendations"); add("external_ids"); add("videos"); add("images")
        }.joinToString(",")
        val dto = tmdb.get(
            "${kind.path}/$id",
            mapOf(
                "language" to settings.apiLanguage,
                "append_to_response" to append,
                "include_video_language" to "ro,en,null",
                "include_image_language" to logoLanguages(settings),
            ),
            TmdbTitleDetails.serializer(),
        )
        // Multe titluri nu au descriere în română: cădem pe engleză.
        val overview = TmdbMappers.cleanOverview(dto.overview) ?: if (settings.language == ContentLanguage.ROMANIAN) {
            runCatching {
                tmdb.get("${kind.path}/$id", mapOf("language" to "en-US"), TmdbTitleDetails.serializer()).overview.let(TmdbMappers::cleanOverview)
            }.getOrNull().orEmpty()
        } else {
            ""
        }

        val details = mapDetails(kind, dto, overview, settings)
        runCatching { persist(details) } // cache-ul local nu trebuie să blocheze afișarea
        return details
    }

    private fun mapDetails(kind: TitleKind, dto: TmdbTitleDetails, overview: String, settings: CatalogSettings): TitleDetails {
        val isMovie = kind == TitleKind.MOVIE
        val translated = if (isMovie) dto.title else dto.name
        val original = if (isMovie) dto.originalTitle else dto.originalName
        val title = TmdbMappers.displayTitle(translated, original, settings.language)
        val item = CatalogItem(
            id = dto.id,
            kind = kind,
            title = title,
            year = TmdbMappers.year(if (isMovie) dto.releaseDate else dto.firstAirDate),
            overview = overview,
            posterUrl = TmdbImages.posterLarge(dto.posterPath),
            backdropUrl = TmdbImages.backdrop(dto.backdropPath),
            rating = dto.voteAverage?.takeIf { it > 0 },
            genreIds = dto.genres.map { it.id },
        )
        val credits = dto.credits
        val crewJobs = listOf("Director", "Screenplay", "Writer", "Creator", "Executive Producer", "Original Music Composer")
        val crew = credits?.crew.orEmpty()
            .filter { it.job in crewJobs }
            .sortedBy { crewJobs.indexOf(it.job.orEmpty()) }
            .distinctBy { it.id }
            .take(8)
            .map { PersonSummary(it.id, it.name, TmdbImages.profile(it.profilePath), jobLabel(it.job)) }

        val studios = buildList {
            dto.networks.forEach { add(StudioRef(it.id, StudioKind.NETWORK, it.name, TmdbImages.logo(it.logoPath))) }
            dto.productionCompanies.forEach { add(StudioRef(it.id, StudioKind.COMPANY, it.name, TmdbImages.logo(it.logoPath))) }
        }

        val trailer = dto.videos?.results.orEmpty()
            .filter { it.site == "YouTube" && (it.type == "Trailer" || it.type == "Teaser") }
            .sortedWith(compareBy({ it.type != "Trailer" }, { !it.official }))
            .firstOrNull()?.key

        return TitleDetails(
            item = item,
            originalTitle = original?.takeIf { it != title },
            tagline = dto.tagline?.takeIf { it.isNotBlank() },
            genres = dto.genres.map { it.name },
            runtimeMinutes = dto.runtime ?: dto.episodeRunTime.firstOrNull(),
            seasonCount = dto.numberOfSeasons,
            episodeCount = dto.numberOfEpisodes,
            status = dto.status,
            voteCount = dto.voteCount,
            cast = credits?.cast.orEmpty().sortedBy { it.order }.take(20)
                .map { PersonSummary(it.id, it.name, TmdbImages.profile(it.profilePath), it.character) },
            crew = crew,
            studios = studios,
            seasons = dto.seasons
                .filter { it.episodeCount > 0 }
                .sortedWith(compareBy { if (it.seasonNumber == 0) Int.MAX_VALUE else it.seasonNumber })
                .map {
                    SeasonSummary(
                        it.seasonNumber,
                        it.name?.takeIf { n -> n.isNotBlank() } ?: if (it.seasonNumber == 0) "Speciale" else "Sezonul ${it.seasonNumber}",
                        it.episodeCount,
                        TmdbMappers.year(it.airDate),
                    )
                },
            availability = TmdbMappers.availabilityFor(dto),
            recommendations = TmdbMappers.items(dto.recommendations?.results.orEmpty(), settings.language, kind),
            imdbId = dto.imdbId ?: dto.externalIds?.imdbId,
            trailerYoutubeKey = trailer,
            nextEpisode = dto.nextEpisodeToAir?.let { e ->
                "Următorul episod: " + "S%02dE%02d".format(e.seasonNumber, e.episodeNumber) + (e.airDate?.let { " · $it" } ?: "")
            },
            logoUrl = TmdbImages.titleLogo(TmdbMappers.logoPath(dto.images?.logos.orEmpty(), settings.logoLanguage)),
            lastAiredEpisode = dto.lastEpisodeToAir?.let { EpisodeAir(it.seasonNumber, it.episodeNumber, it.name, it.airDate) },
        )
    }

    private fun logoLanguages(settings: CatalogSettings) = "${settings.logoLanguage},en,null"

    /** Sigla unui titlu, pentru hero-ul din Acasă (cache lung: siglele nu se schimbă). Null = fără siglă. */
    suspend fun logoUrl(kind: TitleKind, id: Long, settings: CatalogSettings): String? {
        val images = tmdb.get(
            "${kind.path}/$id/images",
            mapOf("include_image_language" to logoLanguages(settings)),
            TmdbImageList.serializer(),
            TmdbClient.LONG_TTL,
        )
        return TmdbImages.titleLogo(TmdbMappers.logoPath(images.logos, settings.logoLanguage))
    }

    private fun jobLabel(job: String?) = when (job) {
        "Director" -> "Regie"
        "Screenplay", "Writer" -> "Scenariu"
        "Creator" -> "Creator"
        "Executive Producer" -> "Producător executiv"
        "Original Music Composer" -> "Muzică"
        else -> job
    }

    /** Salvează titlul în `media` și disponibilitatea pe piață în `availability`. */
    private suspend fun persist(details: TitleDetails) {
        val now = System.currentTimeMillis()
        val existing = mediaDao.get(details.item.mediaKey)
        mediaDao.upsert(
            listOf(
                MediaEntity(
                    key = details.item.mediaKey,
                    type = details.item.kind.path,
                    title = details.item.title,
                    year = details.item.year,
                    tmdbId = details.item.id,
                    imdbId = details.imdbId,
                    traktId = existing?.traktId,
                    simklId = existing?.simklId,
                    posterUrl = details.item.posterUrl,
                    backdropUrl = details.item.backdropUrl,
                    overview = details.item.overview,
                    updatedAt = now,
                ),
            ),
        )
        fun enc(list: List<ProviderInfo>) = list.joinToString("|") { "${it.id}:${it.name.replace("|", "/")}" }
        availabilityDao.upsert(
            details.availability.map {
                AvailabilityEntity(
                    mediaKey = details.item.mediaKey,
                    market = it.market.code,
                    link = it.link,
                    flatrate = enc(it.flatrate),
                    free = enc(it.free),
                    rent = enc(it.rent),
                    buy = enc(it.buy),
                    updatedAt = now,
                )
            },
        )
    }

    suspend fun season(tvId: Long, number: Int, settings: CatalogSettings): Season {
        val dto = tmdb.get("tv/$tvId/season/$number", mapOf("language" to settings.apiLanguage), TmdbSeasonDetails.serializer())
        return Season(
            number = dto.seasonNumber,
            name = dto.name ?: "Sezonul $number",
            overview = TmdbMappers.cleanOverview(dto.overview).orEmpty(),
            episodes = dto.episodes.map {
                Episode(
                    season = it.seasonNumber,
                    number = it.episodeNumber,
                    name = it.name?.takeIf { n -> n.isNotBlank() } ?: "Episodul ${it.episodeNumber}",
                    overview = TmdbMappers.cleanOverview(it.overview).orEmpty(),
                    stillUrl = TmdbImages.still(it.stillPath),
                    airDate = it.airDate,
                    runtimeMinutes = it.runtime,
                    rating = it.voteAverage?.takeIf { v -> v > 0 },
                )
            },
        )
    }

    // ---------- Persoane și studiouri ----------

    suspend fun person(id: Long, settings: CatalogSettings): PersonDetails {
        val dto = tmdb.get(
            "person/$id",
            mapOf("language" to settings.apiLanguage, "append_to_response" to "combined_credits"),
            TmdbPersonDetails.serializer(),
        )
        val bio = dto.biography?.takeIf { it.isNotBlank() } ?: if (settings.language == ContentLanguage.ROMANIAN) {
            runCatching { tmdb.get("person/$id", mapOf("language" to "en-US"), TmdbPersonDetails.serializer()).biography }
                .getOrNull().orEmpty()
        } else {
            ""
        }
        val credits = dto.combinedCredits
        val all = (credits?.cast.orEmpty() + credits?.crew.orEmpty())
            .sortedByDescending { it.popularity ?: 0.0 }
        val items = TmdbMappers.items(all, settings.language)
        return PersonDetails(
            id = dto.id,
            name = dto.name,
            biography = bio,
            birthday = dto.birthday,
            deathday = dto.deathday,
            placeOfBirth = dto.placeOfBirth,
            profileUrl = TmdbImages.url("h632", dto.profilePath),
            department = dto.knownForDepartment,
            movies = items.filter { it.kind == TitleKind.MOVIE },
            shows = items.filter { it.kind == TitleKind.TV },
            credits = items,
        )
    }

    suspend fun studio(kind: StudioKind, id: Long, settings: CatalogSettings): StudioDetails = coroutineScope {
        val info = async { tmdb.get("${kind.path}/$id", emptyMap(), TmdbStudioDetails.serializer(), TmdbClient.LONG_TTL) }
        val base = mapOf("language" to settings.apiLanguage, "sort_by" to "popularity.desc")
        val movies = if (kind == StudioKind.COMPANY) {
            async { list("discover/movie", base + ("with_companies" to id.toString())) }
        } else {
            null
        }
        val shows = async {
            list("discover/tv", base + ((if (kind == StudioKind.NETWORK) "with_networks" else "with_companies") to id.toString()))
        }
        val dto = info.await()
        StudioDetails(
            ref = StudioRef(dto.id, kind, dto.name, TmdbImages.url("w300", dto.logoPath)),
            description = dto.description?.takeIf { it.isNotBlank() },
            headquarters = dto.headquarters?.takeIf { it.isNotBlank() },
            country = dto.originCountry?.takeIf { it.isNotBlank() },
            movies = movies?.await()?.results?.let { TmdbMappers.items(it, settings.language, TitleKind.MOVIE) }.orEmpty(),
            shows = TmdbMappers.items(shows.await().results, settings.language, TitleKind.TV),
        )
    }

    // ---------- Căutare ----------

    /** Genurile pentru filme sau pentru seriale (Descoperă). */
    suspend fun genresFor(kind: TitleKind, settings: CatalogSettings): List<Genre> =
        tmdb.get("genre/${kind.path}/list", mapOf("language" to settings.apiLanguage), TmdbGenreList.serializer(), TmdbClient.LONG_TTL)
            .genres.map { Genre(it.id, it.name.replaceFirstChar { c -> c.uppercase() }) }.sortedBy { it.name }

    /** Descoperă: /discover cu toate filtrele, o pagină. */
    suspend fun discoverTitles(filters: DiscoverFilters, settings: CatalogSettings, page: Int): SearchPage {
        val r = list("discover/${filters.kind.path}", DiscoverQuery.params(filters, page, settings.apiLanguage))
        val items = TmdbMappers.items(r.results, settings.language, filters.kind)
        return SearchPage(items.map { SearchResult.Title(it) }, r.page, minOf(r.totalPages, 500))
    }

    suspend fun genres(settings: CatalogSettings): List<Genre> = coroutineScope {
        val params = mapOf("language" to settings.apiLanguage)
        val m = async { tmdb.get("genre/movie/list", params, TmdbGenreList.serializer(), TmdbClient.LONG_TTL) }
        val t = async { tmdb.get("genre/tv/list", params, TmdbGenreList.serializer(), TmdbClient.LONG_TTL) }
        (m.await().genres + t.await().genres)
            .distinctBy { it.id }
            .map { Genre(it.id, it.name.replaceFirstChar { c -> c.uppercase() }) }
            .sortedBy { it.name }
    }

    /**
     * Căutare după text (titlu sau persoană) cu filtre. Fără text, dar cu filtre,
     * folosește /discover (răsfoire după gen/an).
     */
    suspend fun search(query: String, filters: SearchFilters, settings: CatalogSettings, page: Int = 1): SearchPage {
        val q = query.trim()
        val lang = settings.apiLanguage
        val l = settings.language
        val year = filters.year?.toString()
        val pageStr = page.toString()

        if (q.isEmpty()) {
            if (filters.isEmpty || filters.type == SearchType.PERSON) {
                if (filters.type == SearchType.PERSON) {
                    val r = list("person/popular", mapOf("language" to lang, "page" to pageStr))
                    return SearchPage(r.results.map(::personResult), r.page, r.totalPages)
                }
                return SearchPage(emptyList(), 1, 1)
            }
            return discover(filters, settings, page)
        }

        val (path, extra) = when (filters.type) {
            SearchType.ALL -> "search/multi" to emptyMap<String, String?>()
            SearchType.MOVIE -> "search/movie" to mapOf("primary_release_year" to year)
            SearchType.TV -> "search/tv" to mapOf("first_air_date_year" to year)
            SearchType.PERSON -> "search/person" to emptyMap<String, String?>()
        }
        val r = list(path, mapOf("query" to q, "language" to lang, "page" to pageStr) + extra, ttl = TmdbClient.DEFAULT_TTL)
        val fallbackKind = when (filters.type) {
            SearchType.MOVIE -> TitleKind.MOVIE
            SearchType.TV -> TitleKind.TV
            else -> null
        }
        val results = r.results.mapNotNull { dto ->
            if (dto.mediaType == "person" || filters.type == SearchType.PERSON) {
                personResult(dto)
            } else {
                TmdbMappers.item(dto, l, fallbackKind)
                    ?.takeIf { !dto.adult }
                    ?.takeIf { filters.genreId == null || filters.genreId in it.genreIds }
                    ?.takeIf { filters.year == null || it.year == filters.year }
                    ?.let { SearchResult.Title(it) }
            }
        }
        return SearchPage(results, r.page, r.totalPages)
    }

    private suspend fun discover(filters: SearchFilters, settings: CatalogSettings, page: Int): SearchPage = coroutineScope {
        val base = mapOf(
            "language" to settings.apiLanguage,
            "sort_by" to "popularity.desc",
            "with_genres" to filters.genreId?.toString(),
            "page" to page.toString(),
            "vote_count.gte" to "20",
        )
        val wantMovies = filters.type == SearchType.ALL || filters.type == SearchType.MOVIE
        val wantShows = filters.type == SearchType.ALL || filters.type == SearchType.TV
        val m = if (wantMovies) async { list("discover/movie", base + ("primary_release_year" to filters.year?.toString())) } else null
        val t = if (wantShows) async { list("discover/tv", base + ("first_air_date_year" to filters.year?.toString())) } else null
        val mp = m?.await()
        val tp = t?.await()
        val movies = mp?.results?.let { TmdbMappers.items(it, settings.language, TitleKind.MOVIE) }.orEmpty()
        val shows = tp?.results?.let { TmdbMappers.items(it, settings.language, TitleKind.TV) }.orEmpty()
        SearchPage(
            TmdbMappers.interleave(movies, shows).map { SearchResult.Title(it) },
            page,
            maxOf(mp?.totalPages ?: 1, tp?.totalPages ?: 1),
        )
    }

    private fun personResult(dto: TmdbListItem) = SearchResult.Person(
        PersonSummary(dto.id, dto.name ?: dto.title ?: "?", TmdbImages.profile(dto.profilePath), dto.knownForDepartment),
        knownFor = dto.knownForDepartment?.let(::departmentLabel),
    )

    private fun departmentLabel(d: String) = when (d) {
        "Acting" -> "Actorie"
        "Directing" -> "Regie"
        "Writing" -> "Scenariu"
        "Production" -> "Producție"
        "Sound" -> "Muzică/sunet"
        else -> d
    }

    suspend fun clearCache() = tmdb.clearCache()

}
