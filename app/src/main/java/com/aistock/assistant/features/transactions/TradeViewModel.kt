package com.aistock.assistant.features.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistock.assistant.ai.PartyKind
import com.aistock.assistant.data.InventoryRepository
import com.aistock.assistant.data.LineInput
import com.aistock.assistant.data.Op
import com.aistock.assistant.data.PartyRepository
import com.aistock.assistant.data.PartyRow
import com.aistock.assistant.data.TradeRepository
import com.aistock.assistant.database.ExpenseEntity
import com.aistock.assistant.database.ItemWithProduct
import com.aistock.assistant.database.PaymentEntity
import com.aistock.assistant.database.PaymentWithParty
import com.aistock.assistant.database.ProductEntity
import com.aistock.assistant.database.PurchaseEntity
import com.aistock.assistant.database.PurchaseWithParty
import com.aistock.assistant.database.SaleEntity
import com.aistock.assistant.database.SaleWithParty
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class TradeViewModel @Inject constructor(
    private val trade: TradeRepository,
    private val inventory: InventoryRepository,
    private val parties: PartyRepository,
) : ViewModel() {
    val query = MutableStateFlow("")
    private val q = query.debounce(150)

    val sales: StateFlow<List<SaleWithParty>> = q.flatMapLatest { trade.observeSales(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val purchases: StateFlow<List<PurchaseWithParty>> = q.flatMapLatest { trade.observePurchases(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val expenses: StateFlow<List<ExpenseEntity>> = q.flatMapLatest { trade.observeExpenses(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val payments: StateFlow<List<PaymentWithParty>> = trade.observePayments().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun products(): List<ProductEntity> = inventory.activeProducts()
    suspend fun partyList(kind: PartyKind): List<PartyRow> = parties.list(kind)
    suspend fun expenseCategories(): List<String> = trade.expenseCategories()
    suspend fun saleLines(id: String): List<ItemWithProduct> = trade.saleLines(id)
    suspend fun purchaseLines(id: String): List<ItemWithProduct> = trade.purchaseLines(id)

    suspend fun createSale(customerId: String?, lines: List<LineInput>, discount: Long, tax: Long, paid: Long, notes: String, date: Long): Op<SaleEntity> =
        trade.createSale(customerId, lines, discount, tax, paid, notes, date)

    suspend fun createPurchase(supplierId: String?, lines: List<LineInput>, discount: Long, tax: Long, paid: Long, notes: String, date: Long): Op<PurchaseEntity> =
        trade.createPurchase(supplierId, lines, discount, tax, paid, notes, date)

    suspend fun cancelSale(id: String): Op<Unit> = trade.cancelSale(id)
    suspend fun cancelPurchase(id: String): Op<Unit> = trade.cancelPurchase(id)
    suspend fun addExpense(category: String, description: String, amount: Long, method: String, date: Long): Op<ExpenseEntity> = trade.addExpense(category, description, amount, method, date)
    suspend fun cancelExpense(id: String): Op<Unit> = trade.cancelExpense(id)
    suspend fun addPayment(partyType: String, partyId: String, amount: Long, method: String, reference: String, date: Long, notes: String): Op<PaymentEntity> =
        trade.addPayment(partyType, partyId, amount, method, reference, date, notes)
    suspend fun cancelPayment(id: String): Op<Unit> = trade.cancelPayment(id)
}
