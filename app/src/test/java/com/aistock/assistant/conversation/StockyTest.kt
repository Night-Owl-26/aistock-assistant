package com.aistock.assistant.conversation

import com.aistock.assistant.ai.AssistantEngine
import com.aistock.assistant.ai.FakeBackend
import com.aistock.assistant.ai.LocalFallbackAIProvider
import com.aistock.assistant.ai.ReplyKind
import com.aistock.assistant.ai.llm.LlmClient
import com.aistock.assistant.ai.llm.LlmMessage
import com.aistock.assistant.ai.llm.LlmReply
import com.aistock.assistant.ai.llm.ToolCall
import com.aistock.assistant.ai.llm.ToolSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conversation loop with a scripted stand-in for the language model. These tests prove what the APP guarantees
 * whatever a model says: tools return real data, changes wait for confirmation, corrections replace, and "done" is
 * only reported after the data really changed. They do not test how well a real model understands people.
 */
class StockyTest {
    /** Plays back one scripted step per model call and records what the app sent. */
    private class ScriptedLlm(private vararg val steps: (List<LlmMessage>) -> LlmReply) : LlmClient {
        override val name = "scripted"
        var calls = 0
        val seen = ArrayList<List<LlmMessage>>()
        var toolsOffered: List<ToolSpec> = emptyList()
        override suspend fun chat(messages: List<LlmMessage>, tools: List<ToolSpec>): LlmReply {
            seen.add(messages.toList()); toolsOffered = tools
            return steps[calls++](messages)
        }
    }

    private fun call(name: String, vararg args: Pair<String, String>) = LlmReply("", listOf(ToolCall("c$name", name, mapOf(*args))))
    private fun lastTool(m: List<LlmMessage>) = (m.last() as LlmMessage.ToolResult).text
    private fun say(text: String) = LlmReply(text)

    private fun stocky(b: FakeBackend, llm: LlmClient?) = Stocky(b, AssistantEngine(b, LocalFallbackAIProvider()), { llm })

    @Test
    fun answersComeFromToolDataNotFromTheModel(): Unit = runBlocking {
        val b = FakeBackend()
        b.addProduct("Blue Cotton", unit = "METER", stock = 486)
        val llm = ScriptedLlm(
            { call("find_products", "query" to "blue cotton") },
            { m -> say("Blue Cotton currently " + Regex("(\\d+) meter").find(lastTool(m))!!.groupValues[1] + " metres stock-la irukku.") },
        )
        val r = stocky(b, llm).handle("Blue cotton stock evlo?")
        assertEquals(ReplyKind.ANSWER, r.kind)
        assertEquals("Blue Cotton currently 486 metres stock-la irukku.", r.text)
        val system = (llm.seen[0][0] as LlmMessage.System).text
        assertTrue(system.contains("Stocky") && system.contains("Tanglish") && system.contains("Never invent"))
        assertTrue(llm.toolsOffered.any { it.name == "propose_stock_in" } && llm.toolsOffered.any { it.name == "confirm_pending" })
    }

    @Test
    fun followUpUsesTheProductFromTheConversationAndWaitsForYes(): Unit = runBlocking {
        val b = FakeBackend()
        b.addProduct("Blue Cotton", unit = "METER", stock = 486)
        val llm = ScriptedLlm(
            { call("find_products", "query" to "blue cotton") }, { say("486 metres.") },
            { call("propose_stock_in", "product" to "", "quantity" to "50", "unit" to "metre") },
            { m -> assertTrue(lastTool(m), lastTool(m).contains("WAITING FOR THE OWNER")); say("Sure. 50 metres add pannalama? Current stock 486, after update 536 metres aagum.") },
        )
        val s = stocky(b, llm)
        s.handle("Blue cotton stock evlo?")
        val r = s.handle("50 metre add pannidu.")
        assertEquals(ReplyKind.CONFIRM, r.kind)
        assertTrue(r.text.contains("536"))
        assertTrue(r.lines.toString(), r.lines.any { it.value.contains("536") })
        assertEquals("nothing is saved before the owner agrees", 486L, b.stock("Blue Cotton"))
        // The second question carried the context of the first.
        assertTrue((llm.seen[2][0] as LlmMessage.System).text.contains("product being discussed = Blue Cotton"))
        val done = s.handle("Yes")
        assertEquals(ReplyKind.DONE, done.kind)
        assertEquals(536L, b.stock("Blue Cotton"))
        assertEquals("a plain yes does not need the model", 4, llm.calls)
    }

    @Test
    fun correctionReplacesTheProposal(): Unit = runBlocking {
        val b = FakeBackend()
        b.addProduct("Blue Cotton", unit = "METER", stock = 486)
        val llm = ScriptedLlm(
            { call("propose_stock_in", "product" to "Blue Cotton", "quantity" to "20") }, { say("20 metres add pannalama?") },
            { m -> assertTrue((m[0] as LlmMessage.System).text.contains("WAITING FOR THE OWNER'S YES/NO")); call("propose_stock_in", "product" to "same", "quantity" to "30") },
            { say("Seri, 30 metres. Add pannalama?") },
            { call("confirm_pending") }, { say("ignored") },
        )
        val s = stocky(b, llm)
        s.handle("Blue cotton la 20 metre add pannunga")
        s.handle("Actually 30 add pannunga")
        val r = s.handle("seri pannidu")
        assertEquals(ReplyKind.DONE, r.kind)
        assertEquals(516L, b.stock("Blue Cotton"))
        assertEquals(1, b.executed.size)
    }

    @Test
    fun aFailedSaveIsNeverReportedAsDone(): Unit = runBlocking {
        val b = FakeBackend()
        b.addProduct("Blue Cotton", unit = "METER", stock = 486)
        val llm = ScriptedLlm(
            { call("propose_stock_in", "product" to "Blue Cotton", "quantity" to "10") }, { say("Add 10?") },
            { call("confirm_pending") }, { say("Done! Added 10 metres.") },
        )
        val s = stocky(b, llm)
        s.handle("add 10 blue cotton")
        b.failNext = true
        val r = s.handle("ok go on then")
        assertEquals(ReplyKind.ERROR, r.kind)
        assertTrue(r.text, r.text.contains("not changed"))
        assertFalse(r.text.contains("Done"))
        assertEquals(486L, b.stock("Blue Cotton"))
    }

    @Test
    fun businessRulesStillApplyToModelProposals(): Unit = runBlocking {
        val b = FakeBackend()
        b.addProduct("Blue Shirt", stock = 5, sell = 450)
        val llm = ScriptedLlm(
            { call("propose_sale", "product" to "Blue Shirt", "quantity" to "20") },
            { m -> assertTrue(lastTool(m), lastTool(m).startsWith("NOT POSSIBLE")); say("Blue Shirt-la 5 pieces dhaan irukku, 20 sell panna mudiyadhu.") },
            { call("propose_sale", "product" to "Red Saree", "quantity" to "1") }, { say("Red Saree kidaikkala.") },
            { call("propose_stock_out", "product" to "Blue Shirt", "quantity" to "-3") }, { say("no") },
        )
        val s = stocky(b, llm)
        assertEquals(ReplyKind.ANSWER, s.handle("sell 20 blue shirts").kind)
        s.handle("sell 1 red saree")
        s.handle("remove minus three")
        assertFalse(s.awaitingConfirmation)
        assertTrue(b.executed.isEmpty())
        assertEquals(1, b.productList.size)
    }

    @Test
    fun paymentsBalancesAndPartyContext(): Unit = runBlocking {
        val b = FakeBackend()
        b.addCustomer("Kumar Traders"); b.customers[0] = b.customers[0].copy(balancePaise = 900_000)
        b.activity.add("Sale INV-0003 to Kumar Traders ₹4,200")
        val llm = ScriptedLlm(
            { call("find_party", "name" to "Kumar Traders") }, { m -> say(lastTool(m)) },
            { call("recent_activity", "party" to "same", "limit" to "1") }, { m -> say(lastTool(m)) },
            { call("propose_payment", "party" to "same", "amount" to "5000") }, { say("₹5,000 received-nu record pannalama?") },
        )
        val s = stocky(b, llm)
        assertTrue(s.handle("Kumar Traders balance evlo?").text.contains("₹9,000"))
        assertTrue(s.handle("Avanga last purchase enna?").text.contains("INV-0003"))
        val r = s.handle("5000 payment panniten")
        assertEquals(ReplyKind.CONFIRM, r.kind)
        s.confirm()
        assertEquals(400_000L, b.customers[0].balancePaise)
    }

    @Test
    fun workbookSheetsColumnsAndRows(): Unit = runBlocking {
        val b = FakeBackend()
        b.sheetData["CUSTOMER LIST"] = arrayListOf(listOf("Name", "Phone"), listOf("Kumar Traders", "98765"))
        b.sheetPurpose["CUSTOMER LIST"] = "CUSTOMERS"
        val llm = ScriptedLlm(
            { call("propose_create_sheet", "name" to "damaged stock") }, { say("Create pannalama?") },
            { call("propose_add_row", "sheet" to "Damaged Stock", "values" to "Product=Blue Cotton; Quantity=5 metre; Reason=damaged") }, { say("Add pannalama?") },
            { call("propose_add_column", "sheet" to "customer", "column" to "GST Number") }, { say("Add pannalama?") },
            { call("search_sheet", "sheet" to "CUSTOMER LIST", "query" to "kumar") }, { m -> say(lastTool(m)) },
            { call("propose_add_row", "sheet" to "Damaged Stock", "values" to "Colour=Blue") }, { m -> assertTrue(lastTool(m).startsWith("NOT POSSIBLE")); say("That column is not there.") },
        )
        val s = stocky(b, llm)
        assertEquals(ReplyKind.CONFIRM, s.handle("Damaged stock-ku new sheet create pannu").kind); s.handle("yes")
        s.handle("Blue cotton 5 metre damaged"); s.handle("yes")
        s.handle("Customer sheet-la GST number column add pannu"); s.handle("yes")
        assertEquals(listOf("", "Blue Cotton", "5 metre", "damaged"), b.sheetData.getValue("Damaged Stock")[1])
        assertEquals(listOf("Name", "Phone", "GST Number"), b.sheetData.getValue("CUSTOMER LIST")[0])
        assertTrue(s.handle("find kumar in customer list").text.contains("98765"))
        s.handle("add colour blue")
        assertEquals(2, b.sheetData.getValue("Damaged Stock").size)
        // The model was told about the workbook.
        assertTrue((llm.seen.last()[0] as LlmMessage.System).text.contains("CUSTOMER LIST"))
    }

    @Test
    fun withoutAModelOrWhenItFailsTheOnDeviceRulesAnswer(): Unit = runBlocking {
        val b = FakeBackend()
        b.addProduct("Blue Shirt", stock = 80)
        val offline = stocky(b, null)
        assertTrue(offline.handle("How many Blue Shirts do I have?").text.contains("80 pieces"))
        assertEquals("local", offline.lastSource)
        val broken = stocky(b, ScriptedLlm({ throw RuntimeException("no internet") }))
        assertTrue(broken.handle("How many Blue Shirts do I have?").text.contains("80 pieces"))
        assertEquals("local", broken.lastSource)
    }

    @Test
    fun oldTurnsAreSummarisedInsteadOfSentForever(): Unit = runBlocking {
        val b = FakeBackend()
        val llm = ScriptedLlm(*Array(12) { i -> { _: List<LlmMessage> -> say("reply $i") } })
        val s = stocky(b, llm)
        repeat(12) { s.handle("question number $it") }
        val last = llm.seen.last()
        assertTrue("history sent to the model stays bounded", last.size <= 16)
        assertTrue((last[0] as LlmMessage.System).text.contains("question number 0"))
        val ctx = s.exportContext()
        assertTrue(ctx.getValue("summary").contains("question number 0"))
    }
}

class StockyFailureTest {
    private val failing = object : com.aistock.assistant.ai.llm.LlmClient {
        override val name = "x"
        override suspend fun chat(messages: List<com.aistock.assistant.ai.llm.LlmMessage>, tools: List<com.aistock.assistant.ai.llm.ToolSpec>): com.aistock.assistant.ai.llm.LlmReply =
            throw com.aistock.assistant.ai.llm.LlmException(400, "API key not valid. Please pass a valid API key.")
    }

    @org.junit.Test
    fun a_failed_model_call_is_explained_instead_of_hidden() = kotlinx.coroutines.runBlocking {
        val b = FakeBackend()
        val s = Stocky(b, AssistantEngine(b, LocalFallbackAIProvider()), { failing })
        val r = s.handle("how is business going these days")
        org.junit.Assert.assertEquals(com.aistock.assistant.ai.ReplyKind.ERROR, r.kind)
        org.junit.Assert.assertTrue(r.text, r.text.contains("did not accept the AI key"))
        org.junit.Assert.assertTrue(s.lastError != null)
    }
}

class StockyCountTest {
    @org.junit.Test
    fun counting_a_sheet_uses_every_row() = kotlinx.coroutines.runBlocking {
        val b = object : FakeBackend() {
            override suspend fun sheetRows(sheet: String, query: String, limit: Int): List<List<String>> =
                listOf(listOf("Region", "Promotion", "Quantity", "UnitPrice")) + (1..300).map { listOf(if (it % 2 == 0) "North" else "South", if (it % 3 == 0) "FREESHIP" else "", "2", "10") }
        }
        var seen = ""
        val llm = object : com.aistock.assistant.ai.llm.LlmClient {
            var n = 0
            override val name = "x"
            override suspend fun chat(messages: List<com.aistock.assistant.ai.llm.LlmMessage>, tools: List<com.aistock.assistant.ai.llm.ToolSpec>): com.aistock.assistant.ai.llm.LlmReply {
                if (n++ == 0) return com.aistock.assistant.ai.llm.LlmReply("", listOf(com.aistock.assistant.ai.llm.ToolCall("c", "count_sheet", mapOf("sheet" to "Sheet1", "where" to "promotion=freeship", "group_by" to "Region", "sum" to "Quantity", "times" to "UnitPrice"))))
                seen = (messages.last() as com.aistock.assistant.ai.llm.LlmMessage.ToolResult).text
                return com.aistock.assistant.ai.llm.LlmReply("100 orders")
            }
        }
        Stocky(b, AssistantEngine(b, LocalFallbackAIProvider()), { llm }).handle("how many free ship orders")
        org.junit.Assert.assertTrue(seen, seen.startsWith("EXACT: 100 of 300 rows match. Sum of Quantity x UnitPrice = 2000."))
        org.junit.Assert.assertTrue(seen, seen.contains("- North: 50 rows, sum 1000"))
    }
}

class StockyOwnerCheckTest {
    @org.junit.Test
    fun the_model_cannot_confirm_a_payment_by_itself() = kotlinx.coroutines.runBlocking {
        val b = FakeBackend()
        val kumar = b.addCustomer("Kumar Traders")
        b.customers[0] = kumar.copy(balancePaise = 900_000)
        var step = 0
        var toolText = ""
        val llm = object : com.aistock.assistant.ai.llm.LlmClient {
            override val name = "x"
            override suspend fun chat(messages: List<com.aistock.assistant.ai.llm.LlmMessage>, tools: List<com.aistock.assistant.ai.llm.ToolSpec>): com.aistock.assistant.ai.llm.LlmReply {
                val last = messages.last()
                if (last is com.aistock.assistant.ai.llm.LlmMessage.ToolResult) { toolText = last.text; return com.aistock.assistant.ai.llm.LlmReply("ok") }
                step++
                return if (step == 1) com.aistock.assistant.ai.llm.LlmReply("", listOf(com.aistock.assistant.ai.llm.ToolCall("p", "propose_payment", mapOf("party" to "Kumar Traders", "amount" to "5000", "direction" to "received"))))
                else com.aistock.assistant.ai.llm.LlmReply("", listOf(com.aistock.assistant.ai.llm.ToolCall("c", "confirm_pending", emptyMap())))
            }
        }
        val s = Stocky(b, AssistantEngine(b, LocalFallbackAIProvider(), ownerCheck = { true }), { llm })
        s.handle("Kumar paid 5000")
        val r = s.handle("go ahead and record it please")
        org.junit.Assert.assertTrue(toolText, toolText.startsWith("NOT DONE YET"))
        org.junit.Assert.assertEquals(com.aistock.assistant.ai.ReplyKind.CONFIRM, r.kind)
        org.junit.Assert.assertEquals(900_000L, b.customers[0].balancePaise)
    }
}

class StockySafetyTest {
    private fun script(vararg replies: com.aistock.assistant.ai.llm.LlmReply) = object : com.aistock.assistant.ai.llm.LlmClient {
        var i = 0
        override val name = "x"
        override suspend fun chat(messages: List<com.aistock.assistant.ai.llm.LlmMessage>, tools: List<com.aistock.assistant.ai.llm.ToolSpec>) = replies[minOf(i++, replies.size - 1)]
    }
    private fun call(name: String, vararg a: Pair<String, String>) = com.aistock.assistant.ai.llm.LlmReply("", listOf(com.aistock.assistant.ai.llm.ToolCall("c$name", name, mapOf(*a))))

    @org.junit.Test
    fun the_model_cannot_propose_and_confirm_in_one_go() = kotlinx.coroutines.runBlocking {
        val b = FakeBackend()
        b.addProduct("Blue Cotton", unit = "METER", stock = 100)
        val llm = script(call("propose_stock_in", "product" to "Blue Cotton", "quantity" to "10"), call("confirm_pending"), com.aistock.assistant.ai.llm.LlmReply("Please check and say yes."))
        val r = Stocky(b, AssistantEngine(b, LocalFallbackAIProvider()), { llm }).handle("blue cotton 10 metre vandhuchu, save it")
        org.junit.Assert.assertEquals(com.aistock.assistant.ai.ReplyKind.CONFIRM, r.kind)
        org.junit.Assert.assertEquals(100L, b.stock("Blue Cotton"))
    }

    @org.junit.Test
    fun an_old_proposal_is_not_saved_by_a_later_unrelated_yes() = kotlinx.coroutines.runBlocking {
        val b = FakeBackend()
        b.addProduct("Blue Cotton", unit = "METER", stock = 100)
        val llm = script(
            call("propose_stock_in", "product" to "Blue Cotton", "quantity" to "10"), com.aistock.assistant.ai.llm.LlmReply("Add 10 metres?"),
            com.aistock.assistant.ai.llm.LlmReply("You have 100 metres. Shall I show last week too?"),
            com.aistock.assistant.ai.llm.LlmReply("Here is last week."),
        )
        val s = Stocky(b, AssistantEngine(b, LocalFallbackAIProvider()), { llm })
        org.junit.Assert.assertEquals(com.aistock.assistant.ai.ReplyKind.CONFIRM, s.handle("10 metre blue cotton add").kind)
        s.handle("wait, how much do I have now?")
        s.handle("yes")
        org.junit.Assert.assertEquals(100L, b.stock("Blue Cotton"))
    }
}
