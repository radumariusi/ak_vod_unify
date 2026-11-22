package ro.ak.vodunify.core.imdb

import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Notele IMDb ale tuturor titlurilor, compact: id-ul numeric („tt0111161” → 111161), sortat, și nota × 10.
 * ~1,5 milioane de titluri încap în câțiva MB și se caută binar, fără rețea.
 */
class ImdbRatingsTable(private val ids: IntArray, private val ratings: ByteArray) {
    val size: Int get() = ids.size

    /** Nota IMDb pentru „tt…”, sau null dacă titlul nu are notă (ori are prea puține voturi). */
    fun rating(imdbId: String?): Double? {
        val n = numeric(imdbId) ?: return null
        val i = ids.binarySearch(n)
        return if (i >= 0) ratings[i] / 10.0 else null
    }

    fun write(out: OutputStream) {
        val d = DataOutputStream(out.buffered())
        d.writeInt(MAGIC)
        d.writeInt(ids.size)
        ids.forEach(d::writeInt)
        d.write(ratings)
        d.flush()
    }

    companion object {
        private const val MAGIC = 0x494D4442 // "IMDB"

        /** Titlurile cu mai puține voturi nu primesc notă (nota nu spune mare lucru). */
        const val MIN_VOTES = 50

        fun numeric(imdbId: String?): Int? =
            imdbId?.trim()?.takeIf { it.startsWith("tt") }?.substring(2)?.toIntOrNull()

        fun read(input: InputStream): ImdbRatingsTable {
            val d = DataInputStream(input.buffered())
            require(d.readInt() == MAGIC) { "fișier de note IMDb invalid" }
            val n = d.readInt()
            val ids = IntArray(n) { d.readInt() }
            val ratings = ByteArray(n)
            d.readFully(ratings)
            return ImdbRatingsTable(ids, ratings)
        }

        /**
         * Din fișierul oficial IMDb „title.ratings.tsv” (dezarhivat): antet „tconst averageRating numVotes”,
         * apoi „tt0000001	5.7	2100”, câte un titlu pe linie.
         */
        fun parseTsv(reader: BufferedReader, minVotes: Int = MIN_VOTES): ImdbRatingsTable {
            var ids = IntArray(1 shl 20)
            var ratings = ByteArray(1 shl 20)
            var n = 0
            reader.lineSequence().forEach { line ->
                val a = line.indexOf('\t')
                if (a < 0) return@forEach
                val b = line.indexOf('\t', a + 1)
                if (b < 0) return@forEach
                val id = numeric(line.substring(0, a)) ?: return@forEach // sare peste antet
                val votes = line.substring(b + 1).trim().toIntOrNull() ?: return@forEach
                if (votes < minVotes) return@forEach
                val rating = line.substring(a + 1, b).toDoubleOrNull() ?: return@forEach
                if (n == ids.size) {
                    ids = ids.copyOf(n * 2)
                    ratings = ratings.copyOf(n * 2)
                }
                ids[n] = id
                ratings[n] = Math.round(rating * 10).toInt().coerceIn(0, 100).toByte()
                n++
            }
            val outIds = ids.copyOf(n)
            val outRatings = ratings.copyOf(n)
            // Fișierul e sortat după tconst; ne asigurăm, pentru căutarea binară.
            if ((1 until n).any { outIds[it] < outIds[it - 1] }) {
                val order = (0 until n).sortedBy { outIds[it] }
                return ImdbRatingsTable(IntArray(n) { outIds[order[it]] }, ByteArray(n) { outRatings[order[it]] })
            }
            return ImdbRatingsTable(outIds, outRatings)
        }
    }
}
