package com.crush.mobile.data.project

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Version 1 of the projects registry (ADR-0001 P2).
 *
 * exportSchema=false is deliberately explicit for version 1: there is no
 * migration yet, so there is nothing a schema JSON would protect. The first
 * schema change flips this to true and wires room.schemaLocation so the
 * exported schema becomes the migration's reference - claimed only when
 * exercised, not before.
 */
@Database(entities = [ProjectEntity::class], version = 1, exportSchema = false)
abstract class ProjectDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
}
