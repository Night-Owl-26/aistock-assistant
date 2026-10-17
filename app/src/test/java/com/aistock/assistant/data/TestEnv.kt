package com.aistock.assistant.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistock.assistant.ai.PartyKind
import com.aistock.assistant.database.AppDatabase

/** A fresh in-memory database with the real repositories on top, for one test. */
class TestEnv(var allowNegative: Boolean = false) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    val inventory = InventoryRepository(db, object : StockRules { override fun allowNegativeStock() = allowNegative })
    val trade = TradeRepository(db, inventory)
    val parties = PartyRepository(db)
    val scheduled = ArrayList<Triple<String, String, Long>>()
    val business = com.aistock.assistant.business.RoomBusinessBackend(db, parties, object : com.aistock.assistant.business.ReminderSink {
        override fun schedule(id: String, text: String, at: Long) { scheduled.add(Triple(id, text, at)) }
        override fun cancel(id: String) { scheduled.removeAll { it.first == id } }
    })
    val backend = RoomAssistantBackend(db, inventory, trade, parties, business)

    suspend fun product(name: String, stock: Long = 0, min: Long = 0, sell: Long = 0, buy: Long = 0, unit: String = "PCS") =
        inventory.createProduct(ProductInput(name, unit = unit, openingStock = stock * 1000, minimumStock = min * 1000, sellingPrice = sell * 100, purchasePrice = buy * 100)).orThrow()

    suspend fun customer(name: String, opening: Long = 0): String = parties.create(PartyKind.CUSTOMER, PartyInput(name, openingBalance = opening * 100)).orThrow()
    suspend fun supplier(name: String, opening: Long = 0): String = parties.create(PartyKind.SUPPLIER, PartyInput(name, openingBalance = opening * 100)).orThrow()
    suspend fun stock(id: String): Long = db.productDao().byId(id)!!.currentStock / 1000
    suspend fun customerBalance(id: String): Long = parties.get(PartyKind.CUSTOMER, id)!!.balance
    suspend fun supplierBalance(id: String): Long = parties.get(PartyKind.SUPPLIER, id)!!.balance

    fun close() = db.close()
}
