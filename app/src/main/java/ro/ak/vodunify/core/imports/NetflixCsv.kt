package ro.ak.vodunify.core.imports

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Rezultatul citirii unui CSV Netflix. */
data class CsvParse(
    val items: List<ImportItem>,
    /** Profilurile găsite (doar în exportul complet „ViewingActivity.csv”); gol în CSV-ul simplu. */
    val profiles: List<String>,
    /** Profilul fiecărui element, în aceeași ordine ca [items] (gol în CSV-ul simplu). */
    val itemProfiles: List<String>,
    val skipped: Int,
)

/**
 * Citește cele două fișiere de istoric pe care le dă Netflix:
 *  - „NetflixViewingHistory.csv” (Cont → Profil → Activitate de vizionare → Descarcă tot): `Title,Date`;
 *  - „ViewingActivity.csv” din „Descarcă informațiile tale”: `Profile Name,Start Time,Duration,…,Title,
 *    Supplemental Video Type,…,Bookmark,…` (cu poziția la care ai rămas).
 */
object NetflixCsv {

    fun parse(text: String, zone: ZoneId = ZoneId.of("Europe/Bucharest")): CsvParse {
        val rows = rows(text.removePrefix("﻿"))
        if (rows.isEmpty()) return CsvParse(emptyList(), emptyList(), emptyList(), 0)
        val header = rows.first().map { it.trim().lowercase() }
        val body = rows.drop(1).filter { r -> r.any { it.isNotBlank() } }
        val title = header.indexOf("title")
        if (title < 0) return CsvParse(emptyList(), emptyList(), emptyList(), body.size)

        val profile = header.indexOf("profile name")
        val start = header.indexOf("start time")
        return if (profile >= 0 && start >= 0) full(header, body, title, profile, start) else simple(header, body, title, zone)
    }

    private fun simple(header: List<String>, body: List<List<String>>, title: Int, zone: ZoneId): CsvParse {
        val dateCol = header.indexOf("date")
        val dates = body.map { it.getOrNull(dateCol).orEmpty().trim() }
        val dayFirst = dayFirst(dates)
        val items = mutableListOf<ImportItem>()
        var skipped = 0
        body.forEachIndexed { i, r ->
            val t = r.getOrNull(title)?.trim().orEmpty()
            if (t.isEmpty()) {
                skipped++
                return@forEachIndexed
            }
            val day = parseDate(dates[i], dayFirst)
            // Fără oră: considerăm seara (21:00), ca ordinea în zi să fie rezonabilă.
            val at = day?.atTime(21, 0)?.atZone(zone)?.toInstant()?.toEpochMilli() ?: 0L
            items += ImportItem(title = t, date = at)
        }
        return CsvParse(items, emptyList(), emptyList(), skipped)
    }

    private fun full(header: List<String>, body: List<List<String>>, title: Int, profile: Int, start: Int): CsvParse {
        val supplemental = header.indexOf("supplemental video type")
        val bookmark = header.indexOf("bookmark").takeIf { it >= 0 } ?: header.indexOf("latest bookmark")
        val items = mutableListOf<ImportItem>()
        val itemProfiles = mutableListOf<String>()
        var skipped = 0
        for (r in body) {
            val t = r.getOrNull(title)?.trim().orEmpty()
            // Trailere, reclame, „hook”-uri: nu sunt vizionări.
            val extra = r.getOrNull(supplemental)?.trim().orEmpty()
            if (t.isEmpty() || extra.isNotEmpty()) {
                skipped++
                continue
            }
            val at = runCatching {
                LocalDateTime.parse(r[start].trim(), FULL_FORMAT).toInstant(ZoneOffset.UTC).toEpochMilli()
            }.getOrDefault(0L)
            items += ImportItem(title = t, date = at, bookmark = r.getOrNull(bookmark)?.let(::hms))
            itemProfiles += r.getOrNull(profile)?.trim().orEmpty()
        }
        return CsvParse(items, itemProfiles.distinct().filter { it.isNotEmpty() }, itemProfiles, skipped)
    }

    private val FULL_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** „00:45:12” → secunde. */
    fun hms(value: String): Long? {
        val p = value.trim().split(':').map { it.toLongOrNull() ?: return null }
        return when (p.size) {
            3 -> p[0] * 3600 + p[1] * 60 + p[2]
            2 -> p[0] * 60 + p[1]
            else -> null
        }?.takeIf { it > 0 }
    }

    /** Ziua e prima (20/11/26, 20.11.2026) sau luna (11/20/26)? Ne uităm dacă vreun prim număr trece de 12. */
    private fun dayFirst(dates: List<String>): Boolean {
        if (dates.any { it.contains('.') }) return true
        val firsts = dates.mapNotNull { d -> d.split('/', '-').takeIf { it.size == 3 && it[0].length <= 2 }?.get(0)?.toIntOrNull() }
        val seconds = dates.mapNotNull { d -> d.split('/', '-').takeIf { it.size == 3 && it[0].length <= 2 }?.get(1)?.toIntOrNull() }
        return when {
            firsts.any { it > 12 } -> true
            seconds.any { it > 12 } -> false
            else -> false // Netflix în engleză (SUA): lună/zi/an
        }
    }

    fun parseDate(value: String, dayFirst: Boolean): LocalDate? {
        val v = value.trim()
        if (v.isEmpty()) return null
        val parts = v.split('/', '.', '-').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size != 3) return null
        val n = parts.map { it.toIntOrNull() ?: return null }
        return runCatching {
            if (parts[0].length == 4) {
                LocalDate.of(n[0], n[1], n[2])
            } else {
                val year = if (parts[2].length <= 2) 2000 + n[2] else n[2]
                if (dayFirst) LocalDate.of(year, n[1], n[0]) else LocalDate.of(year, n[0], n[1])
            }
        }.getOrNull()
    }

    /** CSV (RFC 4180): virgule, ghilimele, ghilimele dublate, rânduri noi în câmpuri. */
    fun rows(text: String): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        quoted = false
                    }
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> quoted = true
                    ',' -> {
                        row.add(field.toString())
                        field.clear()
                    }
                    '\r' -> Unit
                    '\n' -> {
                        row.add(field.toString())
                        field.clear()
                        out.add(row)
                        row = mutableListOf()
                    }
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            out.add(row)
        }
        return out
    }
}
