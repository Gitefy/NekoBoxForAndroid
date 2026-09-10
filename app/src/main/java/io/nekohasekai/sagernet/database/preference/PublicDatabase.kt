package io.nekohasekai.sagernet.database.preference

import androidx.room.Database
import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.matrix.roomigrant.GenerateRoomMigrations
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DbExecutors

@Database(entities = [KeyValuePair::class], version = 1)
@GenerateRoomMigrations
abstract class PublicDatabase : RoomDatabase() {
    companion object {
        val instance by lazy {
            SagerNet.application.getDatabasePath(Key.DB_PROFILE).parentFile?.mkdirs()
            Room.databaseBuilder(SagerNet.application, PublicDatabase::class.java, Key.DB_PUBLIC)
                // WAL: concurrent readers; settings writes no longer force full-file
                // checkpoints that serialized the :bg process behind fsyncs.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .enableMultiInstanceInvalidation()
                .fallbackToDestructiveMigration()
                .setQueryExecutor(DbExecutors.query)
                .setTransactionExecutor(DbExecutors.write)
                .build()
        }

        val kvPairDao get() = instance.keyValuePairDao()

        /**
         * Main-thread-safe invalidation feed for configuration preferences.
         * Backed by Room's own invalidation thread (own-process commits and
         * cross-process `enableMultiInstanceInvalidation` broadcasts).
         */
        val invalidationSource = RoomPreferenceDataStore.InvalidationSource { observe ->
            instance.invalidationTracker.addObserver(
                object : InvalidationTracker.Observer("KeyValuePair") {
                    override fun onInvalidated(tables: Set<String>) {
                        observe(tables)
                    }
                },
            )
        }
    }

    abstract fun keyValuePairDao(): KeyValuePair.Dao

}
