package ro.ak.vodunify.core.catalog

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
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
import ro.ak.vodunify.core.tmdb.TmdbSeasonDetails
import ro.ak.vodunify.core.tmdb.TmdbStudioDetails
import ro.ak.vodunify.core.tmdb.TmdbTitleDetails
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

    suspend fun home(settings: CatalogSettings): HomeCatalog = supervisorScope {
        val lang = settings.apiLanguage
        val l = settings.language
        val region = settings.market.code

        val trending = async { list("trending/all/week", mapOf("language" to lang)) }
        val movies = async { list("movie/popular", mapOf("language" to lang, "region" to region)) }
        val shows = async { list("tv/popular", mapOf("language" to lang)) }
        val topMovies = async { list("movie/top_rated", mapOf("language" to lang, "region" to region)) }
        val providers = async { topServices(settings, 5) }

        val errors = mutableListOf<Throwable>()
        suspend fun <T> safe(block: suspend () -> T): T? = try {
            block()
        } catch (e: TmdbNotConfiguredException) {
            throw e
        } catch (e: TmdbUnauthorizedException) {
            throw e
        } catch (e: Exception) {
            errors += e
            null
        }

        val trendingItems = safe { TmdbMappers.items(trending.await().results, l) }.orEmpty()
        val rows = mutableListOf<CatalogRow>()
        rows += CatalogRow("trending", "În tendințe", trendingItems)
        safe { TmdbMappers.items(movies.await().results, l, TitleKind.MOVIE) }?.let { rows += CatalogRow("movies", "Filme populare", it) }
        safe { TmdbMappers.items(shows.await().results, l, TitleKind.TV) }?.let { rows += CatalogRow("shows", "Seriale populare", it) }
        safe { TmdbMappers.items(topMovies.await().results, l, TitleKind.MOVIE) }?.let { rows += CatalogRow("top_movies", "Filme apreciate", it) }

        val serviceRows = safe { providers.await() }.orEmpty().map { provider ->
            async {
                safe {
                    val (m, t) = serviceCatalog(provider.id, settings)
                    CatalogRow("service_${provider.id}", "Pe ${provider.name} (${settings.market.code})", TmdbMappers.interleave(m, t))
                }
            }
        }.awaitAll().filterNotNull()
        rows += serviceRows

        val nonEmpty = rows.filter { it.items.isNotEmpty() }
        if (nonEmpty.isEmpty() && errors.isNotEmpty()) throw errors.first()
        HomeCatalog(
            hero = trendingItems.filter { it.backdropUrl != null }.take(6),
            rows = nonEmpty,
        )
    }

    /** Serviciile de streaming principale din piața selectată (Netflix, Prime Video etc.). */
    suspend fun topServices(settings: CatalogSettings, count: Int): List<ProviderInfo> {
        val all = tmdb.get(
            "watch/providers/movie",
            mapOf("watch_region" to settings.market.code, "language" to settings.apiLanguage),
            TmdbProviderList.serializer(),
            TmdbClient.LONG_TTL,
        )
        return TmdbMappers.topProviders(all.results, settings.market, count)
    }

    private suspend fun serviceCatalog(providerId: Int, settings: CatalogSettings): Pair<List<CatalogItem>, List<CatalogItem>> =
        coroutineScope {
            val params = mapOf(
                "language" to settings.apiLanguage,
                "watch_region" to settings.market.code,
                "with_watch_providers" to providerId.toString(),
                "with_watch_monetization_types" to "flatrate|free|ads",
                "sort_by" to "popularity.desc",
            )
            val m = async { list("discover/movie", params) }
            val t = async { list("discover/tv", params) }
            TmdbMappers.items(m.await().results, settings.language, TitleKind.MOVIE) to
                TmdbMappers.items(t.await().results, settings.language, TitleKind.TV)
        }

    // ---------- Detalii ----------

    suspend fun details(kind: TitleKind, id: Long, settings: CatalogSettings): TitleDetails {
        val append = buildList {
            add("credits"); add("watch/providers"); add("recommendations"); add("external_ids"); add("videos")
        }.joinToString(",")
        val dto = tmdb.get(
            "${kind.path}/$id",
            mapOf(
                "language" to settings.apiLanguage,
                "append_to_response" to append,
                "include_video_language" to "ro,en,null",
            ),
            TmdbTitleDetails.serializer(),
        )
        // Multe titluri nu au descriere în română: cădem pe engleză.
        val overview = dto.overview?.takeIf { it.isNotBlank() } ?: if (settings.language == ContentLanguage.ROMANIAN) {
            runCatching {
                tmdb.get("${kind.path}/$id", mapOf("language" to "en-US"), TmdbTitleDetails.serializer()).overview
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
            availability = TmdbMappers.availabilityFor(dto, settings.market),
            recommendations = TmdbMappers.items(dto.recommendations?.results.orEmpty(), settings.language, kind),
            imdbId = dto.imdbId ?: dto.externalIds?.imdbId,
            trailerYoutubeKey = trailer,
            nextEpisode = dto.nextEpisodeToAir?.let { e ->
                "Următorul episod: S${e.seasonNumber} E${e.episodeNumber}" + (e.airDate?.let { " · $it" } ?: "")
            },
        )
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
            overview = dto.overview.orEmpty(),
            episodes = dto.episodes.map {
                Episode(
                    season = it.seasonNumber,
                    number = it.episodeNumber,
                    name = it.name?.takeIf { n -> n.isNotBlank() } ?: "Episodul ${it.episodeNumber}",
                    overview = it.overview.orEmpty(),
                    stillUrl = TmdbImages.still(it.stillPath),
                    airDate = it.airDate,
                    runtimeMinutes = it.runtime,
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
