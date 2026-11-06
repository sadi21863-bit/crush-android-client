package com.crush.mobile.data.project

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 2 of the projects registry (ADR-0001 P2, extended by S-02).
 *
 * exportSchema=false was deliberately explicit for version 1 - "there is
 * nothing a schema JSON would protect". The S-02 trust columns are version
 * 2's additive migration below, written and tested as hand-SQL; the schema
 * JSON stays off until a breaking (non-additive) change makes the export
 * worth its tooling, per this file's original accounting.
 */
@Database(entities = [ProjectEntity::class], version = 2, exportSchema = false)
abstract class ProjectDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {

        /**
         * v1 -> v2 (S-02, 2026-11-05): the per-row crushrc consent record.
         * Additive ALTER TABLEs only - no row shape changes, no rewrite, so
         * every existing project row (and its byte-exact repo_dir, which the
         * engine keys workspaces on) survives verbatim. Nullable columns on
         * purpose: NULL means "no consent recorded", and rewriting existing
         * rows is forbidden by the migration invariant.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN trust_fingerprint TEXT")
                db.execSQL("ALTER TABLE projects ADD COLUMN consented_at_ms INTEGER")
            }
        }
    }
}
