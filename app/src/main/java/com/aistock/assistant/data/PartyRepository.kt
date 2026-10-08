package com.aistock.assistant.data

import com.aistock.assistant.ai.PartyKind
import com.aistock.assistant.database.AppDatabase
import com.aistock.assistant.database.CustomerEntity
import com.aistock.assistant.database.SupplierEntity
import com.aistock.assistant.utils.NameMatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class PartyInput(
    val name: String, val phone: String = "", val email: String = "", val address: String = "",
    val openingBalance: Long = 0, val notes: String = "",
)

/** A customer or supplier with the amount currently outstanding. */
data class PartyRow(
    val id: String, val kind: PartyKind, val name: String, val phone: String, val email: String, val address: String,
    val openingBalance: Long, val notes: String, val balance: Long,
)

@Singleton
class PartyRepository @Inject constructor(private val db: AppDatabase) {
    private val dao get() = db.partyDao()

    suspend fun create(kind: PartyKind, input: PartyInput): Op<String> = db.op {
        val name = input.name.trim()
        check(input, name)
        val key = NameMatcher.key(name)
        val now = System.currentTimeMillis()
        val id = newId()
        if (kind == PartyKind.CUSTOMER) {
            if (dao.activeCustomers().any { NameMatcher.key(it.name) == key }) fail(ErrCode.DUPLICATE)
            dao.insertCustomer(CustomerEntity(id, name, input.phone.trim(), input.email.trim(), input.address.trim(), input.openingBalance, input.notes.trim(), true, now, now))
        } else {
            if (dao.activeSuppliers().any { NameMatcher.key(it.name) == key }) fail(ErrCode.DUPLICATE)
            dao.insertSupplier(SupplierEntity(id, name, input.phone.trim(), input.email.trim(), input.address.trim(), input.openingBalance, input.notes.trim(), true, now, now))
        }
        id
    }

    suspend fun update(kind: PartyKind, id: String, input: PartyInput): Op<Unit> = db.op {
        val name = input.name.trim()
        check(input, name)
        val key = NameMatcher.key(name)
        val now = System.currentTimeMillis()
        if (kind == PartyKind.CUSTOMER) {
            val c = dao.customer(id) ?: fail(ErrCode.NOT_FOUND)
            if (dao.activeCustomers().any { it.id != id && NameMatcher.key(it.name) == key }) fail(ErrCode.DUPLICATE)
            dao.updateCustomer(c.copy(name = name, phone = input.phone.trim(), email = input.email.trim(), address = input.address.trim(), openingBalance = input.openingBalance, notes = input.notes.trim(), updatedAt = now))
        } else {
            val c = dao.supplier(id) ?: fail(ErrCode.NOT_FOUND)
            if (dao.activeSuppliers().any { it.id != id && NameMatcher.key(it.name) == key }) fail(ErrCode.DUPLICATE)
            dao.updateSupplier(c.copy(name = name, phone = input.phone.trim(), email = input.email.trim(), address = input.address.trim(), openingBalance = input.openingBalance, notes = input.notes.trim(), updatedAt = now))
        }
    }

    private fun check(input: PartyInput, name: String) {
        if (name.isEmpty() || name.length > 120) fail(ErrCode.INVALID)
    }

    /** @return true when physically deleted, false when hidden because it has transaction history. */
    suspend fun remove(kind: PartyKind, id: String): Op<Boolean> = db.op {
        val now = System.currentTimeMillis()
        if (kind == PartyKind.CUSTOMER) {
            dao.customer(id) ?: fail(ErrCode.NOT_FOUND)
            if (dao.customerHistoryCount(id) == 0) { dao.deleteCustomer(id); true } else { dao.setCustomerActive(id, false, now); false }
        } else {
            dao.supplier(id) ?: fail(ErrCode.NOT_FOUND)
            if (dao.supplierHistoryCount(id) == 0) { dao.deleteSupplier(id); true } else { dao.setSupplierActive(id, false, now); false }
        }
    }

    fun observe(kind: PartyKind, query: String): Flow<List<PartyRow>> =
        if (kind == PartyKind.CUSTOMER) dao.observeCustomers(query.trim()).map { l -> l.map { it.customer.row(it.balance) } }
        else dao.observeSuppliers(query.trim()).map { l -> l.map { it.supplier.row(it.balance) } }

    suspend fun list(kind: PartyKind): List<PartyRow> =
        if (kind == PartyKind.CUSTOMER) dao.customersWithBalance().map { it.customer.row(it.balance) }
        else dao.suppliersWithBalance().map { it.supplier.row(it.balance) }

    suspend fun get(kind: PartyKind, id: String): PartyRow? = list(kind).firstOrNull { it.id == id }

    fun observeCustomerOutstanding() = dao.observeCustomerOutstanding()
    fun observeSupplierOutstanding() = dao.observeSupplierOutstanding()

    private fun CustomerEntity.row(balance: Long) = PartyRow(id, PartyKind.CUSTOMER, name, phone, email, address, openingBalance, notes, balance)
    private fun SupplierEntity.row(balance: Long) = PartyRow(id, PartyKind.SUPPLIER, name, phone, email, address, openingBalance, notes, balance)
}
