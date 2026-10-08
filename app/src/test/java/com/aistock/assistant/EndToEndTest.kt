package com.aistock.assistant

import com.aistock.assistant.ai.AssistantEngine
import com.aistock.assistant.ai.LocalFallbackAIProvider
import com.aistock.assistant.ai.ReplyKind
import com.aistock.assistant.data.Status
import com.aistock.assistant.data.TestEnv
import com.aistock.assistant.data.TxnType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The mandatory end-to-end sequence, run through the real assistant pipeline against a real Room database. */
@RunWith(RobolectricTestRunner::class)
class EndToEndTest {
    private lateinit var env: TestEnv
    private lateinit var engine: AssistantEngine

    @Before
    fun setUp() {
        env = TestEnv()
        engine = AssistantEngine(env.backend, LocalFallbackAIProvider())
    }

    @After fun tearDown() { env.close() }

    @Test
    fun mandatorySequence(): Unit = runBlocking {
        // 1-2. Create product Blue Shirt with opening stock 100 PCS
        val shirt = env.product("Blue Shirt", stock = 100, min = 10, sell = 450, buy = 300)
        // 3. Create customer Kumar Traders
        val kumar = env.customer("Kumar Traders")

        // 4-5. "Sold 20 Blue Shirts to Kumar Traders." -> asks for confirmation, nothing saved yet
        var r = engine.handle("Sold 20 Blue Shirts to Kumar Traders.")
        assertEquals(ReplyKind.CONFIRM, r.kind)
        assertEquals(100L, env.stock(shirt.id))
        // 6-7. Confirm -> database shows 80
        r = engine.handle("Yes")
        assertEquals(r.text, ReplyKind.DONE, r.kind)
        assertEquals(80L, env.stock(shirt.id))
        val sale = env.db.tradeDao().salesBetween(0, Long.MAX_VALUE).single()
        assertEquals("Kumar Traders", sale.partyName)
        assertEquals(900_000L, sale.sale.total)
        assertEquals(Status.UNPAID, sale.sale.status)
        assertEquals(900_000L, env.customerBalance(kumar))

        // 8-9. "How many Blue Shirts do I have?" -> 80 pieces
        r = engine.handle("How many Blue Shirts do I have?")
        assertEquals(ReplyKind.ANSWER, r.kind)
        assertTrue(r.text, r.text.contains("80 pieces"))

        // 10-12. "Remove 10 because they were damaged." -> confirm -> 70
        r = engine.handle("Remove 10 because they were damaged.")
        assertEquals(r.text, ReplyKind.CONFIRM, r.kind)
        assertEquals(80L, env.stock(shirt.id))
        r = engine.confirm()
        assertEquals(r.text, ReplyKind.DONE, r.kind)
        assertEquals(70L, env.stock(shirt.id))
        assertEquals(TxnType.DAMAGE, env.inventory.movementsFor(shirt.id).last().type)

        // 13-15. "Received 50 Blue Shirts from ABC Traders at 500 each." -> confirm -> 120
        r = engine.handle("Received 50 Blue Shirts from ABC Traders at 500 each.")
        assertEquals(r.text, ReplyKind.CONFIRM, r.kind)
        r = engine.confirm()
        assertEquals(r.text, ReplyKind.DONE, r.kind)
        assertEquals(120L, env.stock(shirt.id))
        val purchase = env.db.tradeDao().purchasesBetween(0, Long.MAX_VALUE).single()
        assertEquals("ABC Traders", purchase.partyName)
        assertEquals(2_500_000L, purchase.purchase.total)

        // 16-17. "Show low stock products." -> correct result
        r = engine.handle("Show low stock products.")
        assertEquals(ReplyKind.ANSWER, r.kind)
        assertTrue(r.text, r.text.contains("No products are low"))
        val black = env.product("Black Shirt", stock = 3, min = 5)
        r = engine.handle("Show low stock products.")
        assertTrue(r.text, r.text.contains("Black Shirt"))
        assertFalse(r.text, r.text.contains("Blue Shirt"))
        assertNotNull(black)

        // Every movement was recorded: opening, sale, damage, purchase.
        assertEquals(listOf(TxnType.OPENING, TxnType.SALE, TxnType.DAMAGE, TxnType.PURCHASE), env.inventory.movementsFor(shirt.id).map { it.type })
        // Every confirmed write is in the audit log.
        val audits = env.db.metaDao().audits(50)
        assertEquals(3, audits.count { it.status == "SUCCESS" })
        assertTrue(audits.any { it.intent == "CREATE_SALE" })

        // Follow-up questions use real data.
        r = engine.handle("How much does Kumar Traders owe me?")
        assertTrue(r.text, r.text.contains("₹9,000"))
        r = engine.handle("How much do I owe ABC Traders?")
        assertTrue(r.text, r.text.contains("₹25,000"))
        r = engine.handle("What were today's sales?")
        assertTrue(r.text, r.text.contains("₹9,000"))
    }

    @Test
    fun assistantWritesCanBeUndone(): Unit = runBlocking {
        val shirt = env.product("Blue Shirt", stock = 100, sell = 450)
        val kumar = env.customer("Kumar Traders")
        engine.handle("Sold 20 Blue Shirts to Kumar Traders")
        val done = engine.confirm()
        assertEquals(80L, env.stock(shirt.id))
        assertEquals(ReplyKind.DONE, engine.undo(done.undoToken!!).kind)
        assertEquals(100L, env.stock(shirt.id))
        assertEquals(0L, env.customerBalance(kumar))
        assertEquals("the sale is kept, marked cancelled", Status.CANCELLED, env.db.tradeDao().salesBetween(0, Long.MAX_VALUE).single().sale.status)

        engine.handle("Add 5 Blue Shirt")
        val added = engine.confirm()
        assertEquals(105L, env.stock(shirt.id))
        engine.undo(added.undoToken!!)
        assertEquals(100L, env.stock(shirt.id))
    }

    @Test
    fun spokenExamplesFromTheSpecificationChangeTheDatabase(): Unit = runBlocking {
        suspend fun say(text: String): String {
            val r = engine.handle(text)
            return if (r.kind == ReplyKind.CONFIRM) engine.confirm().text else r.text
        }
        say("Add 100 pieces of Blue Shirt.")
        val shirt = env.inventory.activeProducts().single { it.name == "Blue Shirt" }
        assertEquals(100L, env.stock(shirt.id))
        say("Add Kumar Traders as a customer.")
        say("Add ABC Traders as a supplier.")
        assertEquals(1, env.backend.parties(com.aistock.assistant.ai.PartyKind.CUSTOMER).size)
        assertEquals(1, env.backend.parties(com.aistock.assistant.ai.PartyKind.SUPPLIER).size)
        say("Received 50 boxes of A4 paper from Kumar Traders at 250 rupees each.")
        val paper = env.inventory.activeProducts().single { it.name == "A4 Paper" }
        assertEquals("BOX", paper.unit)
        assertEquals(50L, env.stock(paper.id))
        say("Paid 2500 rupees for transport.")
        assertTrue(say("Show today's expenses.").contains("₹2,500"))
        assertTrue(say("What did we purchase yesterday?").contains("No purchases"))
        say("We counted Blue Shirt and there are actually 72 pieces.")
        assertEquals(72L, env.stock(shirt.id))
        assertEquals(TxnType.ADJUSTMENT_OUT, env.inventory.movementsFor(shirt.id).last().type)
        say("Blue shirt 20 pieces stock-la add pannunga.")
        assertEquals(92L, env.stock(shirt.id))
    }
}
