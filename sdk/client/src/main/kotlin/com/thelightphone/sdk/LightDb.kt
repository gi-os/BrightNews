package com.thelightphone.sdk

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

fun <T : RoomDatabase> SealedLightContext.buildDatabase(dbClass: Class<T>, dbName: String?): T {
    return Room.databaseBuilder(androidContext.applicationContext, dbClass, dbName).build()
}

/**
 * Same as [buildDatabase], with schema migrations applied when the stored database is older
 * than the version declared on [dbClass].
 */
fun <T : RoomDatabase> SealedLightContext.buildDatabase(
    dbClass: Class<T>,
    dbName: String?,
    vararg migrations: Migration,
): T {
    return Room.databaseBuilder(androidContext.applicationContext, dbClass, dbName)
        .addMigrations(*migrations)
        .build()
}
