package de.mm20.launcher2.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration_33_34 : Migration(33, 34) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_Icons_packageName_activityName_iconPack` " +
                    "ON `Icons` (`packageName`, `activityName`, `iconPack`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_Icons_drawable_iconPack` " +
                    "ON `Icons` (`drawable`, `iconPack`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_CustomAttributes_key_type` " +
                    "ON `CustomAttributes` (`key`, `type`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_CustomAttributes_type_value` " +
                    "ON `CustomAttributes` (`type`, `value`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_Searchable_pinPosition_weight_launchCount` " +
                    "ON `Searchable` (`pinPosition`, `weight`, `launchCount`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_Searchable_type_hidden` " +
                    "ON `Searchable` (`type`, `hidden`)"
        )

        // Rebuild the Plugins table to drop the always-null settingsActivity column.
        // ALTER TABLE ... DROP COLUMN is not available on all supported SQLite versions.
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `Plugins_new` (" +
                    "`authority` TEXT NOT NULL, " +
                    "`label` TEXT NOT NULL, " +
                    "`description` TEXT, " +
                    "`packageName` TEXT NOT NULL, " +
                    "`className` TEXT NOT NULL, " +
                    "`type` TEXT NOT NULL, " +
                    "`enabled` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`authority`)" +
                    ")"
        )
        database.execSQL(
            "INSERT INTO `Plugins_new` (`authority`, `label`, `description`, `packageName`, `className`, `type`, `enabled`) " +
                    "SELECT `authority`, `label`, `description`, `packageName`, `className`, `type`, `enabled` FROM `Plugins`"
        )
        database.execSQL("DROP TABLE `Plugins`")
        database.execSQL("ALTER TABLE `Plugins_new` RENAME TO `Plugins`")
    }
}
