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
    fun offlineGreetingAndProductListAreFriendly(): Unit = runBlocking {
        val b = FakeBackend()
        b.addProduct("Green Tea", stock = 12)
        b.addProduct("Masala Tea", stock = 7)
        b.addProduct("Blue Shirt", stock = 3)
        val e = engine(b)
        val hello = e.handle("hey")
        assertTrue(hello.text, hello.text.contains("sales") && hello.text.contains("What would you like"))
        val r = e.handle("give me the list of tea products")
        assertTrue(r.text, r.text.contains("Green Tea") && r.text.contains("Masala Tea"))
        assertFalse(r.text, r.text.contains("Blue Shirt"))
        assertTrue(e.handle("list my products").text.contains("Blue Shirt"))
    }

    @Test
    fun paymentInMixedLanguageNeedsConfirmation(): Unit = runBlocking {
        val b = FakeBackend()
        val kumar = b.addCustomer("Kumar Traders")
        b.customers[0] = kumar.copy(balancePaise = 900_000)
        val e = engine(b)
        val r = e.handle("Kumar Traders-ku 5000 payment panniten")
        assertEquals(r.text, ReplyKind.CONFIRM, r.kind)
        assertTrue(r.text, r.text.contains("Kumar Traders") && r.text.contains("₹5,000"))
        assertEquals(900_000L, b.customers[0].balancePaise)
        e.handle("yes")
        assertEquals(400_000L, b.customers[0].balancePaise)
    }

    @Test
    fun undoThatReversesTheLastChangeAfterConfirmation(): Unit = runBlocking {
        val b = FakeBackend()
        b.addProduct("Blue Cotton", unit = "METER", stock = 486)
        val e = engine(b)
        assertEquals(ReplyKind.ANSWER, e.handle("undo that").kind)
        e.handle("Add 50 meters of Blue Cotton"); e.confirm()
        assertEquals(536L, b.stock("Blue Cotton"))
        val ask = e.handle("Undo that.")
        assertEquals(ReplyKind.CONFIRM, ask.kind)
        assertEquals(536L, b.stock("Blue Cotton"))
        assertEquals(ReplyKind.DONE, e.handle("yes").kind)
        assertEquals(486L, b.stock("Blue Cotton"))
    }

    @Test
    fun correctionReplacesTheWaitingQuantityInsteadOfAddingTwice(): Unit = runBlocking {
        val b = FakeBackend()
        b.addProduct("Blue Cotton", unit = "METER", stock = 486)
        val e = engine(b)
        e.handle("Add 20 meters of Blue Cotton")
        var r = e.handle("Actually 30 add pannunga")
        assertEquals(ReplyKind.CONFIRM, r.kind)
        assertTrue(r.text, r.text.contains("30"))
        r = e.handle("Actually 5 illa 8 metre dhaan")
        assertTrue(r.text, r.text.contains("8 "))
        e.confirm()
        assertEquals(494L, b.stock("Blue Cotton"))
        assertEquals(1, b.executed.size)
    }

    @Test
    fun ownerWordsForProductsAreLearnedAndUsed(): Unit = runBlocking {
        val b = FakeBackend()
        b.addProduct("Blue Cotton 40s Premium", unit = "METER", stock = 100)
        val e = engine(b)
        var r = e.handle("Add 20 meters of blue cotton")
        assertTrue(r.text, r.text.contains("Did you mean"))
        r = e.handle("yes")
        assertEquals(ReplyKind.CONFIRM, r.kind)
        assertTrue(r.text, r.text.contains("remember"))
        e.confirm()
        assertEquals("Blue Cotton 40s Premium", b.aliasList.single().productName)
        // Next time there is no question.
        r = e.handle("blue cotton stock evlo")
        assertTrue(r.text, r.text.contains("120"))
        r = e.handle("Remove 5 meters of blue cotton because damaged")
        assertEquals(ReplyKind.CONFIRM, r.kind)
    }

    @Test
    fun sheetsAndColumnsCanBeCreatedOfflineToo(): Unit = runBlocking {
        val b = FakeBackend()
        b.sheetData["CUSTOMER LIST"] = arrayListOf(listOf("Name", "Phone"), listOf("Kumar Traders", "98765"))
        b.sheetPurpose["CUSTOMER LIST"] = "CUSTOMERS"
        val e = engine(b)
        var r = e.handle("Create a new sheet for damaged items")
        assertEquals(r.text, ReplyKind.CONFIRM, r.kind)
        assertTrue(b.sheetData.size == 1)
        e.confirm()
        assertEquals(listOf("Date", "Product", "Quantity", "Reason"), b.sheetData.getValue("Damaged Items")[0])
        r = e.handle("In the customer sheet add GST Number column")
        assertEquals(r.text, ReplyKind.CONFIRM, r.kind)
        e.handle("yes")
        assertEquals(listOf("Name", "Phone", "GST Number"), b.sheetData.getValue("CUSTOMER LIST")[0])
        assertEquals("existing rows are kept", "Kumar Traders", b.sheetData.getValue("CUSTOMER LIST")[1][0])
        assertEquals(ReplyKind.ERROR, e.handle("In the customer sheet add GST Number column").kind)
    }

    @Test
    fun everyConfirmedActionIsLoggedWithBeforeAndAfter(): Unit = runBlocking {
        val b = FakeBackend()
        b.addProduct("Blue Cotton", unit = "METER", stock = 486)
        val e = engine(b)
        e.handle("10 metre blue cotton add pannidu"); e.confirm()
        val a = b.actions.single()
        assertEquals("10 metre blue cotton add pannidu", a.command)
        assertEquals("Blue Cotton", a.subject)
        assertTrue(a.before, a.before.startsWith("486"))
        assertTrue(a.after, a.after.startsWith("496"))
        assertTrue(a.confirmed)
    }

    @Test
    fun aSpokenYesIsNotEnoughForAPaymentWhenOwnerCheckIsOn(): Unit = runBlocking {
        val b = FakeBackend()
        val kumar = b.addCustomer("Kumar Traders")
        b.customers[0] = kumar.copy(balancePaise = 900_000)
        val e = AssistantEngine(b, LocalFallbackAIProvider(), ownerCheck = { true })
        e.handle("Kumar Traders-ku 5000 payment panniten")
        assertTrue(e.needsOwnerCheck)
        val r = e.handle("yes")
        assertEquals(ReplyKind.CONFIRM, r.kind)
        assertTrue(r.text, r.text.contains("fingerprint"))
        assertEquals(900_000L, b.customers[0].balancePaise)
        // The Confirm button (after the phone lock) still works.
        assertEquals(ReplyKind.DONE, e.confirm().kind)
        assertEquals(400_000L, b.customers[0].balancePaise)
    }

    @Test
    fun ordinaryStockInStillConfirmsByVoiceWithOwnerCheckOn(): Unit = runBlocking {
        val b = FakeBackend()
        b.addProduct("Blue Cotton", unit = "METER", stock = 486)
        val e = AssistantEngine(b, LocalFallbackAIProvider(), ownerCheck = { true })
        e.handle("Add 50 meters of Blue Cotton")
        assertEquals(false, e.needsOwnerCheck)
        assertEquals(ReplyKind.DONE, e.handle("yes").kind)
        assertEquals(536L, b.stock("Blue Cotton"))
    }
}
