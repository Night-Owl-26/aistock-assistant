package com.aistock.assistant.business

import com.aistock.assistant.ai.ExecResult
import com.aistock.assistant.ai.PartyKind
import com.aistock.assistant.data.BizException
import com.aistock.assistant.data.ErrCode
import com.aistock.assistant.data.Op
import com.aistock.assistant.data.PartyInput
import com.aistock.assistant.data.PartyRepository
import com.aistock.assistant.data.fail
import com.aistock.assistant.data.newId
import com.aistock.assistant.data.op
import com.aistock.assistant.data.orThrow
import com.aistock.assistant.database.AppDatabase
import com.aistock.assistant.utils.NameMatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Something that can show a notification at a time; the Android implementation uses AlarmManager. */
interface ReminderSink {
    fun schedule(id: String, text: String, at: Long)
    fun cancel(id: String)
}

/**
 * The business platform on the phone's database. Every change runs in one transaction, is checked against the
 * business configuration, and leaves a history event. Work items and follow-ups created from a voice or retried
 * request carry a request id, so the same request never creates two records.
 */
@Singleton
class RoomBusinessBackend @Inject constructor(
    private val db: AppDatabase,
    private val parties: PartyRepository,
    private val reminders: ReminderSink,
) : BusinessBackend {
    private val dao get() = db.workDao()

    // ---------------------------------------------------------------- configuration

    override suspend fun config(): BusinessConfig = dao.profile()?.let { BusinessConfigJson.read(it.config) } ?: Templates.DEFAULT
    override suspend fun configured(): Boolean = dao.profile() != null
    fun observeConfig(): Flow<BusinessConfig> = dao.observeProfile().map { p -> p?.let { BusinessConfigJson.read(it.config) } ?: Templates.DEFAULT }
    fun observeConfigured(): Flow<Boolean> = dao.observeProfile().map { it != null }
    suspend fun profileVersion(): Int = dao.profile()?.version ?: 0

    suspend fun saveConfig(c: BusinessConfig, serverVersion: Int = 0): Op<Int> = db.op {
        if (c.validate().isNotEmpty()) fail(ErrCode.INVALID)
        val v = (dao.profile()?.version ?: 0) + 1
        dao.insertProfile(BusinessProfileEntity(v, BusinessConfigJson.write(c), System.currentTimeMillis(), serverVersion))
        v
    }

    // ---------------------------------------------------------------- reading

    private fun ref(r: WorkRow, cfg: BusinessConfig): WorkRef? {
        val m = Module.of(r.module) ?: return null
        return WorkRef(r.id, m, r.number, r.title, r.status, r.stage, r.customerId, r.customerName, r.staffId, r.staffName, r.productName,
            r.quantityMilli, r.doneMilli, r.amountPaise, r.dueAt, r.startAt, r.closedAt != null || cfg.workflow(m).isDone(r.status), r.priority)
    }

    override suspend fun work(module: Module?, openOnly: Boolean, limit: Int): List<WorkRef> {
        val cfg = config()
        return dao.rows(module?.key, if (openOnly) 2_000 else limit).mapNotNull { ref(it, cfg) }.filter { !openOnly || !it.closed }.take(limit)
    }

    fun observeWork(module: Module): Flow<List<WorkRow>> = dao.observeRows(module.key)
    fun observeItem(id: String): Flow<WorkRow?> = dao.observeRow(id)
    fun observeEvents(id: String) = dao.observeEvents(id)
    suspend fun item(id: String): WorkItemEntity? = dao.work(id)
    fun observeStaff() = dao.observeStaff()
    fun observeFollowups() = dao.observeFollowups()
    fun observeReminders() = dao.observeReminders()
    fun observeOverdueWork(now: Long) = dao.observeOverdue(now)
    fun observeOverdueFollowups(now: Long) = dao.observeOverdueFollowups(now)

    override suspend fun staff(): List<StaffRef> = dao.staff().map { StaffRef(it.id, it.name, it.phone, it.role) }

    private fun FollowupEntity.ref() = FollowupRef(id, customerId, personName, phone, kind, note, dueAt, status, result)
    override suspend fun followups(pendingOnly: Boolean, limit: Int) = dao.followups(if (pendingOnly) "PENDING" else null, limit).map { it.ref() }
    override suspend fun followupsFor(customerId: String?, name: String) = dao.followupsFor(customerId, name).map { it.ref() }
    override suspend fun contactsFor(customerId: String?, name: String) = dao.contactsFor(customerId, name).map { ContactRef(it.channel, it.summary, it.createdAt) }
    override suspend fun expiring(untilMillis: Long) = dao.expiring(untilMillis).map { BatchInfo(it.productName, it.unit, it.batchNo, it.expiryDate, it.quantityMilli) }

    override suspend fun insightsInput(now: Long, periodDays: Int): InsightsInput {
        val day = 86_400_000L
        val ist = 19_800_000L
        // Whole days in local time: "today" starts at midnight.
        val todayStart = now - Math.floorMod(now + ist, day)
        val from = todayStart - (periodDays - 1) * day
        val prevFrom = from - periodDays * day
        val t = db.tradeDao()
        val salesNow = t.saleTotals(from, now + 1)
        val salesPrev = t.saleTotals(prevFrom, from)
        val exNow = t.expensesByCategory(from, now + 1).sumOf { it.total }
        val exPrev = t.expensesByCategory(prevFrom, from).sumOf { it.total }
        return InsightsInput(
            now, periodDays, salesNow.total, salesPrev.total, salesNow.count, exNow, exPrev,
            t.salesByProduct(from, now + 1).map { ProductSale(it.productName, it.unit, it.quantity, it.total) },
            dao.productActivity(), work(null, openOnly = true, limit = 500), followups(pendingOnly = true, limit = 200),
            expiring(now + 7 * day),
        )
    }

    // ---------------------------------------------------------------- writing

    private fun prefix(m: Module) = when (m) {
        Module.PRODUCTION -> "PO"; Module.SERVICE_TICKETS -> "JOB"; Module.APPOINTMENTS -> "APT"
        Module.PROJECTS -> "PRJ"; Module.ORDERS -> "ORD"; else -> "TSK"
    }

    private suspend fun event(id: String, kind: String, from: String, to: String, note: String = "", source: String = "app") =
        dao.insertEvent(WorkEventEntity(newId(), id, kind, from.take(120), to.take(120), note.take(500), source, System.currentTimeMillis()))

    /** Used by Stocky (through the confirmation card) and by the screens. */
    /** Called after the business setup is saved, so it can be copied to the owner's server. */
    @Volatile var onSetupSaved: (suspend (BusinessConfig) -> Unit)? = null

    override suspend fun execute(action: BusinessAction): ExecResult {
        var reminder: Triple<String, String, Long>? = null
        val cancelled = ArrayList<String>()
        val r: Op<ExecResult> = db.op {
            val now = System.currentTimeMillis()
            val cfg = config()
            when (action) {
                is BusinessAction.SetupBusiness -> {
                    if (action.config.validate().isNotEmpty()) fail(ErrCode.INVALID)
                    val prev = dao.profile()
                    // The new version remembers which server version it was based on, for conflict checks.
                    dao.insertProfile(BusinessProfileEntity((prev?.version ?: 0) + 1, BusinessConfigJson.write(action.config), now, prev?.serverVersion ?: 0))
                    ExecResult(true)
                }
                is BusinessAction.NewWork -> {
                    action.clientRef?.let { if (dao.workByRef(it) != null) fail(ErrCode.DUPLICATE) }
                    if (!cfg.has(action.module)) fail(ErrCode.INVALID)
                    val flow = cfg.workflow(action.module)
                    if (flow.status(action.status) == null) fail(ErrCode.INVALID)
                    if (action.stage != null && flow.stage(action.stage) == null) fail(ErrCode.INVALID)
                    if (action.title.isBlank()) fail(ErrCode.INVALID)
                    val customerId = action.customer?.id ?: action.newCustomerName?.takeIf { it.isNotBlank() }?.let { parties.create(PartyKind.CUSTOMER, PartyInput(it)).orThrow() }
                    val number = prefix(action.module) + "-%04d".format(dao.countWork(action.module.key) + 1)
                    val id = newId()
                    val fields = JSONObject(); for ((k, v) in action.fields) fields.put(k, v)
                    dao.insertWork(WorkItemEntity(id, action.module.key, number, action.title.trim().take(120), customerId, action.product?.id, action.staff?.id,
                        flow.status(action.status)!!, action.stage?.let { flow.stage(it) }, action.priority, action.quantityMilli, 0, action.amountPaise,
                        action.startAt, action.dueAt, fields.toString(), action.notes.take(1000), now, now, null, action.clientRef))
                    event(id, "CREATED", "", number)
                    ExecResult(true, reference = number)
                }
                is BusinessAction.UpdateWork -> {
                    val old = dao.work(action.item.id) ?: fail(ErrCode.NOT_FOUND)
                    val m = Module.of(old.module) ?: fail(ErrCode.INVALID)
                    val flow = cfg.workflow(m)
                    var w = old
                    action.status?.let { s ->
                        val st = flow.status(s) ?: fail(ErrCode.INVALID)
                        if (!st.equals(w.status, true)) { event(w.id, "STATUS", w.status, st); w = w.copy(status = st, closedAt = if (flow.isDone(st)) now else null) }
                    }
                    action.stage?.let { s ->
                        val st = flow.stage(s) ?: fail(ErrCode.INVALID)
                        if (!st.equals(w.stage, true)) { event(w.id, "STAGE", w.stage.orEmpty(), st); w = w.copy(stage = st) }
                    }
                    action.addDoneMilli?.let { q ->
                        if (q <= 0) fail(ErrCode.INVALID)
                        event(w.id, "PROGRESS", w.doneMilli.toString(), (w.doneMilli + q).toString())
                        w = w.copy(doneMilli = w.doneMilli + q)
                    }
                    action.staff?.let { s -> if (s.id != w.staffId) { event(w.id, "ASSIGN", w.staffId.orEmpty(), s.name); w = w.copy(staffId = s.id) } }
                    action.dueAt?.let { d -> event(w.id, "DUE", w.dueAt?.toString().orEmpty(), d.toString()); w = w.copy(dueAt = d) }
                    if (action.fields.isNotEmpty()) {
                        val o = try { JSONObject(w.fields) } catch (e: Exception) { JSONObject() }
                        for ((k, v) in action.fields) { event(w.id, "FIELD", "$k=${o.optString(k)}", "$k=$v"); o.put(k, v) }
                        w = w.copy(fields = o.toString())
                    }
                    action.note?.takeIf { it.isNotBlank() }?.let { n -> event(w.id, "NOTE", "", "", n) }
                    dao.updateWork(w.copy(updatedAt = now))
                    ExecResult(true, reference = w.number)
                }
                is BusinessAction.AddStaff -> {
                    if (action.name.isBlank()) fail(ErrCode.INVALID)
                    if (dao.staff().any { NameMatcher.key(it.name) == NameMatcher.key(action.name) }) fail(ErrCode.DUPLICATE)
                    dao.insertStaff(StaffEntity(newId(), action.name.trim(), action.phone, action.role, true, now))
                    ExecResult(true)
                }
                is BusinessAction.NewFollowup -> {
                    action.clientRef?.let { if (dao.followupByRef(it) != null) fail(ErrCode.DUPLICATE) }
                    if (action.personName.isBlank()) fail(ErrCode.INVALID)
                    val id = newId()
                    dao.insertFollowup(FollowupEntity(id, action.customer?.id, action.personName.trim(), action.phone, action.kind, action.note, action.dueAt, "PENDING", "", action.workItemId, now, null, action.clientRef))
                    if (action.remind) {
                        val rid = newId()
                        val text = "${action.kind.lowercase().replaceFirstChar { it.uppercase() }} ${action.personName}" + if (action.note.isNotBlank()) " – ${action.note}" else ""
                        dao.insertReminder(ReminderEntity(rid, text, action.dueAt, "SCHEDULED", id, action.workItemId, now))
                        reminder = Triple(rid, text, action.dueAt)
                    }
                    ExecResult(true)
                }
                is BusinessAction.CompleteFollowup -> {
                    val f = dao.followup(action.followup.id) ?: fail(ErrCode.NOT_FOUND)
                    if (f.status != "PENDING") fail(ErrCode.NOT_FOUND)
                    dao.updateFollowup(f.copy(status = "DONE", result = action.result, completedAt = now))
                    for (r in dao.remindersFor(f.id)) { dao.updateReminder(r.copy(status = "CANCELLED")); cancelled.add(r.id) }
                    dao.insertContact(ContactLogEntity(newId(), f.customerId, f.personName, if (f.kind == "MESSAGE") "MESSAGE" else f.kind, action.result, now))
                    action.nextDueAt?.let { d ->
                        dao.insertFollowup(FollowupEntity(newId(), f.customerId, f.personName, f.phone, f.kind, action.nextNote ?: f.note, d, "PENDING", "", f.workItemId, now))
                        val rid = newId(); val text = "${f.kind.lowercase().replaceFirstChar { it.uppercase() }} ${f.personName}"
                        dao.insertReminder(ReminderEntity(rid, text, d, "SCHEDULED", null, f.workItemId, now)); reminder = Triple(rid, text, d)
                    }
                    ExecResult(true)
                }
                is BusinessAction.CallResult -> {
                    dao.insertContact(ContactLogEntity(newId(), action.customer?.id, action.personName, "CALL", action.result, now))
                    action.followup?.let { fr ->
                        val f = dao.followup(fr.id)
                        if (f != null && f.status == "PENDING") {
                            dao.updateFollowup(f.copy(status = "DONE", result = action.result, completedAt = now))
                            for (r in dao.remindersFor(f.id)) { dao.updateReminder(r.copy(status = "CANCELLED")); cancelled.add(r.id) }
                        }
                    }
                    action.nextDueAt?.let { d ->
                        dao.insertFollowup(FollowupEntity(newId(), action.customer?.id, action.personName, action.customer?.phone.orEmpty(), "CALL", action.nextNote.orEmpty(), d, "PENDING", "", null, now))
                        val rid = newId(); val text = "Call ${action.personName}" + (action.nextNote?.let { " – $it" } ?: "")
                        dao.insertReminder(ReminderEntity(rid, text, d, "SCHEDULED", null, null, now)); reminder = Triple(rid, text, d)
                    }
                    ExecResult(true)
                }
                is BusinessAction.NewReminder -> {
                    if (action.text.isBlank()) fail(ErrCode.INVALID)
                    val rid = newId()
                    dao.insertReminder(ReminderEntity(rid, action.text, action.dueAt, "SCHEDULED", null, null, now))
                    reminder = Triple(rid, action.text, action.dueAt)
                    ExecResult(true)
                }
                is BusinessAction.LogContact -> {
                    dao.insertContact(ContactLogEntity(newId(), action.customer?.id, action.personName, action.channel, action.summary.take(300), now))
                    ExecResult(true)
                }
                is BusinessAction.AddBatch -> {
                    if (action.quantityMilli <= 0) fail(ErrCode.INVALID)
                    dao.insertBatch(ProductBatchEntity(newId(), action.product.id, action.batchNo, action.expiryDate, action.quantityMilli, now))
                    ExecResult(true)
                }
            }
        }
        return when (r) {
            is Op.Ok -> {
                // The alarm is set only after the reminder is safely saved.
                reminder?.let { (id, text, at) -> try { reminders.schedule(id, text, at) } catch (e: Exception) { } }
                for (id in cancelled) try { reminders.cancel(id) } catch (e: Exception) { }
                if (action is BusinessAction.SetupBusiness) try { onSetupSaved?.invoke(action.config) } catch (e: Exception) { }
                r.value
            }
            is Op.Err -> ExecResult(false, r.code)
        }
    }

    suspend fun cancelReminder(id: String) {
        val r = dao.reminder(id) ?: return
        dao.updateReminder(r.copy(status = "CANCELLED"))
        reminders.cancel(id)
    }

    /** @return false when the reminder was cancelled meanwhile (e.g. its follow-up was done), so nothing is shown. */
    suspend fun reminderDue(id: String): Boolean {
        val r = dao.reminder(id) ?: return false
        if (r.status != "SCHEDULED") return false
        dao.updateReminder(r.copy(status = "SHOWN"))
        return true
    }

    suspend fun latestProfile() = dao.profile()
    suspend fun markSynced(version: Int, serverVersion: Int) = dao.setServerVersion(version, serverVersion)

    /** After a restart or an update the alarms are gone; set them again. */
    suspend fun rescheduleAll() {
        for (r in dao.scheduledReminders()) try { reminders.schedule(r.id, r.text, r.dueAt) } catch (e: Exception) { }
    }

    suspend fun archiveStaff(id: String) { dao.staffById(id)?.let { dao.updateStaff(it.copy(active = false)) } }

    @Suppress("unused") private fun unused(e: BizException) = e
}
