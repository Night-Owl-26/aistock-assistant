package com.aistock.assistant.business

import com.aistock.assistant.ai.AssistantEngine
import com.aistock.assistant.ai.FakeBackend
import com.aistock.assistant.ai.LocalFallbackAIProvider
import com.aistock.assistant.ai.ReplyKind
import com.aistock.assistant.ai.llm.LlmClient
import com.aistock.assistant.ai.llm.LlmMessage
import com.aistock.assistant.ai.llm.LlmReply
import com.aistock.assistant.ai.llm.ToolCall
import com.aistock.assistant.ai.llm.ToolSpec
import com.aistock.assistant.conversation.Stocky
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class BusinessPlatformTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-10-17T05:00:00Z") // 10:30 in India

    /** A model that plays back a script and remembers the last tool result it was given. */
    private class Script(vararg val steps: LlmReply) : LlmClient {
        var i = 0
        val toolResults = ArrayList<String>()
        override val name = "script"
        override suspend fun chat(messages: List<LlmMessage>, tools: List<ToolSpec>): LlmReply {
            (messages.last() as? LlmMessage.ToolResult)?.let { toolResults.add(it.text) }
            return steps[minOf(i++, steps.size - 1)]
        }
    }
    private fun call(name: String, vararg a: Pair<String, String>) = LlmReply("", listOf(ToolCall("c${name}", name, mapOf(*a))))
    private fun say(t: String) = LlmReply(t)

    private fun stocky(b: FakeBackend, biz: FakeBusinessBackend, llm: LlmClient?) =
        Stocky(b, AssistantEngine(b, LocalFallbackAIProvider(), zone = { zone }, now = { now }), { llm }, zone = { zone }, now = { now }, business = biz).also { b.business = biz }

    @Test
    fun every_template_is_a_valid_configuration_and_they_differ() {
        for (t in Templates.ALL) assertEquals(t.type, emptyList<String>(), t.config.validate())
        val garment = Templates.of("garment_manufacturing").config
        val repair = Templates.of("service_repair").config
        val salon = Templates.of("salon").config
        assertTrue(garment.has(Module.PRODUCTION) && !garment.has(Module.APPOINTMENTS))
        assertTrue(repair.has(Module.SERVICE_TICKETS) && repair.fields(Module.SERVICE_TICKETS).any { it.key == "estimate" })
        assertTrue(salon.has(Module.APPOINTMENTS) && !salon.has(Module.PRODUCTION))
        assertEquals("Stitching", garment.workflow(Module.PRODUCTION).nextStage("Cutting"))
        assertNull(garment.workflow(Module.PRODUCTION).nextStage("Dispatched"))
    }

    @Test
    fun bad_configurations_are_refused() {
        val ok = Templates.DEFAULT
        assertTrue(ok.copy(businessType = "spaceship").validate().isNotEmpty())
        assertTrue(ok.copy(modules = emptySet()).validate().isNotEmpty())
        assertTrue(ok.copy(workflows = mapOf(Module.TASKS to Workflow(listOf("A", "a"), listOf("A")))).validate().isNotEmpty())
        assertTrue(ok.copy(workflows = mapOf(Module.TASKS to Workflow(listOf("Done"), listOf("Done")))).validate().isNotEmpty(), "needs an open status")
        assertTrue(ok.copy(customFields = listOf(CustomField(Module.TASKS, "Bad Key", "x", FieldType.TEXT))).validate().isNotEmpty())
    }

    private fun assertTrue(c: Boolean, msg: String) = org.junit.Assert.assertTrue(msg, c)

    @Test
    fun the_owner_describing_the_business_finds_the_right_template() {
        assertEquals("garment_manufacturing", Templates.guess("Naan Tiruppur-la garment manufacturing business run panren")?.type)
        assertEquals("service_repair", Templates.guess("mobile repair shop")?.type)
        assertEquals("salon", Templates.guess("ladies beauty parlour")?.type)
        assertEquals("retail", Templates.guess("fancy store")?.type)
    }

    @Test
    fun setting_up_a_garment_unit_by_talking_needs_the_owners_yes() = runBlocking {
        val b = FakeBackend(); val biz = FakeBusinessBackend()
        val llm = Script(
            call("propose_business_setup", "business_type" to "garment_manufacturing", "stages" to "production=Cutting|Stitching|Ironing|Packing", "names" to "production=Lot"),
            say("Ungal process-ku app-a set pannattuma?"),
        )
        val s = stocky(b, biz, llm)
        val r = s.handle("Naan Tiruppur-la garment manufacturing panren. Cutting, stitching, ironing, packing.")
        assertEquals(ReplyKind.CONFIRM, r.kind)
        assertFalse("nothing saved before the yes", biz.isConfigured)
        assertTrue(r.lines.any { it.value.contains("Cutting › Stitching › Ironing › Packing") })
        assertEquals(ReplyKind.DONE, s.handle("seri").kind)
        assertTrue(biz.isConfigured)
        assertEquals(listOf("Cutting", "Stitching", "Ironing", "Packing"), biz.cfg.workflow(Module.PRODUCTION).stages)
        assertEquals("Lot", biz.cfg.term("production", ""))
    }

    @Test
    fun production_order_created_moved_and_progress_recorded() = runBlocking {
        val b = FakeBackend(); b.addCustomer("Ramesh Exports")
        val biz = FakeBusinessBackend(Templates.of("garment_manufacturing").config, true)
        biz.staffList.add(StaffRef("s1", "Ravi", "", "tailor"))
        val llm = Script(
            call("propose_new_work", "module" to "production", "title" to "500 polo t-shirts", "customer" to "ramesh", "quantity" to "500", "due" to "2026-10-25", "staff" to "ravi", "fields" to "buyer_po=PO-881"),
            say("500 polo t-shirts order create pannattuma?"),
            call("propose_update_work", "ref" to "polo", "stage" to "next", "done_quantity" to "120"),
            say("Stitching-ku move panni 120 done-nu podattuma?"),
        )
        val s = stocky(b, biz, llm)
        val r = s.handle("Ramesh Exports-ku 500 polo t-shirt order vandhirukku, 25th delivery, Ravi-ku kudu")
        assertEquals(ReplyKind.CONFIRM, r.kind)
        assertTrue(r.lines.any { it.label == "Buyer PO" && it.value == "PO-881" })
        assertEquals(ReplyKind.DONE, s.handle("yes").kind)
        val item = biz.items.single()
        assertEquals("PO-0001", item.number); assertEquals("Cutting", item.stage); assertEquals("Ravi", item.staffName)
        assertEquals(500_000L, item.quantityMilli)
        assertEquals(ReplyKind.CONFIRM, s.handle("polo order cutting mudinjiduchu, 120 pieces stitching-la irukku").kind)
        assertEquals(ReplyKind.DONE, s.handle("ok").kind)
        assertEquals("Stitching", biz.items.single().stage)
        assertEquals(120_000L, biz.items.single().doneMilli)
    }

    @Test
    fun a_repeated_request_does_not_create_two_items() = runBlocking {
        val biz = FakeBusinessBackend(Templates.of("service_repair").config, true)
        val first = biz.execute(BusinessAction.NewWork(Module.SERVICE_TICKETS, "Redmi screen", null, "Priya", null, null, "Open", "Received", null, null, null, null, 0, emptyMap(), "", "req-1"))
        val again = biz.execute(BusinessAction.NewWork(Module.SERVICE_TICKETS, "Redmi screen", null, "Priya", null, null, "Open", "Received", null, null, null, null, 0, emptyMap(), "", "req-1"))
        assertTrue(first.ok); assertFalse(again.ok); assertEquals("DUPLICATE", again.error)
        assertEquals(1, biz.items.size)
    }

    @Test
    fun follow_up_created_then_completed_with_the_next_one_booked() = runBlocking {
        val b = FakeBackend(); b.addCustomer("Priya")
        val biz = FakeBusinessBackend()
        val llm = Script(
            call("propose_followup", "person" to "priya", "kind" to "CALL", "due" to "2026-10-18T11:00", "note" to "new collection"),
            say("Naalaikku 11 manikku Priya-ku call remind pannattuma?"),
            call("propose_complete_followup", "ref" to "priya", "result" to "Will visit Saturday", "next_due" to "2026-10-24T10:00", "next_note" to "visit"),
            say("Seri, save pannattuma?"),
        )
        val s = stocky(b, biz, llm)
        assertEquals(ReplyKind.CONFIRM, s.handle("Antha customer Priya-ku naalaikku call panna remind pannu").kind)
        assertEquals(ReplyKind.DONE, s.handle("seri").kind)
        assertEquals(1, biz.reminders.size)
        assertEquals(Instant.parse("2026-10-18T05:30:00Z").toEpochMilli(), biz.followupList.single().dueAt)
        s.handle("Priya kitta pesiten, Saturday varuvanga")
        s.handle("ok")
        assertEquals("DONE", biz.followupList[0].status)
        assertEquals("PENDING", biz.followupList[1].status)
    }

    @Test
    fun a_follow_up_in_the_past_is_refused() = runBlocking {
        val b = FakeBackend(); val biz = FakeBusinessBackend()
        val llm = Script(call("propose_followup", "person" to "Kumar", "due" to "2026-10-10T10:00"), say("That time has passed."))
        val s = stocky(b, biz, llm)
        assertEquals(ReplyKind.ANSWER, s.handle("remind me to call Kumar last week").kind)
        assertTrue(llm.toolResults.single().contains("already passed"))
        assertTrue(biz.followupList.isEmpty())
    }

    @Test
    fun a_message_is_only_drafted_and_a_call_only_offered() = runBlocking {
        val b = FakeBackend()
        val priya = b.addCustomer("Priya"); b.customers[0] = priya.copy(phone = "9876543210")
        val biz = FakeBusinessBackend()
        val llm = Script(
            call("draft_message", "customer" to "priya", "message" to "Vanakkam Priya! Pudhu collection vandhirukku. Kadaikku vaanga."),
            say("Message ready. WhatsApp-la anuppalaam."),
            call("call_customer", "customer" to "priya"),
            say("Call button kaattirukken."),
        )
        val s = stocky(b, biz, llm)
        val r = s.handle("Priya-ku new collection vandhirukku nu message anuppanum")
        assertEquals(listOf("WHATSAPP", "SMS"), r.actions.map { it.kind })
        assertEquals("9876543210", r.actions[0].phone)
        assertTrue(llm.toolResults[0].contains("NOT SENT"))
        assertTrue("nothing is logged as sent", biz.contacts.isEmpty())
        val c = s.handle("call Priya")
        assertEquals("CALL", c.actions.single().kind)
    }

    @Test
    fun the_model_cannot_confirm_a_business_change_it_just_proposed() = runBlocking {
        val b = FakeBackend(); val biz = FakeBusinessBackend()
        val llm = Script(call("propose_add_staff", "name" to "Ravi"), call("confirm_pending"), say("Done!"))
        val s = stocky(b, biz, llm)
        assertEquals(ReplyKind.CONFIRM, s.handle("Ravi-a staff-a add pannu, save it").kind)
        assertTrue(biz.staffList.isEmpty())
    }

    @Test
    fun briefing_compares_periods_and_labels_estimates() {
        val p = { name: String, buy: Long, sell: Long, stock: Long, min: Long, last: Long? -> ProductActivity(name, "PIECE", stock, buy, sell, min, last, 0) }
        val nowMs = now.toEpochMilli()
        val input = InsightsInput(
            nowMs, 7, salesNow = 1_200_000, salesPrev = 1_000_000, billsNow = 12, expensesNow = 200_000, expensesPrev = 100_000,
            productSalesNow = listOf(ProductSale("Pink clip", "PIECE", 60_000, 1_200_000)),
            products = listOf(p("Pink clip", 1_000, 2_000, 5_000, 10_000, nowMs), p("Old bangle", 5_000, 4_000, 20_000, 0, null)),
            openWork = emptyList(), pendingFollowups = emptyList(), expiring = emptyList(),
        )
        val list = Insights.build(input)
        val text = Insights.forModel(list)
        assertTrue(text, text.contains("FACT: Sales in the last 7 days: ₹12,000 from 12 bills, up 20% from ₹10,000"))
        assertTrue(text, text.contains("ESTIMATE: Estimated gross margin"))
        assertTrue(text, text.contains("Low stock: Pink clip 5 piece left (about 115 more covers 2 weeks)"))
        assertTrue(text, text.contains("at or below cost for: Old bangle"))
        assertTrue(text, text.contains("Not sold in 30 days: Old bangle"))
        assertTrue(text, text.contains("100% more"))
    }

    @Test
    fun with_no_cost_price_profit_is_not_claimed() {
        val input = InsightsInput(now.toEpochMilli(), 1, 500_000, 0, 3, 0, 0, listOf(ProductSale("Clip", "PIECE", 10_000, 500_000)),
            listOf(ProductActivity("Clip", "PIECE", 1_000, 0, 50_000, 0, now.toEpochMilli(), 0)), emptyList(), emptyList(), emptyList())
        val text = Insights.forModel(Insights.build(input))
        assertFalse(text, text.contains("margin:"))
        assertTrue(text, text.contains("Profit cannot be worked out yet"))
    }
}
