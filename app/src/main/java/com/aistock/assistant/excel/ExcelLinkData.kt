package com.aistock.assistant.excel

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * A column mapping the owner imported with, remembered by the sheet's header fingerprint ([HeaderMapper.signature])
 * so the same layout is understood the same way next time without asking the AI again.
 * @param fields JSON object: shared field name -> column title ("" = not used)
 */
@Entity(tableName = "column_maps")
data class ColumnMapEntity(@PrimaryKey val signature: String, val purpose: String, val fields: String, val updatedAt: Long)

/**
 * The owner's own Excel file that AIStock keeps up to date, after the owner turned this on.
 * Columns are found again by their title on every update (the owner may insert columns); the index is the fallback.
 * @param uri a document the owner picked, with a persisted read/write permission
 * @param salesSheet also keep an "AIStock Sales" sheet with the bills and total revenue (owner's choice)
 * @param addNewRows add products created in the app as new rows at the bottom
 */
@Entity(tableName = "excel_links")
data class ExcelLinkEntity(
    @PrimaryKey val id: String,
    val uri: String,
    val fileName: String,
    val sheetName: String,
    val nameHeader: String, val nameCol: Int,
    val sizeHeader: String, val sizeCol: Int,
    val stockHeader: String, val stockCol: Int,
    val priceHeader: String, val priceCol: Int,
    val enabled: Boolean,
    val addNewRows: Boolean,
    val salesSheet: Boolean,
    val lastSyncAt: Long?,
    val lastResult: String,
    val createdAt: Long,
)

@Dao
interface ExcelDao {
    @Query("SELECT * FROM column_maps WHERE signature = :signature")
    suspend fun columnMap(signature: String): ColumnMapEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveColumnMap(map: ColumnMapEntity)

    @Query("SELECT * FROM excel_links ORDER BY createdAt")
    suspend fun links(): List<ExcelLinkEntity>

    @Query("SELECT * FROM excel_links ORDER BY createdAt")
    fun observeLinks(): kotlinx.coroutines.flow.Flow<List<ExcelLinkEntity>>

    @Query("SELECT * FROM excel_links WHERE id = :id")
    suspend fun link(id: String): ExcelLinkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveLink(link: ExcelLinkEntity)

    @Query("UPDATE excel_links SET lastSyncAt = :at, lastResult = :result WHERE id = :id")
    suspend fun setResult(id: String, at: Long, result: String)

    @Query("DELETE FROM excel_links WHERE id = :id")
    suspend fun deleteLink(id: String)

    @Query("UPDATE excel_links SET enabled = 0")
    suspend fun disableAll()
}
