package com.aistock.assistant.data

import com.aistock.assistant.ai.AssistantEngine
import com.aistock.assistant.ai.LocalFallbackAIProvider
import com.aistock.assistant.ai.PartyKind
import com.aistock.assistant.ai.Plan
import com.aistock.assistant.ai.ReplyKind
import com.aistock.assistant.business.BusinessAction
import com.aistock.assistant.business.BusinessConfigJson
import com.aistock.assistant.business.Insights
import com.aistock.assistant.business.Module
import com.aistock.assistant.business.Templates
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

/** The business platform on the real database: what Stocky saves is really there afterwards. */
@RunWith(RobolectricTestRunner::class)
class BusinessRepositoryTest {
    private lateinit var env: TestEnv
    @Before fun setUp() { env = TestEnv() }
    @After fun tearDown() { env.close() }

    private fun newWork(module: Module, title: String, ref: String? = null, stage: String? = null, status: String = "Open") =
        BusinessAction.NewWork(module, title, null, "Ramesh Exports", null, null, status, stage, 500_000, null, null, System.currentTimeMillis() + 86_400_000, 0, mapOf("buyer_po" to "PO-881"), "", ref)

    @Test
    fun configuration_round_trips_and_versions(): Unit = runBlocking {
        assertFalse(env.business.configured())
        val garment = Templates.of("garment_manufacturing").config
        assertEquals(garment, BusinessConfigJson.read(BusinessConfigJson.write(garment)))
        assertTrue(env.business.execute(BusinessAction.SetupBusiness(garment)).ok)
        assertTrue(env.business.configured())
        assertEquals("garment_manufacturing", env.business.config().businessType)
        assertEquals(1, env.business.profileVersion())
        assertFalse("an invalid configuration is refused", env.business.execute(BusinessAction.SetupBusiness(garment.copy(businessType = "x"))).ok)
        assertEquals(1, env.business.profileVersion())
    }

    @Test
    fun production_order_lifecycle_is_saved_with_history(): Unit = runBlocking {
        env.business.execute(BusinessAction.SetupBusiness(Templates.of("garment_manufacturing").config))
        val r = env.business.execute(newWork(Module.PRODUCTION, "500 polo t-shirts", "req-1", "Cutting"))
        assertTrue(r.ok); assertEquals("PO-0001", r.reference)
        // The buyer did not exist: created as a customer.
        assertEquals(1, env.parties.list(PartyKind.CUSTOMER).size)
        val dup = env.business.execute(newWork(Module.PRODUCTION, "500 polo t-shirts", "req-1", "Cutting"))
        assertEquals("DUPLICATE", dup.error)
        val item = env.business.work(Module.PRODUCTION, openOnly = true).single()
        assertEquals("Ramesh Exports", item.customerName)
        val up = env.business.execute(BusinessAction.UpdateWork(item, null, "Stitching", 120_000, null, null, "Lot 1 done"))
        assertTrue(up.ok)
        val after = env.business.work(Module.PRODUCTION, openOnly = true).single()
        assertEquals("Stitching", after.stage); assertEquals(120_000L, after.doneMilli)
        assertTrue(env.business.execute(BusinessAction.UpdateWork(after, "Completed", null, null, null, null, null)).ok)
        assertTrue(env.business.work(Module.PRODUCTION, openOnly = true).isEmpty())
        val kinds = env.db.workDao().events(item.id).map { it.kind }
        assertTrue(kinds.toString(), kinds.containsAll(listOf("CREATED", "STAGE", "PROGRESS", "NOTE", "STATUS")))
        assertFalse("a step that is not in the workflow is refused", env.business.execute(BusinessAction.UpdateWork(after, null, "Teleport", null, null, null, null)).ok)
    }

    @Test
    fun a_module_that_is_switched_off_cannot_be_used(): Unit = runBlocking {
        // The shop default has no production module.
        assertFalse(env.business.execute(newWork(Module.PRODUCTION, "x")).ok)
        assertTrue(env.business.execute(newWork(Module.TASKS, "Stock check", status = "To do")).ok)
    }

    @Test
    fun follow_up_sets_a_reminder_only_after_saving_and_completion_books_the_next(): Unit = runBlocking {
        val due = System.currentTimeMillis() + 3_600_000
        assertTrue(env.business.execute(BusinessAction.NewFollowup(null, "Priya", "98", "CALL", "new collection", due, true, null, "f-1")).ok)
        assertEquals(1, env.scheduled.size); assertEquals(due, env.scheduled[0].third)
        assertEquals("DUPLICATE", env.business.execute(BusinessAction.NewFollowup(null, "Priya", "98", "CALL", "new collection", due, true, null, "f-1")).error)
        assertEquals(1, env.scheduled.size)
        val f = env.business.followups(pendingOnly = true).single()
        assertTrue(env.business.execute(BusinessAction.CompleteFollowup(f, "Will come Saturday", due + 86_400_000, "visit")).ok)
        val all = env.business.followups(pendingOnly = false)
        assertEquals(listOf("PENDING", "DONE"), all.map { it.status })
        assertEquals("Will come Saturday", env.business.contactsFor(null, "Priya").single().summary)
        assertEquals("NOT_FOUND", env.business.execute(BusinessAction.CompleteFollowup(f, "again", null, null)).error)
    }

    @Test
    fun stocky_confirmation_saves_through_the_real_backend(): Unit = runBlocking {
        val engine = AssistantEngine(env.backend, LocalFallbackAIProvider())
        engine.proposePlan(com.aistock.assistant.ai.Intent.UNKNOWN, Plan.Business(BusinessAction.AddStaff("Ravi", "", "tailor"), "Added Ravi."), "Add Ravi?", emptyList())
        val r = engine.confirm()
        assertEquals(ReplyKind.DONE, r.kind)
        assertEquals("Ravi", env.business.staff().single().name)
    }

    @Test
    fun briefing_uses_real_sales(): Unit = runBlocking {
        val clip = env.product("Pink clip", stock = 8, min = 10, sell = 20, buy = 10, unit = "PIECE")
        env.trade.createSale(null, listOf(LineInput(clip.id, 2_000, 2_000))).orThrow()
        val input = env.business.insightsInput(System.currentTimeMillis(), 7)
        assertEquals(4_000L, input.salesNow)
        val text = Insights.forModel(Insights.build(input))
        assertTrue(text, text.contains("Sales in the last 7 days: ₹40 from 1 bills"))
        assertTrue(text, text.contains("Low stock: Pink clip 6 piece"))
        assertNotNull(input.products.single().lastSaleAt)
    }
}
