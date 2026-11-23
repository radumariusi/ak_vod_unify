package ro.ak.vodunify.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/*
 * Migrările bazei locale. Baza e sursa adevărului (istoric, Continuă vizionarea), deci la orice schimbare
 * de schemă se adaugă aici o migrare, ca actualizarea aplicației să nu șteargă datele.
 */

/** v6: aplicația în care ai văzut ultima dată fiecare titlu început (sigla din Continuă vizionarea). */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE resume_points ADD COLUMN appPackage TEXT")
    }
}

/** v7: istoricul păstrează aplicația și momentul redării, inclusiv după terminarea unui titlu. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE watch_events ADD COLUMN appPackage TEXT")
        db.execSQL("ALTER TABLE watch_events ADD COLUMN playbackAt INTEGER")
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_5_6, MIGRATION_6_7)
