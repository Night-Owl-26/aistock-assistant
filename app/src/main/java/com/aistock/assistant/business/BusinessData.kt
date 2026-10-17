package com.aistock.assistant.business

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** The business configuration, one row, with every saved version kept. */
@Entity(tableName = "business_profiles")
data class BusinessProfileEntity(
    @PrimaryKey val version: Int,
    val config: String,
    val createdAt: Long,
    /** The server version this came from or was saved as; 0 when only on this phone. */
    val serverVersion: Int = 0,
)

@Entity(tableName = "staff")
data class StaffEntity(
    @PrimaryKey val id: String,
    val name: String,
    val phone: String = "",
    val role: String = "",
    val active: Boolean = true,
    val createdAt: Long,
)

/**
 * One unit of work in any module: a production order, a job card, an appointment, a project, an order or a task.
 * What a status or stage may be comes from the business configuration.
 */
@Entity(
    tableName = "work_items",
    indices = [Index("module", "status"), Index("dueAt"), Index("staffId"), Index(value = ["clientRef"], unique = true)],
)
data class WorkItemEntity(
    @PrimaryKey val id: String,
    val module: String,
    /** Human number such as PO-0007 or JOB-0031. */
    val number: String,
    val title: String,
    val customerId: String? = null,
    val productId: String? = null,
    val staffId: String? = null,
    val status: String,
    val stage: String? = null,
    val priority: Int = 0,
    /** Ordered quantity and how much is done, in thousandths. */
    val quantityMilli: Long? = null,
    val doneMilli: Long = 0,
    val amountPaise: Long? = null,
    val startAt: Long? = null,
    val dueAt: Long? = null,
    /** JSON object of the configuration's custom fields. */
    val fields: String = "{}",
    val notes: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val closedAt: Long? = null,
    /** Set when a voice or retried request created it, so the same request never creates two items. */
    val clientRef: String? = null,
)

/** History of a work item: nothing is overwritten without a trace. */
@Entity(tableName = "work_events", indices = [Index("workItemId")])
data class WorkEventEntity(
    @PrimaryKey val id: String,
    val workItemId: String,
    /** CREATED, STATUS, STAGE, PROGRESS, ASSIGN, DUE, NOTE */
    val kind: String,
    val fromValue: String = "",
    val toValue: String = "",
    val note: String = "",
    val source: String = "app",
    val createdAt: Long,
)

@Entity(tableName = "followups", indices = [Index("status", "dueAt"), Index("customerId"), Index(value = ["clientRef"], unique = true)])
data class FollowupEntity(
    @PrimaryKey val id: String,
    val customerId: String? = null,
    /** Name as said, kept even when no saved customer matches. */
    val personName: String,
    val phone: String = "",
    /** CALL, MESSAGE, VISIT, PAYMENT, OTHER */
    val kind: String,
    val note: String,
    val dueAt: Long,
    /** PENDING, DONE, CANCELLED */
    val status: String = "PENDING",
    val result: String = "",
    val workItemId: String? = null,
    val createdAt: Long,
    val completedAt: Long? = null,
    val clientRef: String? = null,
)

/** A spoken or written contact with a customer: calls made, messages prepared. Nothing here claims delivery. */
@Entity(tableName = "contact_log", indices = [Index("customerId")])
data class ContactLogEntity(
    @PrimaryKey val id: String,
    val customerId: String? = null,
    val personName: String,
    /** CALL, WHATSAPP, SMS, VISIT */
    val channel: String,
    val summary: String,
    val createdAt: Long,
)

@Entity(tableName = "reminders", indices = [Index("status", "dueAt")])
data class ReminderEntity(
    @PrimaryKey val id: String,
    val text: String,
    val dueAt: Long,
    /** SCHEDULED, SHOWN, CANCELLED */
    val status: String = "SCHEDULED",
    val followupId: String? = null,
    val workItemId: String? = null,
    val createdAt: Long,
)

/** A batch of a product with its expiry date (grocery, pharmacy, food). */
@Entity(tableName = "product_batches", indices = [Index("productId"), Index("expiryDate")])
data class ProductBatchEntity(
    @PrimaryKey val id: String,
    val productId: String,
    val batchNo: String = "",
    /** Start of the expiry day, epoch millis. */
    val expiryDate: Long,
    val quantityMilli: Long,
    val createdAt: Long,
)

data class WorkRow(
    val id: String, val module: String, val number: String, val title: String, val status: String, val stage: String?,
    val priority: Int, val quantityMilli: Long?, val doneMilli: Long, val amountPaise: Long?, val startAt: Long?, val dueAt: Long?,
    val customerId: String?, val customerName: String?, val staffId: String?, val staffName: String?, val productName: String?,
    val updatedAt: Long, val closedAt: Long?,
)

@Dao
interface WorkDao {
    // profile
    @Insert suspend fun insertProfile(p: BusinessProfileEntity)
    @Query("SELECT * FROM business_profiles ORDER BY version DESC LIMIT 1") suspend fun profile(): BusinessProfileEntity?
    @Query("SELECT * FROM business_profiles ORDER BY version DESC LIMIT 1") fun observeProfile(): Flow<BusinessProfileEntity?>

    // staff
    @Insert suspend fun insertStaff(s: StaffEntity)
    @Update suspend fun updateStaff(s: StaffEntity)
    @Query("SELECT * FROM staff WHERE active = 1 ORDER BY name COLLATE NOCASE") suspend fun staff(): List<StaffEntity>
    @Query("SELECT * FROM staff WHERE active = 1 ORDER BY name COLLATE NOCASE") fun observeStaff(): Flow<List<StaffEntity>>
    @Query("SELECT * FROM staff WHERE id = :id") suspend fun staffById(id: String): StaffEntity?

    // work items
    @Insert suspend fun insertWork(w: WorkItemEntity)
    @Update suspend fun updateWork(w: WorkItemEntity)
    @Insert suspend fun insertEvent(e: WorkEventEntity)
    @Query("SELECT * FROM work_items WHERE id = :id") suspend fun work(id: String): WorkItemEntity?
    @Query("SELECT * FROM work_items WHERE clientRef = :ref") suspend fun workByRef(ref: String): WorkItemEntity?
    @Query("SELECT * FROM work_items WHERE number = :number COLLATE NOCASE") suspend fun workByNumber(number: String): WorkItemEntity?
    @Query("SELECT COUNT(*) FROM work_items WHERE module = :module") suspend fun countWork(module: String): Int
    @Query("SELECT * FROM work_events WHERE workItemId = :id ORDER BY createdAt DESC, rowid DESC") suspend fun events(id: String): List<WorkEventEntity>
    @Query("SELECT * FROM work_events WHERE workItemId = :id ORDER BY createdAt DESC, rowid DESC") fun observeEvents(id: String): Flow<List<WorkEventEntity>>

    @Query(ROWS + " WHERE (:module IS NULL OR w.module = :module) ORDER BY (w.closedAt IS NOT NULL), (w.dueAt IS NULL), w.dueAt, w.updatedAt DESC LIMIT :limit")
    suspend fun rows(module: String?, limit: Int): List<WorkRow>
    @Query(ROWS + " WHERE w.module = :module ORDER BY (w.closedAt IS NOT NULL), (w.dueAt IS NULL), w.dueAt, w.updatedAt DESC LIMIT 500")
    fun observeRows(module: String): Flow<List<WorkRow>>
    @Query(ROWS + " WHERE w.id = :id") fun observeRow(id: String): Flow<WorkRow?>
    @Query("SELECT COUNT(*) FROM work_items WHERE closedAt IS NULL AND dueAt IS NOT NULL AND dueAt < :now") fun observeOverdue(now: Long): Flow<Int>

    // follow-ups
    @Insert suspend fun insertFollowup(f: FollowupEntity)
    @Update suspend fun updateFollowup(f: FollowupEntity)
    @Query("SELECT * FROM followups WHERE id = :id") suspend fun followup(id: String): FollowupEntity?
    @Query("SELECT * FROM followups WHERE clientRef = :ref") suspend fun followupByRef(ref: String): FollowupEntity?
    @Query("SELECT * FROM followups WHERE (:status IS NULL OR status = :status) ORDER BY (status != 'PENDING'), dueAt LIMIT :limit") suspend fun followups(status: String?, limit: Int): List<FollowupEntity>
    @Query("SELECT * FROM followups ORDER BY (status != 'PENDING'), dueAt LIMIT 500") fun observeFollowups(): Flow<List<FollowupEntity>>
    @Query("SELECT * FROM followups WHERE customerId = :customerId OR personName = :name COLLATE NOCASE ORDER BY dueAt DESC LIMIT 20") suspend fun followupsFor(customerId: String?, name: String): List<FollowupEntity>
    @Query("SELECT COUNT(*) FROM followups WHERE status = 'PENDING' AND dueAt < :now") fun observeOverdueFollowups(now: Long): Flow<Int>

    // contact log
    @Insert suspend fun insertContact(c: ContactLogEntity)
    @Query("SELECT * FROM contact_log WHERE customerId = :customerId OR personName = :name COLLATE NOCASE ORDER BY createdAt DESC LIMIT 20") suspend fun contactsFor(customerId: String?, name: String): List<ContactLogEntity>

    // reminders
    @Insert suspend fun insertReminder(r: ReminderEntity)
    @Update suspend fun updateReminder(r: ReminderEntity)
    @Query("SELECT * FROM reminders WHERE id = :id") suspend fun reminder(id: String): ReminderEntity?
    @Query("SELECT * FROM reminders WHERE status = 'SCHEDULED' ORDER BY dueAt") suspend fun scheduledReminders(): List<ReminderEntity>
    @Query("SELECT * FROM reminders ORDER BY (status != 'SCHEDULED'), dueAt DESC LIMIT 200") fun observeReminders(): Flow<List<ReminderEntity>>

    // batches
    @Insert suspend fun insertBatch(b: ProductBatchEntity)
    @Query("SELECT b.*, p.name AS productName, p.unit AS unit FROM product_batches b JOIN products p ON p.id = b.productId WHERE b.quantityMilli > 0 AND b.expiryDate <= :until ORDER BY b.expiryDate")
    suspend fun expiring(until: Long): List<BatchRow>

    @Query(
        "SELECT p.name AS name, p.unit AS unit, p.currentStock AS stockMilli, p.purchasePrice AS buyPaise, p.sellingPrice AS sellPaise, p.minimumStock AS minMilli, " +
            "(SELECT MAX(s.date) FROM sale_items i JOIN sales s ON s.id = i.saleId WHERE i.productId = p.id AND s.status != 'CANCELLED') AS lastSaleAt, p.createdAt AS createdAt " +
            "FROM products p WHERE p.active = 1"
    )
    suspend fun productActivity(): List<ProductActivity>

    companion object {
        const val ROWS = "SELECT w.id, w.module, w.number, w.title, w.status, w.stage, w.priority, w.quantityMilli, w.doneMilli, w.amountPaise, w.startAt, w.dueAt, " +
            "w.customerId, c.name AS customerName, w.staffId, s.name AS staffName, p.name AS productName, w.updatedAt, w.closedAt " +
            "FROM work_items w LEFT JOIN customers c ON c.id = w.customerId LEFT JOIN staff s ON s.id = w.staffId LEFT JOIN products p ON p.id = w.productId"
    }
}

data class BatchRow(
    val id: String, val productId: String, val batchNo: String, val expiryDate: Long, val quantityMilli: Long, val createdAt: Long,
    val productName: String, val unit: String,
)
