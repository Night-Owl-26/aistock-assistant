package com.aistock.assistant.sync

import com.aistock.assistant.data.Access
import com.aistock.assistant.data.ErrCode
import com.aistock.assistant.data.LineInput
import com.aistock.assistant.data.Op
import com.aistock.assistant.data.ProductInput
import com.aistock.assistant.data.TestEnv
import com.aistock.assistant.data.orThrow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Two phones of one business (the owner's and a staff member's) syncing through a server that follows the same rules
 * as the real one (server/src/sync.js): both sell the same item at the same time and both see the right stock.
 */
@RunWith(RobolectricTestRunner::class)
class StaffSyncTest {
    private lateinit var owner: TestEnv
    private lateinit var staff: TestEnv
    private val server = FakeServer()

    @Before fun setUp() { owner = TestEnv(); staff = TestEnv() }
    @After fun tearDown() { owner.close(); staff.close(); Access.setRole("owner") }

    /** Mirrors server/src/sync.js: last change wins, staff may only add (and edit work items), refused rows come back. */
    class FakeServer {
        data class Row(val data: JSONObject?, val deleted: Boolean, val seq: Long, val by: String)
        val rows = LinkedHashMap<String, Row>()
        var seq = 0L
        var staffMoney = false
        private val add = setOf("sales", "sale_items", "inventory_transactions", "customers", "contact_log", "products", "categories", "business_aliases", "product_batches")
        private val money = setOf("purchases", "purchase_items", "payments", "expenses", "suppliers")

        fun transport(role: String, name: String) = object : SyncTransport {
            override fun business() = "b1"
            override fun role() = role
            override suspend fun push(changes: JSONArray): JSONObject {
                val rejected = JSONArray()
                for (i in 0 until changes.length()) {
                    val c = changes.getJSONObject(i)
                    val t = c.getString("t"); val id = c.getString("id"); val del = c.optBoolean("deleted")
                    val key = "$t/$id"
                    val cur = rows[key]
                    val exists = cur != null && !cur.deleted
                    val why = when {
                        role != "staff" -> null
                        del -> "staff_cannot_delete"
                        t in setOf("work_items", "work_events", "followups") -> null
                        t in add || (staffMoney && t in money) -> if (exists) "staff_cannot_change" else null
                        else -> "not_allowed"
                    }
                    if (why != null) { rejected.put(JSONObject().put("t", t).put("id", id).put("reason", why).put("data", cur?.data ?: JSONObject.NULL)); continue }
                    rows[key] = Row(if (del) null else c.getJSONObject("data"), del, ++seq, name)
                }
                return JSONObject().put("ok", true).put("rejected", rejected)
            }
            override suspend fun pull(since: Long, limit: Int): JSONObject {
                val list = rows.entries.filter { it.value.seq > since }.sortedBy { it.value.seq }
                val page = list.take(limit)
                val arr = JSONArray()
                for ((k, r) in page) arr.put(JSONObject().put("t", k.substringBefore('/')).put("id", k.substringAfter('/')).put("data", r.data ?: JSONObject.NULL).put("deleted", r.deleted).put("seq", r.seq).put("by", r.by))
                return JSONObject().put("rows", arr).put("next", page.lastOrNull()?.value?.seq ?: since).put("more", list.size > limit)
            }
        }
    }

    private fun ownerSync() = SyncEngine(owner.db, server.transport("owner", "Kumar"))
    private var replaced = 0
    private fun staffSync() = SyncEngine(staff.db, server.transport("staff", "Ravi"), beforeReplace = { replaced++ })

    private suspend fun asOwner(block: suspend () -> Unit) { Access.setRole("owner", "Kumar"); block() }
    private suspend fun asStaff(block: suspend () -> Unit) { Access.setRole("staff", "Ravi", "S1"); block() }

    @Test
    fun ownerAndStaffShareStockAndBills(): Unit = runBlocking {
        val o = ownerSync(); val s = staffSync()
        lateinit var shirt: String
        lateinit var old: String
        asOwner {
            shirt = owner.product("Shirt XL", stock = 10, sell = 450, buy = 300).id
            // A product whose stock is older than the stock movements (kept from an early version of the app).
            old = owner.product("Old Saree").id
            owner.db.productDao().setStock(old, 5_000, System.currentTimeMillis())
            owner.customer("Raju")
            assertTrue(o.syncNow())
            assertEquals(0, o.waiting())
        }
        asStaff {
            // The staff phone had something of its own: it is backed up and replaced by the business's data.
            staff.customer("Someone else")
            assertTrue(s.syncNow())
            assertEquals(1, replaced)
            assertEquals(listOf("Raju"), staff.db.partyDao().activeCustomers().map { it.name })
            assertEquals(10L, staff.stock(shirt))
            assertEquals(5L, staff.stock(old))
            assertEquals(45_000L, staff.db.productDao().byId(shirt)!!.sellingPrice)
        }

        // Both sell the Shirt XL before either has synced: 2 by Ravi, 3 by the owner.
        asStaff {
            val sale = staff.trade.createSale(null, listOf(LineInput(shirt, 2_000, 45_000))).orThrow()
            assertEquals("INV-S1-0001", sale.invoiceNumber)
            assertEquals("Ravi", sale.createdBy)
        }
        asOwner { assertEquals("INV-0001", owner.trade.createSale(null, listOf(LineInput(shirt, 3_000, 45_000))).orThrow().invoiceNumber) }
        asStaff { assertTrue(s.syncNow()) }
        asOwner { assertTrue(o.syncNow()) }
        asStaff { assertTrue(s.syncNow()) }
        assertEquals(5L, owner.stock(shirt))
        assertEquals(5L, staff.stock(shirt))
        assertEquals(setOf("INV-0001", "INV-S1-0001"), owner.db.tradeDao().salesBetween(0, Long.MAX_VALUE).map { it.sale.invoiceNumber }.toSet())
        assertEquals("Ravi", owner.db.tradeDao().salesBetween(0, Long.MAX_VALUE).single { it.sale.invoiceNumber == "INV-S1-0001" }.sale.createdBy)
        assertEquals(2, staff.db.tradeDao().salesBetween(0, Long.MAX_VALUE).size)

        // Staff limits: no price changes, no purchases while the owner has not allowed them, no cancelling.
        asStaff {
            assertEquals(ErrCode.NOT_ALLOWED, (staff.inventory.updateProduct(shirt, ProductInput("Shirt XL", sellingPrice = 100)) as Op.Err).code)
            assertEquals(ErrCode.NOT_ALLOWED, (staff.trade.createPurchase(null, listOf(LineInput(shirt, 1_000, 30_000))) as Op.Err).code)
            val sid = staff.db.tradeDao().salesBetween(0, Long.MAX_VALUE).first().sale.id
            assertEquals(ErrCode.NOT_ALLOWED, (staff.trade.cancelSale(sid) as Op.Err).code)
        }

        // The owner changes the price: the staff phone gets it, and its stock is not disturbed.
        asOwner {
            owner.inventory.updateProduct(shirt, ProductInput("Shirt XL", sellingPrice = 50_000, purchasePrice = 30_000)).orThrow()
            assertTrue(o.syncNow())
        }
        asStaff {
            assertTrue(s.syncNow())
            assertEquals(50_000L, staff.db.productDao().byId(shirt)!!.sellingPrice)
            assertEquals(5L, staff.stock(shirt))
        }

        // A change the server refuses (a staff phone editing a product directly) is put back from the server's copy.
        asStaff {
            val p = staff.db.productDao().byId(shirt)!!
            staff.db.productDao().update(p.copy(sellingPrice = 1))
            assertEquals(1, s.waiting())
            assertTrue(s.syncNow())
            assertEquals(50_000L, staff.db.productDao().byId(shirt)!!.sellingPrice)
            assertEquals(0, s.waiting())
        }

        // With money entries allowed, a staff purchase reaches the owner and raises the stock on both phones.
        server.staffMoney = true
        Access.setRole("staff", "Ravi", "S1", staffMoney = true)
        val sup = staff.supplier("Kumar Traders")
        staff.trade.createPurchase(sup, listOf(LineInput(shirt, 4_000, 30_000))).orThrow().also { assertEquals("PUR-S1-0001", it.purchaseNumber) }
        assertTrue(s.syncNow())
        asOwner { assertTrue(o.syncNow()) }
        assertEquals(9L, owner.stock(shirt))
        assertEquals(9L, staff.stock(shirt))
        assertEquals(listOf("Kumar Traders"), owner.db.partyDao().activeSuppliers().map { it.name })
    }

    @Test
    fun aPhoneOfflineForAWhileCatchesUpAndNothingIsSentTwice(): Unit = runBlocking {
        val o = ownerSync(); val s = staffSync()
        lateinit var id: String
        asOwner { id = owner.product("Rice 1kg", stock = 100, sell = 60).id; assertTrue(o.syncNow()) }
        asStaff { assertTrue(s.syncNow()) }
        // The staff phone sells 30 times without internet; the owner meanwhile adds stock.
        asStaff { repeat(30) { staff.trade.createSale(null, listOf(LineInput(id, 1_000, 6_000))).orThrow() } }
        asOwner { owner.inventory.stockIn(id, 50_000, "PURCHASE", null, "").orThrow(); assertTrue(o.syncNow()) }
        asStaff { assertTrue(s.syncNow()); assertTrue(s.syncNow()) }
        asOwner { assertTrue(o.syncNow()); assertTrue(o.syncNow()) }
        assertEquals(120L, owner.stock(id))
        assertEquals(120L, staff.stock(id))
        assertEquals(30, owner.db.tradeDao().salesBetween(0, Long.MAX_VALUE).size)
        assertEquals(0, o.waiting()); assertEquals(0, s.waiting())
    }
}
