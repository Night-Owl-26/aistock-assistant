package com.aistock.assistant.business

import com.aistock.assistant.ai.ExecResult

/** In-memory business platform for tests, with the same rules as the real one. */
class FakeBusinessBackend(var cfg: BusinessConfig = Templates.DEFAULT, var isConfigured: Boolean = false) : BusinessBackend {
    val items = ArrayList<WorkRef>()
    val staffList = ArrayList<StaffRef>()
    val followupList = ArrayList<FollowupRef>()
    val contacts = ArrayList<Pair<String, ContactRef>>()
    val reminders = ArrayList<Pair<String, Long>>()
    val batches = ArrayList<BatchInfo>()
    val refs = HashSet<String>()
    var input: InsightsInput? = null
    private var seq = 0

    override suspend fun config() = cfg
    override suspend fun configured() = isConfigured
    override suspend fun work(module: Module?, openOnly: Boolean, limit: Int) = items.filter { (module == null || it.module == module) && (!openOnly || !it.closed) }.take(limit)
    override suspend fun staff() = staffList.toList()
    override suspend fun followups(pendingOnly: Boolean, limit: Int) = followupList.filter { !pendingOnly || it.status == "PENDING" }.take(limit)
    override suspend fun followupsFor(customerId: String?, name: String) = followupList.filter { it.customerId == customerId || it.personName.equals(name, true) }
    override suspend fun contactsFor(customerId: String?, name: String) = contacts.filter { it.first.equals(name, true) }.map { it.second }
    override suspend fun expiring(untilMillis: Long) = batches.filter { it.expiryDate <= untilMillis }
    override suspend fun insightsInput(now: Long, periodDays: Int) = input ?: InsightsInput(now, periodDays, 0, 0, 0, 0, 0, emptyList(), emptyList(), items.filter { !it.closed }, followupList.filter { it.status == "PENDING" }, batches)

    override suspend fun execute(action: BusinessAction): ExecResult {
        when (action) {
            is BusinessAction.SetupBusiness -> { if (action.config.validate().isNotEmpty()) return ExecResult(false, "INVALID"); cfg = action.config; isConfigured = true }
            is BusinessAction.NewWork -> {
                action.clientRef?.let { if (!refs.add(it)) return ExecResult(false, "DUPLICATE") }
                val prefix = mapOf(Module.PRODUCTION to "PO", Module.SERVICE_TICKETS to "JOB", Module.APPOINTMENTS to "APT", Module.PROJECTS to "PRJ", Module.ORDERS to "ORD").getOrDefault(action.module, "TSK")
                val n = "$prefix-%04d".format(items.count { it.module == action.module } + 1)
                items.add(WorkRef("w${++seq}", action.module, n, action.title, action.status, action.stage, action.customer?.id, action.customer?.name ?: action.newCustomerName,
                    action.staff?.id, action.staff?.name, action.product?.name, action.quantityMilli, 0, action.amountPaise, action.dueAt, action.startAt, false, action.priority))
                return ExecResult(true, reference = n)
            }
            is BusinessAction.UpdateWork -> {
                val i = items.indexOfFirst { it.id == action.item.id }
                if (i < 0) return ExecResult(false, "NOT_FOUND")
                val old = items[i]
                val wf = cfg.workflow(old.module)
                val status = action.status ?: old.status
                items[i] = old.copy(status = status, stage = action.stage ?: old.stage, doneMilli = old.doneMilli + (action.addDoneMilli ?: 0),
                    staffId = action.staff?.id ?: old.staffId, staffName = action.staff?.name ?: old.staffName, dueAt = action.dueAt ?: old.dueAt, closed = wf.isDone(status))
            }
            is BusinessAction.AddStaff -> staffList.add(StaffRef("s${++seq}", action.name, action.phone, action.role))
            is BusinessAction.NewFollowup -> {
                action.clientRef?.let { if (!refs.add(it)) return ExecResult(false, "DUPLICATE") }
                followupList.add(FollowupRef("f${++seq}", action.customer?.id, action.personName, action.phone, action.kind, action.note, action.dueAt, "PENDING", ""))
                if (action.remind) reminders.add(action.personName to action.dueAt)
            }
            is BusinessAction.CompleteFollowup -> {
                val i = followupList.indexOfFirst { it.id == action.followup.id }
                if (i < 0 || followupList[i].status != "PENDING") return ExecResult(false, "NOT_FOUND")
                followupList[i] = followupList[i].copy(status = "DONE", result = action.result)
                action.nextDueAt?.let { followupList.add(FollowupRef("f${++seq}", action.followup.customerId, action.followup.personName, action.followup.phone, action.followup.kind, action.nextNote ?: action.followup.note, it, "PENDING", "")) }
            }
            is BusinessAction.CallResult -> {
                contacts.add(action.personName to ContactRef("CALL", action.result, 0))
                action.followup?.let { f -> val i = followupList.indexOfFirst { it.id == f.id }; followupList[i] = followupList[i].copy(status = "DONE", result = action.result) }
                action.nextDueAt?.let { followupList.add(FollowupRef("f${++seq}", action.customer?.id, action.personName, action.customer?.phone.orEmpty(), "CALL", action.nextNote.orEmpty(), it, "PENDING", "")) }
            }
            is BusinessAction.NewReminder -> reminders.add(action.text to action.dueAt)
            is BusinessAction.LogContact -> contacts.add(action.personName to ContactRef(action.channel, action.summary, 0))
            is BusinessAction.AddBatch -> batches.add(BatchInfo(action.product.name, action.product.unit, action.batchNo, action.expiryDate, action.quantityMilli))
        }
        return ExecResult(true)
    }
}
