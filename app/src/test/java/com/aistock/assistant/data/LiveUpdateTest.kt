package com.aistock.assistant.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/** Screens watch the database through flows. They must update by themselves after a sale or stock change. */
@RunWith(RobolectricTestRunner::class)
class LiveUpdateTest {
    private lateinit var env: TestEnv

    @Before fun setUp() { env = TestEnv() }
    @After fun tearDown() { env.close() }

    private suspend fun <T> eventually(expected: T, read: () -> T?) {
        withTimeoutOrNull(5_000) { while (read() != expected) delay(50) }
        assertEquals(expected, read())
    }

    @Test
    fun totalsAndStockValueFollowNewSales(): Unit = runBlocking {
        val shirt = env.product("Blue Shirt", stock = 50, sell = 450, buy = 300)
        val kumar = env.customer("Kumar Traders")
        val scope = CoroutineScope(Dispatchers.Default)
        val sales = CopyOnWriteArrayList<Long>()
        val value = CopyOnWriteArrayList<Long>()
        val outstanding = CopyOnWriteArrayList<Long>()
        val low = CopyOnWriteArrayList<Int>()
        scope.launch { env.db.tradeDao().observeSaleTotal(0, Long.MAX_VALUE).collect { sales.add(it) } }
        scope.launch { env.db.productDao().observeStockValue().collect { value.add(it) } }
        scope.launch { env.parties.observeCustomerOutstanding().collect { outstanding.add(it) } }
        scope.launch { env.db.productDao().observeLowCount().collect { low.add(it) } }
        eventually(0L) { sales.lastOrNull() }
        eventually(1_500_000L) { value.lastOrNull() }

        env.trade.createSale(kumar, listOf(LineInput(shirt.id, 20_000, 45_000)), paid = 0).orThrow()
        eventually(900_000L) { sales.lastOrNull() }
        eventually(900_000L) { value.lastOrNull() }
        eventually(900_000L) { outstanding.lastOrNull() }

        env.inventory.stockOut(shirt.id, 30_000, TxnType.DAMAGE).orThrow()
        eventually(0L) { value.lastOrNull() }
        eventually(1) { low.lastOrNull() }
        scope.cancel()
    }
}
