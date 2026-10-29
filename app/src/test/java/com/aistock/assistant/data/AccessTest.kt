package com.aistock.assistant.data

import com.aistock.assistant.ai.PartyKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** After the free trial or plan ends, nothing can be changed, but everything can still be read. */
@RunWith(RobolectricTestRunner::class)
class AccessTest {
    private lateinit var env: TestEnv
    @Before fun setUp() { env = TestEnv(); Access.set(false, 0L, true) }
    @After fun tearDown() { Access.set(false, 0L, true); env.close() }

    @Test
    fun rules() {
        val now = 1_000_000_000_000L
        Access.set(signedIn = false, until = now - 1, active = false)
        assertFalse("not signed in: the sign-in screen decides, not this", Access.readOnly(now))
        Access.set(true, 0L, false)
        assertFalse("plan not known yet: never block", Access.readOnly(now))
        Access.set(true, now + 3 * 86_400_000L + 5, true)
        assertFalse(Access.readOnly(now))
        assertEquals(3, Access.daysLeft(now))
        Access.set(true, now - 1, true)
        assertTrue("period over even without asking the server (offline)", Access.readOnly(now))
        Access.set(true, now + 86_400_000L, false)
        assertTrue("server says the plan is not active", Access.readOnly(now))
        Access.set(false, now, true)
        assertNull(Access.daysLeft(now))
    }

    @Test
    fun readOnlyStopsEveryWriteButKeepsReading(): Unit = runBlocking {
        val shirt = env.product("Blue Shirt", stock = 10, sell = 450, buy = 300)
        val kumar = env.customer("Kumar Traders")
        Access.set(true, System.currentTimeMillis() - 1000, true)
        assertEquals(ErrCode.READ_ONLY, env.trade.createSale(kumar, listOf(LineInput(shirt.id, 2_000, 45_000))).errorOrNull())
        assertEquals(ErrCode.READ_ONLY, env.inventory.stockIn(shirt.id, 5_000).errorOrNull())
        assertEquals(ErrCode.READ_ONLY, env.parties.create(PartyKind.CUSTOMER, PartyInput("Raju")).errorOrNull())
        assertEquals("stock unchanged", 10L, env.stock(shirt.id))
        assertEquals("still readable", 1, env.parties.list(PartyKind.CUSTOMER).size)
        Access.set(true, System.currentTimeMillis() + 86_400_000L, true)
        assertTrue("paid again: saving works", env.trade.createSale(kumar, listOf(LineInput(shirt.id, 2_000, 45_000))).isOk)
        assertEquals(8L, env.stock(shirt.id))
    }
}
