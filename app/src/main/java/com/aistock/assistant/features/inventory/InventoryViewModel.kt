package com.aistock.assistant.features.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistock.assistant.data.InventoryRepository
import com.aistock.assistant.data.Op
import com.aistock.assistant.data.ProductInput
import com.aistock.assistant.database.CategoryEntity
import com.aistock.assistant.database.CategoryWithCount
import com.aistock.assistant.database.InventoryTransactionEntity
import com.aistock.assistant.database.MovementWithProduct
import com.aistock.assistant.database.ProductEntity
import com.aistock.assistant.database.ProductWithCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class InventoryViewModel @Inject constructor(private val repo: InventoryRepository) : ViewModel() {
    val query = MutableStateFlow("")
    val historyQuery = MutableStateFlow("")

    val products: StateFlow<List<ProductWithCategory>> = query.debounce(150).flatMapLatest { repo.observeProducts(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories: StateFlow<List<CategoryWithCount>> = repo.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val movements: StateFlow<List<MovementWithProduct>> = historyQuery.debounce(150).flatMapLatest { repo.observeMovements(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun product(id: String): ProductEntity? = repo.product(id)
    suspend fun activeProducts(): List<ProductEntity> = repo.activeProducts()
    suspend fun activeCategories(): List<CategoryEntity> = repo.activeCategories()
    suspend fun create(input: ProductInput): Op<ProductEntity> = repo.createProduct(input)
    suspend fun update(id: String, input: ProductInput): Op<ProductEntity> = repo.updateProduct(id, input)
    suspend fun remove(id: String): Op<Boolean> = repo.removeProduct(id)
    suspend fun saveCategory(id: String?, name: String, description: String): Op<CategoryEntity> = repo.saveCategory(id, name, description)
    suspend fun removeCategory(id: String): Op<Unit> = repo.removeCategory(id)
    suspend fun stockIn(productId: String, qty: Long, type: String, unitCost: Long?, notes: String): Op<InventoryTransactionEntity> = repo.stockIn(productId, qty, type, unitCost, notes)
    suspend fun stockOut(productId: String, qty: Long, type: String, notes: String): Op<InventoryTransactionEntity> = repo.stockOut(productId, qty, type, notes)
    suspend fun adjustTo(productId: String, qty: Long, notes: String): Op<InventoryTransactionEntity> = repo.adjustTo(productId, qty, notes)
    suspend fun undo(transactionId: String): Op<InventoryTransactionEntity> = repo.undoMovement(transactionId)
}
