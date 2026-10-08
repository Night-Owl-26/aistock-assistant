package com.aistock.assistant.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Conversation logic: clarification, confirmation, execution, context and safety. Runs without a database. */
class AssistantEngineTest {
    private fun engine(b: FakeBackend, provider: AIProvider = LocalFallbackAIProvider()) = AssistantEngine(b, provider)

    @Test
    fun mandatoryEndToEndSequence(): Unit = runBlocking {
        val b = FakeBackend()
        b.addProduct("Blue Shirt", stock = 100, min = 10, sell = 450)
        b.addCustomer("Kumar Traders")
        val e = engine(b)

        var r = e.handle("Sold 20 Blue Shirts to Kumar Traders.")
        assertEquals(ReplyKind.CONFIRM, r.kind)
        assertEquals("nothing may change before confirmation", 100L, b.stock("Blue Shirt"))
        r = e.confirm()
        assertEquals(ReplyKind.DONE, r.kind)
        assertEquals(80L, b.stock("Blue Shirt"))

        r = e.handle("How many Blue Shirts do I have?")
        assertEquals(ReplyKind.ANSWER, r.kind)
        assertTrue(r.text, r.text.contains("80 pieces"))

        r = e.handle("Remove 10 because they were damaged.")
        assertEquals(ReplyKind.CONFIRM, r.kind)
        assertTrue(r.text, r.text.contains("Blue Shirt"))
        r = e.handle("yes")
        assertEquals(ReplyKind.DONE, r.kind)
        assertEquals(70L, b.stock("Blue Shirt"))

        r = e.handle("Received 50 Blue Shirts from ABC Traders at 500 each.")
        assertEquals(ReplyKind.CONFIRM, r.kind)
        r = e.confirm()
        assertEquals(ReplyKind.DONE, r.kind)
        assertEquals(120L, b.stock("Blue Shirt"))
        assertEquals(2_500_000L, b.suppliers.first { it.name == "ABC Traders" }.balancePaise)

        r = e.handle("Show low stock products.")
        assertEquals(ReplyKind.ANSWER, r.kind)
        assertTrue(r.text, r.text.contains("No products are low"))

        b.addProduct("Black Shirt", stock = 2, min = 5)
        r = e.handle("Show low stock products.")
        assertTrue(r.text, r.text.contains("Black Shirt"))
        assertFalse(r.text, r.text.contains("Blue Shirt"))
    }

    @Test
    fun clarificationConversation(): Unit = runBlocking {
        val b = FakeBackend()
        b.addProduct("Blue Shirt Large")
        b.addProduct("Blue Shirt Medium")
        val e = engine(b)
        var r = e.handle("Add 50.")
        assertEquals(ReplyKind.CLARIFY, r.kind)
        assertTrue(r.text, r.text.contains("Which product"))
        r = e.handle("Blue Shirt")
        assertEquals(ReplyKind.CLARIFY, r.kind)
        assertEquals(2, r.choices.size)
        r = e.handle("Large")
        assertEquals(ReplyKind.CONFIRM, r.kind)
        assertTrue(r.text, r.text.contains("Blue Shirt Large"))
        r = e.handle("Yes.")
        assertEquals(ReplyKind.DONE, r.kind)
        assertEquals(50L, b.stock("Blue Shirt Large"))
        assertEquals(0L, b.stock("Blue Shirt Medium"))
    }

    @Test
    fun addCreatesNewProductOnlyAfterConfirmation(): Unit = runBlocking {
        val b = FakeBackend()
        val e = engine(b)
        val r = e.handle("Add 100 pieces of Blue Shirt.")
        assertEquals(ReplyKind.CONFIRM, r.kind)
        assertTrue(b.productList.isEmpty())
        e.confirm()
        assertEquals(100L, b.stock("Blue Shirt"))
    }

    @Test
    fun receivedCreatesPurchaseWithSupplier(): Unit = runBlocking {
        val b = FakeBackend()
        val e = engine(b)
        val r = e.handle("Received 50 boxes of A4 paper from Kumar Traders at 250 rupees each.")
        assertEquals(ReplyKind.CONFIRM, r.kind)
        e.confirm()
        val plan = b.executed.single() as Plan.StockIn
        assertEquals("A4 Paper", plan.newProductName)
        assertEquals("BOX", plan.unit)
        assertEquals(50_000L, plan.qtyMilli)
        assertEquals(25_000L, plan.unitCostPaise)
        assertEquals(1_250_000L, plan.totalPaise)
        assertEquals("Kumar Traders", plan.newSupplierName)
        assertTrue(plan.asPurchase)
    }

    @Test
    fun cancelChangesNothing(): Unit = runBlocking {
        val b = FakeBackend()
        b.addProduct("Blue Shirt", stock = 100, sell = 450)
        val e = engine(b)
        e.handle("Sold 20 Blue Shirts")
        val r = e.handle("no")
        assertEquals(ReplyKind.CANCELLED, r.kind)
        assertEquals(100L, b.stock("Blue Shirt"))
        assertTrue(b.executed.isEmpty())
        assertEquals("CANCELLED", b.audits.last()[3])
    }

    @Test
    fun negativeStockIsRefused(): Unit = runBlocking {
        val b = FakeBackend()
        b.addProduct("Blue Shirt", stock = 5, sell = 450)
        val e = engine(b)
        val r = e.handle("Sold 20 Blue Shirts")
        assertEquals(ReplyKind.ERROR, r.kind)
        assertTrue(r.text, r.text.contains("Not enough stock"))
        assertEquals(5L, b.stock("Blue Shirt"))
        assertFalse(e.awaitingConfirmation)
    }

    @Test
    fun unknownProductForSaleIsNotInvented(): Unit = runBlocking {
        val b = FakeBackend()
        val e = engine(b)
        val r = e.handle("Sold 20 Blue Shirts to Kumar Traders")
        assertEquals(ReplyKind.ERROR, r.kind)
        assertTrue(r.text, r.text.contains("couldn't find"))
        assertTrue(b.productList.isEmpty())
    }

    @Test
    fun fuzzyMatchAsksDidYouMean(): Unit = runBlocking {
        val b = FakeBackend()
        b.addProduct("Blue Cotton Shirt", stock = 30)
        val e = engine(b)
        var r = e.handle("Remove 5 blue shirt because damaged")
        assertEquals(ReplyKind.CLARIFY, r.kind)
        assertTrue(r.text, r.text.contains("Did you mean Blue Cotton Shirt"))
        r = e.handle("yes")
        assertEquals(ReplyKind.CONFIRM, r.kind)
        e.confirm()
        assertEquals(25L, b.stock("Blue Cotton Shirt"))
    }

    @Test
    fun stockAdjustmentShowsDifferenceAndIsDangerous(): Unit = runBlocking {
        val b = FakeBackend()
        b.addProduct("Blue Shirt", stock = 80)
        val e = engine(b)
        val r = e.handle("We counted Blue Shirt and there are actually 72 pieces.")
        assertEquals(ReplyKind.CONFIRM, r.kind)
        assertTrue(r.dangerous)
        assertTrue(r.lines.toString(), r.lines.any { it.value.contains("-8") })
        e.confirm()
        assertEquals(72L, b.stock("Blue Shirt"))
    }

    @Test
    fun expenseAndQueries(): Unit = runBlocking {
        val b = FakeBackend()
        val e = engine(b)
        var r = e.handle("Paid 2500 rupees for transport.")
        assertEquals(ReplyKind.CONFIRM, r.kind)
        assertTrue(r.text, r.text.contains("₹2,500"))
        e.confirm()
        assertEquals(250_000L, b.expenseTotals.single())
        r = e.handle("Show today's expenses.")
        assertTrue(r.text, r.text.contains("₹2,500"))
        r = e.handle("What were today's sales?")
        assertTrue(r.text, r.text.contains("No sales today"))
    }

    @Test
    fun partiesAndBalances(): Unit = runBlocking {
        val b = FakeBackend()
        b.addProduct("Blue Shirt", stock = 100, sell = 500)
        val e = engine(b)
        e.handle("Add Kumar Traders as a customer."); e.confirm()
        e.handle("Add ABC Traders as a supplier."); e.confirm()
        assertEquals(1, b.customers.size)
        assertEquals(1, b.suppliers.size)
        e.handle("Sold 20 Blue Shirts to Kumar Traders."); e.confirm()
        var r = e.handle("How much does Kumar Traders owe me?")
        assertTrue(r.text, r.text.contains("₹10,000"))
        r = e.handle("How much do I owe ABC Traders?")
        assertTrue(r.text, r.text.contains("do not owe"))
        r = e.handle("Add Kumar Traders as a customer.")
        assertEquals(ReplyKind.ERROR, r.kind)
    }

    @Test
    fun bareAddAsksWhatKindOfRecord(): Unit = runBlocking {
        val b = FakeBackend()
        val e = engine(b)
        var r = e.handle("Add Kumar Traders.")
        assertEquals(ReplyKind.CLARIFY, r.kind)
        r = e.handle("supplier")
        assertEquals(ReplyKind.CONFIRM, r.kind)
        e.confirm()
        assertEquals("Kumar Traders", b.suppliers.single().name)
    }

    @Test
    fun undoReversesStock(): Unit = runBlocking {
        val b = FakeBackend()
        b.addProduct("A4 Paper", unit = "BOX", stock = 10)
        val e = engine(b)
        e.handle("Add 50 boxes of A4 Paper")
        val r = e.confirm()
        assertEquals(60L, b.stock("A4 Paper"))
        assertNotNull(r.undoToken)
        val u = e.undo(r.undoToken!!)
        assertEquals(ReplyKind.DONE, u.kind)
        assertEquals(10L, b.stock("A4 Paper"))
    }

    @Test
    fun tanglishCommandWorks(): Unit = runBlocking {
        val b = FakeBackend()
        b.addProduct("Blue Shirt", stock = 10)
        val e = engine(b)
        val r = e.handle("Blue shirt 20 pieces stock-la add pannunga.")
        assertEquals(ReplyKind.CONFIRM, r.kind)
        e.handle("sari")
        assertEquals(30L, b.stock("Blue Shirt"))
    }

    @Test
    fun tamilRepliesWhenLanguageIsTamil(): Unit = runBlocking {
        val b = FakeBackend()
        b.addProduct("Blue Shirt", stock = 80)
        val e = AssistantEngine(b, LocalFallbackAIProvider(), tamil = { true })
        val r = e.handle("Blue shirt evlo irukku")
        assertTrue(r.text, r.text.contains("80 பீஸ்"))
    }

    @Test
    fun unitMismatchIsRejectedAndKgGramConverted(): Unit = runBlocking {
        val b = FakeBackend()
        b.addProduct("Cotton Yarn", unit = "KG", stock = 10)
        val e = engine(b)
        var r = e.handle("Remove 5 boxes of Cotton Yarn")
        assertEquals(ReplyKind.ERROR, r.kind)
        r = e.handle("Remove 2000 grams of Cotton Yarn")
        assertEquals(ReplyKind.CONFIRM, r.kind)
        e.confirm()
        assertEquals(8L, b.stock("Cotton Yarn"))
    }

    @Test
    fun databaseFailureReportsNothingChanged(): Unit = runBlocking {
        val b = FakeBackend()
        b.addProduct("Blue Shirt", stock = 10)
        val e = engine(b)
        e.handle("Add 5 Blue Shirt")
        b.failNext = true
        val r = e.confirm()
        assertEquals(ReplyKind.ERROR, r.kind)
        assertTrue(r.text, r.text.contains("Your data was not changed"))
        assertEquals(10L, b.stock("Blue Shirt"))
    }

    @Test
    fun malformedProviderOutputNeverReachesTheBackend(): Unit = runBlocking {
        val bad = object : AIProvider {
            override val name = "bad"
            override suspend fun understand(conversation: List<ChatTurn>, input: String) =
                AICommand(Intent.STOCK_IN, 0.99, true, Entities(productName = "Blue Shirt", quantityMilli = -5000))
        }
        val b = FakeBackend()
        b.addProduct("Blue Shirt", stock = 10)
        val r = engine(b, bad).handle("anything")
        assertEquals(ReplyKind.ERROR, r.kind)
        assertTrue(b.executed.isEmpty())
        assertEquals("REJECTED", b.audits.last()[3])
    }

    @Test
    fun confidenceBandsAreRespected(): Unit = runBlocking {
        fun provider(conf: Double) = object : AIProvider {
            override val name = "p"
            override suspend fun understand(conversation: List<ChatTurn>, input: String) =
                AICommand(Intent.STOCK_IN, conf, true, Entities(productName = "Blue Shirt", quantityMilli = 5000))
        }
        val b = FakeBackend()
        b.addProduct("Blue Shirt", stock = 10)
        // below 0.70: never executed
        var e = engine(b, provider(0.5))
        var r = e.handle("blah")
        assertEquals(ReplyKind.ANSWER, r.kind)
        assertFalse(e.awaitingAnswer)
        // 0.70 - 0.89: asks first
        e = engine(b, provider(0.8))
        r = e.handle("blah")
        assertEquals(ReplyKind.CLARIFY, r.kind)
        r = e.handle("yes")
        assertEquals(ReplyKind.CONFIRM, r.kind)
        // 0.90+: straight to confirmation
        e = engine(b, provider(0.95))
        r = e.handle("blah")
        assertEquals(ReplyKind.CONFIRM, r.kind)
        assertEquals(10L, b.stock("Blue Shirt"))
    }

    @Test
    fun providerCrashFallsBackToLocalRules(): Unit = runBlocking {
        val crash = object : AIProvider {
            override val name = "crash"
            override suspend fun understand(conversation: List<ChatTurn>, input: String): AICommand = throw RuntimeException("network down")
        }
        val b = FakeBackend()
        b.addProduct("Blue Shirt", stock = 80)
        val r = engine(b, crash).handle("How many Blue Shirts do I have?")
        assertTrue(r.text, r.text.contains("80 pieces"))
    }

    @Test
    fun everyWriteIsAudited(): Unit = runBlocking {
        val b = FakeBackend()
        b.addProduct("Blue Shirt", stock = 10)
        val e = engine(b)
        e.handle("Add 5 Blue Shirt"); e.confirm()
        val a = b.audits.single()
        assertEquals("STOCK_IN", a[0])
        assertTrue(a[1], a[1].contains("\"productName\":\"Blue Shirt\""))
        assertEquals("SUCCESS", a[3])
    }

    @Test
    fun cloudModelCanChatButNeverWrites(): Unit = runBlocking {
        val chatty = object : AIProvider {
            override val name = "cloud"
            override suspend fun understand(conversation: List<ChatTurn>, input: String) =
                AICommand(Intent.UNKNOWN, 1.0, false, response = "Hi Bharath! You have 3 tea products: Green Tea, Masala Tea and Lemon Tea.", source = "cloud")
        }
        val b = FakeBackend()
        val r = engine(b, chatty).handle("give me the list of tea products")
        assertEquals(ReplyKind.ANSWER, r.kind)
        assertTrue(r.text, r.text.contains("Masala Tea"))
        assertTrue(b.executed.isEmpty())
    }

    @Test
    fun offlineGreetingAndProductListAreFriendly(): Unit = runBlocking {
        val b = FakeBackend()
        b.addProduct("Green Tea", stock = 12)
        b.addProduct("Masala Tea", stock = 7)
        b.addProduct("Blue Shirt", stock = 3)
        val e = engine(b)
        assertTrue(e.handle("hey").text.startsWith("Hello"))
        val r = e.handle("give me the list of tea products")
        assertTrue(r.text, r.text.contains("Green Tea") && r.text.contains("Masala Tea"))
        assertFalse(r.text, r.text.contains("Blue Shirt"))
        assertTrue(e.handle("list my products").text.contains("Blue Shirt"))
    }
}
