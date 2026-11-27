package com.crush.mobile.data.project

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.crush.mobile.data.handoff.HandoffDao
import com.crush.mobile.data.handoff.HandoffEntity
import com.crush.mobile.execution.ExecutionDao
import com.crush.mobile.execution.ExecutionEntity

/**
 * Version 2 of the projects registry (ADR-0001 P2, extended by S-02);
 * version 3 adds the Chat to Code handoff trace table (P6, 2026-11-08,
 * MIGRATION_2_3 below) as a plain additive CREATE TABLE beside it - no
 * existing row changes shape. Version 4 adds the V2-16 A2 executions run trace
 * (MIGRATION_3_4), also additive.
 *
 * exportSchema=false was deliberately explicit for version 1 - "there is
 * nothing a schema JSON would protect". The S-02 trust columns are version
 * 2's additive migration below, written and tested as hand-SQL; the schema
 * JSON stays off until a breaking (non-additive) change makes the export
 * worth its tooling, per this file's original accounting.
 */
@Database(
    entities = [ProjectEntity::class, HandoffEntity::class, ExecutionEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class ProjectDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    /** The P6 handoff trace surface (see [HandoffDao]). */
    abstract fun handoffDao(): HandoffDao

    /** V2-16 A2 slice 1: the additive executions table (see [ExecutionDao]). */
    abstract fun executionDao(): ExecutionDao

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

        /**
         * v2 -> v3 (P6 Chat to Code handoff, 2026-11-08): the `handoffs`
         * trace table. Additive CREATE TABLE only - every existing projects
         * row (and the S-02 trust columns it carries) survives byte-exact;
         * no existing row is rewritten or touched. Column names are the
         * plan §9 fields verbatim (this file's only camelCase table - the
         * projects table's snake_case is local convention).
         *
         * The `status` CHECK lives HERE and nowhere else: Room carries no
         * CHECK support in @Entity, and this is the only hand-authored DDL
         * the handoffs table ever gets through the migration path. The
         * HandoffDao's guarded transitions are the enforcement that covers
         * every install, including fresh installs whose Room-generated DDL
         * has no CHECK clause (see HandoffEntity's KDoc).
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS handoffs (" +
                        "handoffId TEXT NOT NULL PRIMARY KEY, " +
                        "sourceChatId TEXT NOT NULL, " +
                        "targetProjectId TEXT NOT NULL, " +
                        "targetCodeSessionId TEXT, " +
                        "summaryMarkdown TEXT NOT NULL, " +
                        "userApprovedAt INTEGER, " +
                        "selectedReferences TEXT NOT NULL, " +
                        "projectTreeFingerprintAtHandoff TEXT, " +
                        "expiryPolicy TEXT NOT NULL, " +
                        "sourceProvider TEXT NOT NULL, " +
                        "status TEXT NOT NULL CHECK (status IN " +
                        "('DRAFT','APPROVED','DISPATCHED','ABANDONED')))"
                )
            }
        }

        /**
         * v3 -> v4 (V2-16 A2 slice 1): the additive `executions` run trace.
         * Plain CREATE TABLE + indexes only - neither `projects` nor `handoffs`
         * rows change shape. No ForeignKey/cascade: the trace survives project
         * removal. The column names are the design §5 names verbatim and must
         * stay identical to ExecutionEntity's @ColumnInfo values.
         *
         * NOTE: the design draft's leading `ALTER TABLE executions ...` is a
         * typo - the table does not exist in v3. Only CREATE TABLE is valid,
         * matching the v2->v3 precedent above.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS executions (" +
                        "execution_id TEXT NOT NULL PRIMARY KEY, " +
                        "run_id TEXT NOT NULL, " +
                        "session_id TEXT NOT NULL, " +
                        "engine_key_token TEXT NOT NULL, " +
                        "workspace_path TEXT NOT NULL, " +
                        "engine_generation INTEGER, " +
                        "lease_at_ms INTEGER, " +
                        "phase TEXT NOT NULL, " +
                        "progress_at_ms INTEGER, " +
                        "created_at_ms INTEGER NOT NULL, " +
                        "updated_at_ms INTEGER NOT NULL, " +
                        "prompt_ref TEXT, " +
                        "terminal_reason TEXT, " +
                        "ack_state TEXT NOT NULL" +
                        ")"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_exec_session " +
                        "ON executions(session_id, updated_at_ms)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_exec_engine " +
                        "ON executions(engine_key_token, phase)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_exec_path ON executions(workspace_path)"
                )
            }
        }
    }
}
