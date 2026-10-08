package com.aistock.assistant.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "checkpoint")
data class CheckpointEntity(@PrimaryKey val id: String, val name: String)

@Dao
interface CheckpointDao {
    @Insert suspend fun insert(e: CheckpointEntity)
    @Query("SELECT COUNT(*) FROM checkpoint") suspend fun count(): Int
}

@Database(entities = [CheckpointEntity::class], version = 1, exportSchema = false)
abstract class CheckpointDb : RoomDatabase() {
    abstract fun dao(): CheckpointDao
}
