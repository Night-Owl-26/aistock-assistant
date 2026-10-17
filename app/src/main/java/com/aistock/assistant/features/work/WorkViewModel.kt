package com.aistock.assistant.features.work

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistock.assistant.ai.ExecResult
import com.aistock.assistant.ai.PartyKind
import com.aistock.assistant.ai.PartyRef
import com.aistock.assistant.ai.ProductRef
import com.aistock.assistant.business.BusinessAction
import com.aistock.assistant.business.BusinessConfig
import com.aistock.assistant.business.BusinessConfigJson
import com.aistock.assistant.business.FollowupEntity
import com.aistock.assistant.business.Insight
import com.aistock.assistant.business.Insights
import com.aistock.assistant.business.Module
import com.aistock.assistant.business.ReminderEntity
import com.aistock.assistant.business.RoomBusinessBackend
import com.aistock.assistant.business.StaffEntity
import com.aistock.assistant.business.StaffRef
import com.aistock.assistant.business.Templates
import com.aistock.assistant.business.WorkEventEntity
import com.aistock.assistant.business.WorkRef
import com.aistock.assistant.business.WorkRow
import com.aistock.assistant.data.InventoryRepository
import com.aistock.assistant.data.PartyRepository
import com.aistock.assistant.data.SettingsStore
import com.aistock.assistant.server.ServerApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

/** Screens for the business platform: setup, work items, staff, follow-ups and reminders. */
@HiltViewModel
class WorkViewModel @Inject constructor(
    val platform: RoomBusinessBackend,
    private val parties: PartyRepository,
    private val inventory: InventoryRepository,
    private val settings: SettingsStore,
    private val server: ServerApi,
) : ViewModel() {
    val config: StateFlow<BusinessConfig> = platform.observeConfig().stateIn(viewModelScope, SharingStarted.Eagerly, Templates.DEFAULT)
    val configured: StateFlow<Boolean?> = platform.observeConfigured().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val staff: StateFlow<List<StaffEntity>> = platform.observeStaff().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val followups: StateFlow<List<FollowupEntity>> = platform.observeFollowups().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val reminders: StateFlow<List<ReminderEntity>> = platform.observeReminders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun rows(module: Module): Flow<List<WorkRow>> = platform.observeWork(module)
    fun row(id: String): Flow<WorkRow?> = platform.observeItem(id)
    fun events(id: String): Flow<List<WorkEventEntity>> = platform.observeEvents(id)
    suspend fun fields(id: String): Map<String, String> = platform.item(id)?.fields?.let { f ->
        try { val o = JSONObject(f); o.keys().asSequence().associateWith { o.optString(it) } } catch (e: Exception) { emptyMap() }
    } ?: emptyMap()
    suspend fun notes(id: String): String = platform.item(id)?.notes.orEmpty()

    suspend fun customers(): List<PartyRef> = parties.list(PartyKind.CUSTOMER).map { PartyRef(it.id, it.name, it.phone, it.balance, PartyKind.CUSTOMER) }
    suspend fun products(): List<ProductRef> = inventory.activeProducts().map { ProductRef(it.id, it.name, it.unit, it.currentStock, it.minimumStock, it.sellingPrice, it.purchasePrice) }
    suspend fun staffRefs(): List<StaffRef> = platform.staff()

    suspend fun run(action: BusinessAction): ExecResult = platform.execute(action)

    /** Saves the setup on the phone; ProfileSync copies it to the server when signed in. */
    suspend fun saveSetup(c: BusinessConfig): ExecResult = platform.execute(BusinessAction.SetupBusiness(c))

    fun ref(r: WorkRow, cfg: BusinessConfig): WorkRef? = Module.of(r.module)?.let { m ->
        WorkRef(r.id, m, r.number, r.title, r.status, r.stage, r.customerId, r.customerName, r.staffId, r.staffName, r.productName,
            r.quantityMilli, r.doneMilli, r.amountPaise, r.dueAt, r.startAt, r.closedAt != null || cfg.workflow(m).isDone(r.status), r.priority)
    }

    suspend fun briefing(days: Int): List<Insight> =
        Insights.build(platform.insightsInput(System.currentTimeMillis(), days), settings.current.value.currencySymbol)

    fun cancelReminder(id: String) = viewModelScope.launch { platform.cancelReminder(id) }
    fun archiveStaff(id: String) = viewModelScope.launch { platform.archiveStaff(id) }
}
