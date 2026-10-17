package com.aistock.assistant.business

import com.aistock.assistant.ai.ExecResult
import com.aistock.assistant.ai.PartyRef
import com.aistock.assistant.ai.ProductRef

data class WorkRef(
    val id: String, val module: Module, val number: String, val title: String, val status: String, val stage: String?,
    val customerId: String?, val customerName: String?, val staffId: String?, val staffName: String?, val productName: String?,
    val quantityMilli: Long?, val doneMilli: Long, val amountPaise: Long?, val dueAt: Long?, val startAt: Long?, val closed: Boolean, val priority: Int,
)

data class StaffRef(val id: String, val name: String, val phone: String, val role: String)

data class FollowupRef(
    val id: String, val customerId: String?, val personName: String, val phone: String, val kind: String, val note: String,
    val dueAt: Long, val status: String, val result: String,
)

data class ContactRef(val channel: String, val summary: String, val at: Long)

/** A fully checked change to the business platform, shown to the owner before it is saved. */
sealed class BusinessAction {
    data class SetupBusiness(val config: BusinessConfig) : BusinessAction()
    data class NewWork(
        val module: Module, val title: String, val customer: PartyRef?, val newCustomerName: String?, val product: ProductRef?,
        val staff: StaffRef?, val status: String, val stage: String?, val quantityMilli: Long?, val amountPaise: Long?,
        val startAt: Long?, val dueAt: Long?, val priority: Int, val fields: Map<String, String>, val notes: String, val clientRef: String?,
    ) : BusinessAction()
    data class UpdateWork(
        val item: WorkRef, val status: String?, val stage: String?, val addDoneMilli: Long?, val staff: StaffRef?,
        val dueAt: Long?, val note: String?, val fields: Map<String, String> = emptyMap(),
    ) : BusinessAction()
    data class AddStaff(val name: String, val phone: String, val role: String) : BusinessAction()
    data class NewFollowup(
        val customer: PartyRef?, val personName: String, val phone: String, val kind: String, val note: String,
        val dueAt: Long, val remind: Boolean, val workItemId: String?, val clientRef: String?,
    ) : BusinessAction()
    data class CompleteFollowup(val followup: FollowupRef, val result: String, val nextDueAt: Long?, val nextNote: String?) : BusinessAction()
    data class NewReminder(val text: String, val dueAt: Long) : BusinessAction()
    data class LogContact(val customer: PartyRef?, val personName: String, val channel: String, val summary: String) : BusinessAction()
    /** The result of a call: logged, the matching follow-up closed, and the next one booked when asked. */
    data class CallResult(val customer: PartyRef?, val personName: String, val result: String, val followup: FollowupRef?, val nextDueAt: Long?, val nextNote: String?) : BusinessAction()
    data class AddBatch(val product: ProductRef, val batchNo: String, val expiryDate: Long, val quantityMilli: Long) : BusinessAction()
}

data class BatchInfo(val productName: String, val unit: String, val batchNo: String, val expiryDate: Long, val quantityMilli: Long)

/** The business platform as Stocky and the screens see it. Every write goes through [execute]. */
interface BusinessBackend {
    suspend fun config(): BusinessConfig
    /** Saved at least once by the owner (or Stocky with the owner's yes). */
    suspend fun configured(): Boolean
    suspend fun work(module: Module?, openOnly: Boolean, limit: Int = 60): List<WorkRef>
    suspend fun staff(): List<StaffRef>
    suspend fun followups(pendingOnly: Boolean, limit: Int = 60): List<FollowupRef>
    suspend fun followupsFor(customerId: String?, name: String): List<FollowupRef>
    suspend fun contactsFor(customerId: String?, name: String): List<ContactRef>
    suspend fun expiring(untilMillis: Long): List<BatchInfo>
    suspend fun insightsInput(now: Long, periodDays: Int): InsightsInput
    suspend fun execute(action: BusinessAction): ExecResult
}

/** A product with when it last sold, for suggestions. */
data class ProductActivity(
    val name: String, val unit: String, val stockMilli: Long, val buyPaise: Long, val sellPaise: Long, val minMilli: Long,
    val lastSaleAt: Long?, val createdAt: Long,
)
