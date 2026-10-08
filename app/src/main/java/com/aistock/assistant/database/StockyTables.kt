package com.aistock.assistant.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/** The owner's own word for a product ("blue cotton" -> "BC-40-COTTON-BLUE"). Only saved after the owner agrees. */
@Entity(tableName = "business_aliases")
data class BusinessAliasEntity(
    @PrimaryKey val id: String,
    val phrase: String,
    /** Normalised phrase used for lookups; one alias per key. */
    val phraseKey: String,
    val productName: String,
    val createdAt: Long,
)

/** One AI write action with its before and after value, for the audit trail. */
@Entity(tableName = "ai_actions")
data class AiActionEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val command: String,
    val intent: String,
    val action: String,
    val subject: String,
    val beforeValue: String,
    val afterValue: String,
    val confirmed: Boolean,
    val result: String,
    val createdAt: Long,
)

/** A sheet of the owner's workbook that lives inside the app: created by talking to Stocky or kept from an import. */
@Entity(tableName = "workbook_sheets")
data class SheetEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** What the sheet holds: CUSTOM, PRODUCTS, CUSTOMERS ... */
    val purpose: String,
    /** JSON array of column names. */
    val columns: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "workbook_rows")
data class SheetRowEntity(
    @PrimaryKey val id: String,
    val sheetId: String,
    val position: Long,
    /** JSON object: column name -> text. */
    val cells: String,
    val createdAt: Long,
)

@Dao
interface StockyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveAlias(a: BusinessAliasEntity)
    @Query("DELETE FROM business_aliases WHERE phraseKey = :key") suspend fun deleteAlias(key: String)
    @Query("SELECT * FROM business_aliases ORDER BY phrase COLLATE NOCASE") suspend fun aliases(): List<BusinessAliasEntity>

    @Insert suspend fun insertAction(a: AiActionEntity)
    @Query("SELECT * FROM ai_actions ORDER BY createdAt DESC, rowid DESC LIMIT :limit") suspend fun actions(limit: Int): List<AiActionEntity>

    @Insert suspend fun insertSheet(s: SheetEntity)
    @Update suspend fun updateSheet(s: SheetEntity)
    @Query("SELECT * FROM workbook_sheets ORDER BY name COLLATE NOCASE") suspend fun sheets(): List<SheetEntity>
    @Insert suspend fun insertRow(r: SheetRowEntity)
    @Query("SELECT * FROM workbook_rows WHERE sheetId = :sheetId ORDER BY position, rowid") suspend fun rows(sheetId: String): List<SheetRowEntity>
    @Query("SELECT COUNT(*) FROM workbook_rows WHERE sheetId = :sheetId") suspend fun rowCount(sheetId: String): Int
}
