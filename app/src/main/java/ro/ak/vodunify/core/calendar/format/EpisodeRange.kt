package ro.ak.vodunify.core.calendar.format

import ro.ak.vodunify.core.connector.EpisodeNumber
import java.util.Locale

/** Unește numai numerele consecutive și păstrează separat fiecare sezon. */
internal fun episodeRange(episodes: Collection<EpisodeNumber>): String =
    episodes.groupBy { it.season }.toSortedMap().entries.joinToString(" · ") { (season, members) ->
        val ranges = mutableListOf<IntRange>()
        members.map { it.episode }.distinct().sorted().forEach { n ->
            val last = ranges.lastOrNull()
            if (last != null && n - last.last == 1) ranges[ranges.lastIndex] = last.first..n
            else ranges.add(n..n)
        }
        "S%02d".format(Locale.ROOT, season) + ranges.joinToString(", ") { range ->
            val first = "E%02d".format(Locale.ROOT, range.first)
            if (range.first == range.last) first else first + "–%02d".format(Locale.ROOT, range.last)
        }
    }
