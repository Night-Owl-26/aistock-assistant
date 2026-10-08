package com.aistock.assistant.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TradeRepositoryTest {
    private lateinit var env: TestEnv

    @Before fun setUp() { env = TestEnv() }
    @After fun tearDown() { env.close() }

    @Test
    fun saleReducesStockAndCalculatesBalance(): Unit = runBlocking {
        val shirt = env.product("Blue Shirt", stock = 100, sell = 450, buy = 300)
        val kumar = env.customer("Kumar Traders")
        val sale = env.trade.createSale(kumar, listOf(LineInput(shirt.id, 20_000, 45_000)), discount = 50_000, tax = 0, paid = 400_000).orThrow()
        assertEquals("INV-0001", sale.invoiceNumber)
        assertEquals(900_000L, sale.subtotal)
        assertEquals(850_000L, sale.total)
        assertEquals(400_000L, sale.paidAmount)
        assertEquals(450_000L, sale.balanceAmount)
        assertEquals(Status.PARTIAL, sale.status)
        assertEquals(80L, env.stock(shirt.id))
        val move = env.inventory.movementsFor(shirt.id).last()
        assertEquals(TxnType.SALE, move.type)
        assertEquals("SALE", move.referenceType)
        assertEquals(sale.id, move.referenceId)
        assertEquals(450_000L, env.customerBalance(kumar))
        assertEquals("INV-0002", env.trade.createSale(null, listOf(LineInput(shirt.id, 1_000, 45_000))).orThrow().invoiceNumber)
    }

    @Test
    fun saleIsAllOrNothing(): Unit = runBlocking {
        val shirt = env.product("Blue Shirt", stock = 100, sell = 450)
        val paper = env.product("A4 Paper", stock = 2, sell = 300)
        val r = env.trade.createSale(null, listOf(LineInput(shirt.id, 20_000, 45_000), LineInput(paper.id, 5_000, 30_000)))
        assertEquals(ErrCode.INSUFFICIENT_STOCK, r.errorOrNull())
        assertEquals("first line must be rolled back", 100L, env.stock(shirt.id))
        assertEquals(2L, env.stock(paper.id))
        assertEquals(0, env.db.tradeDao().saleTotals(0, Long.MAX_VALUE).count)
        assertEquals(1, env.inventory.movementsFor(shirt.id).size)
        // The invoice counter must not have advanced either.
        assertEquals("INV-0001", env.trade.createSale(null, listOf(LineInput(shirt.id, 1_000, 45_000))).orThrow().invoiceNumber)
    }

    @Test
    fun creditSaleNeedsACustomer(): Unit = runBlocking {
        val shirt = env.product("Blue Shirt", stock = 10, sell = 450)
        assertEquals(ErrCode.NEEDS_PARTY, env.trade.createSale(null, listOf(LineInput(shirt.id, 1_000, 45_000)), paid = 0).errorOrNull())
        assertEquals(ErrCode.INVALID, env.trade.createSale(null, listOf(LineInput(shirt.id, 1_000, 45_000)), paid = 99_999_999).errorOrNull())
        assertEquals(ErrCode.INVALID, env.trade.createSale(null, emptyList()).errorOrNull())
        assertEquals(10L, env.stock(shirt.id))
    }

    @Test
    fun cancellingASaleReturnsStockAndKeepsTheRecord(): Unit = runBlocking {
        val shirt = env.product("Blue Shirt", stock = 100, sell = 450)
        val kumar = env.customer("Kumar Traders")
        val sale = env.trade.createSale(kumar, listOf(LineInput(shirt.id, 20_000, 45_000)), paid = 0).orThrow()
        assertEquals(900_000L, env.customerBalance(kumar))
        env.trade.cancelSale(sale.id).orThrow()
        assertEquals(100L, env.stock(shirt.id))
        assertEquals(0L, env.customerBalance(kumar))
        assertEquals(Status.CANCELLED, env.db.tradeDao().sale(sale.id)!!.status)
        assertEquals(TxnType.RETURN_IN, env.inventory.movementsFor(shirt.id).last().type)
        assertEquals(3, env.inventory.movementsFor(shirt.id).size)
        assertEquals(ErrCode.ALREADY_DONE, env.trade.cancelSale(sale.id).errorOrNull())
    }

    @Test
    fun purchaseIncreasesStockAndSupplierBalance(): Unit = runBlocking {
        val shirt = env.product("Blue Shirt", stock = 70, buy = 300)
        val abc = env.supplier("ABC Traders")
        val p = env.trade.createPurchase(abc, listOf(LineInput(shirt.id, 50_000, 50_000)), paid = 0).orThrow()
        assertEquals("PUR-0001", p.purchaseNumber)
        assertEquals(2_500_000L, p.total)
        assertEquals(Status.UNPAID, p.status)
        assertEquals(120L, env.stock(shirt.id))
        assertEquals(2_500_000L, env.supplierBalance(abc))
        assertEquals(TxnType.PURCHASE, env.inventory.movementsFor(shirt.id).last().type)
        assertEquals("latest purchase price is remembered", 50_000L, env.db.productDao().byId(shirt.id)!!.purchasePrice)
        env.trade.cancelPurchase(p.id).orThrow()
        assertEquals(70L, env.stock(shirt.id))
        assertEquals(0L, env.supplierBalance(abc))
    }

    @Test
    fun expenses(): Unit = runBlocking {
        val e = env.trade.addExpense("Transport", "lorry", 250_000).orThrow()
        env.trade.addExpense("Transport", "", 50_000).orThrow()
        env.trade.addExpense("Rent", "", 1_000_000).orThrow()
        assertEquals(ErrCode.INVALID, env.trade.addExpense("", "", 100).errorOrNull())
        assertEquals(ErrCode.INVALID, env.trade.addExpense("Tea", "", 0).errorOrNull())
        val s = env.backend.expenses(0, Long.MAX_VALUE)
        assertEquals(3, s.count)
        assertEquals(1_300_000L, s.totalPaise)
        assertEquals("Rent", s.lines[0].label)
        env.trade.cancelExpense(e.id).orThrow()
        assertEquals(1_050_000L, env.backend.expenses(0, Long.MAX_VALUE).totalPaise)
        assertEquals("cancelled expenses stay on record", 3, env.db.tradeDao().expensesBetween(0, Long.MAX_VALUE).size)
    }

    @Test
    fun paymentsAndOutstanding(): Unit = runBlocking {
        val shirt = env.product("Blue Shirt", stock = 100, sell = 500)
        val kumar = env.customer("Kumar Traders", opening = 1_000)
        val abc = env.supplier("ABC Traders")
        env.trade.createSale(kumar, listOf(LineInput(shirt.id, 20_000, 50_000)), paid = 0).orThrow()          // +10,000
        env.trade.createSale(kumar, listOf(LineInput(shirt.id, 10_000, 50_000)), paid = 200_000).orThrow()    // +3,000
        assertEquals(1_400_000L, env.customerBalance(kumar))                                                  // 1,000 + 10,000 + 3,000
        val pay = env.trade.addPayment("CUSTOMER", kumar, 600_000, "UPI", "ref-1").orThrow()
        assertEquals(800_000L, env.customerBalance(kumar))
        env.trade.cancelPayment(pay.id).orThrow()
        assertEquals(1_400_000L, env.customerBalance(kumar))

        env.trade.createPurchase(abc, listOf(LineInput(shirt.id, 10_000, 30_000)), paid = 0).orThrow()
        env.trade.addPayment("SUPPLIER", abc, 100_000).orThrow()
        assertEquals(200_000L, env.supplierBalance(abc))

        assertEquals(ErrCode.INVALID, env.trade.addPayment("CUSTOMER", kumar, 0).errorOrNull())
        assertEquals(ErrCode.NOT_FOUND, env.trade.addPayment("CUSTOMER", "nobody", 100).errorOrNull())
        assertEquals(ErrCode.INVALID, env.trade.addPayment("ALIEN", kumar, 100).errorOrNull())
    }

    @Test
    fun totalsAreForTheRequestedPeriodOnly(): Unit = runBlocking {
        val shirt = env.product("Blue Shirt", stock = 100, sell = 500)
        val day = 24 * 3600_000L
        val now = System.currentTimeMillis()
        env.trade.createSale(null, listOf(LineInput(shirt.id, 1_000, 50_000)), date = now).orThrow()
        env.trade.createSale(null, listOf(LineInput(shirt.id, 2_000, 50_000)), date = now - 3 * day).orThrow()
        assertEquals(50_000L, env.backend.sales(now - day, now + day).totalPaise)
        assertEquals(150_000L, env.backend.sales(0, Long.MAX_VALUE).totalPaise)
        assertTrue(env.backend.sales(0, Long.MAX_VALUE).lines.single().label == "Blue Shirt")
    }

    @Test
    fun partiesCannotBeDuplicatedAndAreHiddenWhenTheyHaveHistory(): Unit = runBlocking {
        val shirt = env.product("Blue Shirt", stock = 10, sell = 500)
        val kumar = env.customer("Kumar Traders")
        val ravi = env.customer("Ravi")
        assertEquals(ErrCode.DUPLICATE, env.parties.create(com.aistock.assistant.ai.PartyKind.CUSTOMER, PartyInput("kumar traders")).errorOrNull())
        env.trade.createSale(kumar, listOf(LineInput(shirt.id, 1_000, 50_000)), paid = 0).orThrow()
        assertEquals(false, env.parties.remove(com.aistock.assistant.ai.PartyKind.CUSTOMER, kumar).orThrow())
        assertEquals(true, env.parties.remove(com.aistock.assistant.ai.PartyKind.CUSTOMER, ravi).orThrow())
        assertTrue(env.parties.list(com.aistock.assistant.ai.PartyKind.CUSTOMER).isEmpty())
        assertEquals("the sale is still there", 1, env.db.tradeDao().saleTotals(0, Long.MAX_VALUE).count)
    }
}
