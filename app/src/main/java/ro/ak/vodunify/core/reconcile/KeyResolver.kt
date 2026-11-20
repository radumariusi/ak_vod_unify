package ro.ak.vodunify.core.reconcile

import ro.ak.vodunify.core.db.TrackedRef

/**
 * Găsește același titlu între surse chiar dacă fiecare îl știe după alt id
 * (ex. Trakt dă TMDb + IMDb, WeTrakr doar IMDb, SIMKL anime doar TVDB + SIMKL).
 *
 * Toate id-urile văzute împreună pe aceeași referință sunt legate (union-find); cheia
 * canonică a grupului e cel mai bun id, în aceeași ordine ca `MediaRef.titleKey`
 * (tmdb > imdb > trakt > simkl > tvdb), deci pentru titlurile cu TMDb cheia rămâne
 * „movie:tmdb:603” / „tv:tmdb:1399”.
 */
class KeyResolver(refs: Iterable<TrackedRef>) {
    private val parent = HashMap<String, String>()

    init {
        refs.forEach { ref ->
            val tokens = tokens(ref)
            tokens.forEach { find(it) }
            for (i in 1 until tokens.size) union(tokens[0], tokens[i])
        }
    }

    /** Cheia canonică a titlului (film sau serial). */
    fun titleKey(ref: TrackedRef): String {
        val tokens = tokens(ref)
        if (tokens.isEmpty()) return ref.mediaKey
        return find(tokens[0])
    }

    /** Cheia canonică a elementului: titlul, plus sezon/episod pentru episoade. */
    fun itemKey(ref: TrackedRef): String {
        val title = titleKey(ref)
        return if (ref.type == EPISODE) "$title:s${ref.season ?: 0}e${ref.episode ?: 0}" else title
    }

    private fun find(x: String): String {
        val p = parent[x] ?: run { parent[x] = x; return x }
        if (p == x) return x
        val root = find(p)
        parent[x] = root
        return root
    }

    private fun union(a: String, b: String) {
        val ra = find(a)
        val rb = find(b)
        if (ra == rb) return
        // Rădăcina e mereu cel mai bun id, ca cheia canonică să fie stabilă.
        if (better(ra, rb)) parent[rb] = ra else parent[ra] = rb
    }

    companion object {
        private const val EPISODE = "EPISODE"
        private val PRIORITY = listOf("tmdb", "imdb", "trakt", "simkl", "tvdb")

        /** Id-urile referinței, ca „movie:tmdb:603”, în ordinea priorității. */
        fun tokens(ref: TrackedRef): List<String> {
            val kind = if (ref.type == "MOVIE") "movie" else "tv"
            return listOfNotNull(
                ref.tmdbId?.let { "$kind:tmdb:$it" },
                ref.imdbId?.takeIf { it.isNotBlank() }?.let { "$kind:imdb:$it" },
                ref.traktId?.let { "$kind:trakt:$it" },
                ref.simklId?.let { "$kind:simkl:$it" },
                ref.tvdbId?.let { "$kind:tvdb:$it" },
            )
        }

        private fun rank(token: String): Int = PRIORITY.indexOf(token.split(':').getOrNull(1)).let { if (it < 0) 99 else it }

        private fun better(a: String, b: String): Boolean {
            val ra = rank(a)
            val rb = rank(b)
            return if (ra != rb) ra < rb else a < b
        }
    }
}
