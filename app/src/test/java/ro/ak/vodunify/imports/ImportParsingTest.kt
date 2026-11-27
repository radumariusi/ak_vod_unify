package ro.ak.vodunify.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.vodunify.core.imports.ImportItem
import ro.ak.vodunify.core.imports.ImportParser
import ro.ak.vodunify.core.imports.ImportPlanner
import ro.ak.vodunify.core.imports.NetflixCsv
import ro.ak.vodunify.core.scrobble.TitleGuess
import java.time.LocalDate
import java.time.ZoneOffset

class ImportParsingTest {

    @Test
    fun csvEpisodeTitle() {
        val g = ImportParser.guesses(ImportItem(title = "Stranger Things: Season 1: Chapter One: The Vanishing of Will Byers"))
        assertEquals(listOf(TitleGuess.SeasonNamedEpisode("Stranger Things", 1, "Chapter One: The Vanishing of Will Byers")), g)
    }

    @Test
    fun csvRomanianAndLimitedSeries() {
        assertEquals(
            TitleGuess.NumberedEpisode("Mercredi", 2, 1, null),
            ImportParser.guesses(ImportItem(title = "Mercredi: Sezonul 2: Ep 1")).first(),
        )
        assertEquals(
            TitleGuess.NumberedEpisode("Adolescence", 1, 1, null),
            ImportParser.guesses(ImportItem(title = "Adolescence: Limited Series: Episode 1")).first(),
        )
    }

    @Test
    fun movieWithColonStaysMovieFirst() {
        val g = ImportParser.guesses(ImportItem(title = "Glass Onion: A Knives Out Mystery"))
        assertEquals(TitleGuess.Movie("Glass Onion: A Knives Out Mystery", null), g.first())
        assertEquals(listOf(TitleGuess.Movie("Kill Bill: Vol. 1", null)), ImportParser.guesses(ImportItem(title = "Kill Bill: Vol. 1")))
    }

    @Test
    fun siteFieldsUsed() {
        val g = ImportParser.guesses(ImportItem(series = "The Crown", season = "Season 3", episode = "Olding", title = "Olding"))
        assertEquals(TitleGuess.SeasonNamedEpisode("The Crown", 3, "Olding"), g.first())
    }

    @Test
    fun seasonNumbers() {
        assertEquals(2, ImportParser.seasonNumber("Partea 2"))
        assertEquals(3, ImportParser.seasonNumber("Volume 3"))
        assertEquals(1, ImportParser.seasonNumber("Miniserie"))
        assertNull(ImportParser.seasonNumber("Chapter One"))
    }

    @Test
    fun simpleCsvUsDates() {
        val csv = "Title,Date\n\"Stranger Things: Season 1: Chapter One\",\"11/20/26\"\n\"Dune\",\"1/5/25\"\n"
        val p = NetflixCsv.parse(csv, ZoneOffset.UTC)
        assertEquals(2, p.items.size)
        assertEquals(LocalDate.of(2026, 11, 20).atTime(21, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), p.items[0].date)
        assertEquals("Dune", p.items[1].title)
    }

    @Test
    fun simpleCsvDayFirstDates() {
        val p = NetflixCsv.parse("Title,Date\nA,20.11.2026\nB,3/4/2025\nC,25/4/2025\n", ZoneOffset.UTC)
        assertEquals(LocalDate.of(2026, 11, 20).atTime(21, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), p.items[0].date)
        assertEquals(LocalDate.of(2025, 4, 3).atTime(21, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), p.items[1].date)
    }

    @Test
    fun fullExportWithBookmarksAndProfiles() {
        val csv = """
            Profile Name,Start Time,Duration,Attributes,Title,Supplemental Video Type,Device Type,Bookmark,Latest Bookmark,Country
            Radu,2026-11-19 20:00:00,00:23:40,,"Stranger Things: Season 1: Chapter One",,TV,00:23:40,00:23:40,RO (Romania)
            Radu,2026-11-19 19:58:00,00:00:30,,"Stranger Things: Season 1 (Trailer)",TRAILER,TV,00:00:30,00:00:30,RO (Romania)
            Ana,2026-11-18 21:00:00,01:40:00,,Dune,,TV,01:40:00,01:40:00,RO (Romania)
        """.trimIndent()
        val p = NetflixCsv.parse(csv)
        assertEquals(2, p.items.size)
        assertEquals(1, p.skipped)
        assertEquals(listOf("Radu", "Ana"), p.profiles)
        assertEquals(23 * 60L + 40, p.items[0].bookmark)
        assertEquals(listOf("Radu", "Ana"), p.itemProfiles)
    }

    @Test
    fun csvQuotesAndCommas() {
        val rows = NetflixCsv.rows("a,\"b, c\",\"d \"\"e\"\"\"\n1,2,3")
        assertEquals(listOf(listOf("a", "b, c", "d \"e\""), listOf("1", "2", "3")), rows)
    }

    @Test
    fun planner() {
        assertEquals(ImportPlanner.Kind.Watched, ImportPlanner.classify(null, null, null))
        assertEquals(ImportPlanner.Kind.Watched, ImportPlanner.classify(2900, 3000, null))
        val r = ImportPlanner.classify(1420, null, 50 * 60_000L)
        assertTrue(r is ImportPlanner.Kind.Resume)
        assertEquals(1_420_000L, (r as ImportPlanner.Kind.Resume).positionMs)
        assertEquals(ImportPlanner.Kind.Skip, ImportPlanner.classify(5, 3000, null))
    }

    @Test
    fun numberedEpisodesComeFirst() {
        // Netflix „Watched”: numele + numerele sezonului și episodului.
        val nf = ImportParser.guesses(
            ImportItem(series = "The Office (U.S.)", season = "Season 2", episode = "The Dundies", title = "x", seasonNumber = 2, episodeNumber = 1),
        )
        assertEquals(TitleGuess.NumberedEpisode("The Office (U.S.)", 2, 1, "The Dundies"), nf.first())
        assertTrue(nf.contains(TitleGuess.SeasonNamedEpisode("The Office (U.S.)", 2, "The Dundies")))
        // Prime: episodul are doar numărul în nume.
        val pr = ImportParser.guesses(ImportItem(series = "The Boys", season = "Season 2", episode = "Episode 3", title = "x"))
        assertEquals(listOf(TitleGuess.NumberedEpisode("The Boys", 2, 3, "Episode 3")), pr)
    }

    @Test
    fun dayBounds() {
        val zone = java.time.ZoneId.of("Europe/Bucharest")
        val at = java.time.ZonedDateTime.of(2026, 11, 20, 23, 30, 0, 0, zone).toInstant().toEpochMilli()
        val (from, to) = ImportPlanner.dayBounds(at, zone)
        assertEquals(java.time.ZonedDateTime.of(2026, 11, 20, 0, 0, 0, 0, zone).toInstant().toEpochMilli(), from)
        assertEquals(java.time.ZonedDateTime.of(2026, 11, 21, 0, 0, 0, 0, zone).toInstant().toEpochMilli() - 1, to)
    }
}
