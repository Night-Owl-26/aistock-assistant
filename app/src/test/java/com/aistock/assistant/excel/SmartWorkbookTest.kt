package com.aistock.assistant.excel

import androidx.room.Room
import com.aistock.assistant.ai.AssistantEngine
import com.aistock.assistant.ai.LocalFallbackAIProvider
import com.aistock.assistant.ai.ReplyKind
import com.aistock.assistant.ai.llm.LlmClient
import com.aistock.assistant.ai.llm.LlmMessage
import com.aistock.assistant.ai.llm.LlmReply
import com.aistock.assistant.ai.llm.ToolCall
import com.aistock.assistant.ai.llm.ToolSpec
import com.aistock.assistant.conversation.Stocky
import com.aistock.assistant.data.TestEnv
import com.aistock.assistant.database.AppDatabase
import com.aistock.assistant.utils.NameMatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal

/**
 * The whole path the owner asked for, minus the microphone: a workbook with unusual column titles is understood by
 * the (scripted) AI, imported with sizes, a spoken sale is turned into a confirmed sale that lowers the stock and
 * raises revenue, and the owner's own Excel file is then updated with the new stock and revenue.
 */
@RunWith(RobolectricTestRunner::class)
class SmartWorkbookTest {
    private lateinit var env: TestEnv

    @Before fun setUp() { env = TestEnv() }
    @After fun tearDown() { env.close() }

    private class FakeAi(private val reply: () -> String?) : ColumnAi {
        val prompts = ArrayList<String>()
        override suspend fun ask(prompt: String): String? { prompts.add(prompt); return reply() }
    }

    private fun workbook(): ByteArray {
        val out = ByteArrayOutputStream()
        XlsxWriter.write(out, listOf(Sheet("Godown", listOf(
            listOf("Itm", "Sz", "Bal Qty", "Rt"),
            listOf("Shirt", "XL", BigDecimal(12), BigDecimal(450)),
            listOf("Shirt", "L", BigDecimal(8), BigDecimal(430)),
            listOf("Pant", "32", BigDecimal(5), BigDecimal(799)),
        ))))
        return out.toByteArray()
    }

    private val aiReply = """{"sheets":[{"sheet":"Godown","purpose":"PRODUCTS","confidence":92,"columns":{"name":"Itm","size":"Sz","stock":"Bal Qty","sellingPrice":"Rt"}}]}"""

    @Test
    fun aiMappedImportThenSpokenSaleThenExcelUpdate(): Unit = runBlocking {
        val ai = FakeAi { aiReply }
        val exchange = DataExchange(env.context, env.db, env.inventory, env.parties, ai)
        val bytes = workbook()

        // 1. The rules alone cannot read these titles.
        val found = exchange.analyzeWorkbook("shop.xlsx", ByteArrayInputStream(bytes))
        assertEquals(null, ImportSpec.autoMap(found.single().headers, ImportKind.PRODUCTS)["name"])

        // 2. The AI reads titles and masked examples, the app checks the answer, the owner sees the plan.
        val (smart, status) = exchange.smartColumns(found)
        assertEquals(ColumnAiStatus.USED, status)
        assertTrue(ai.prompts.single().contains("\"Bal Qty\""))
        val plan = exchange.workbookPlan(smart).single()
        assertEquals(ImportKind.PRODUCTS, plan.kind)
        assertEquals(mapOf("name" to 0, "size" to 1, "openingStock" to 2, "sellingPrice" to 3), plan.choice.mapping)
        assertEquals("ai", plan.choice.source["size"])
        assertEquals(listOf("Shirt XL", "Shirt L", "Pant 32"), plan.preview.ready.map { it.values["name"] })

        // 3. Import: each size is its own product with its own stock and price.
        val result = exchange.importWorkbook(smart)
        assertEquals(3, result.imported[ImportKind.PRODUCTS])
        val shirtXl = env.db.productDao().active().single { it.name == "Shirt XL" }
        assertEquals(12_000L, shirtXl.currentStock)
        assertEquals(45_000L, shirtXl.sellingPrice)
        val candidate = result.linkable.single()
        assertEquals(LinkCandidate("Godown", listOf("Itm", "Sz", "Bal Qty", "Rt"), 0, 1, 2, 3), candidate)

        // 4. "Raju-ku 2 XL shirt kuduthen, cash" -> the model extracts product, size and quantity; nothing is saved before yes.
        var step = 0
        val llm = object : LlmClient {
            override val name = "scripted"
            override suspend fun chat(messages: List<LlmMessage>, tools: List<ToolSpec>): LlmReply = when (step++) {
                0 -> {
                    assertTrue(tools.single { it.name == "propose_sale" }.params.any { it.name == "size" })
                    LlmReply("", listOf(ToolCall("c1", "propose_sale", mapOf("product" to "shirt", "size" to "XL", "quantity" to "2", "paid" to "true"))))
                }
                else -> LlmReply("2 XL shirt, 900 rupees. Save pannatuma?")
            }
        }
        val stocky = Stocky(env.backend, AssistantEngine(env.backend, LocalFallbackAIProvider()), { llm })
        val ask = stocky.handle("2 XL shirt sold, cash")
        assertEquals(ask.text, ReplyKind.CONFIRM, ask.kind)
        assertEquals(12_000L, env.db.productDao().byId(shirtXl.id)!!.currentStock)
        val done = stocky.handle("Yes")
        assertEquals(done.text, ReplyKind.DONE, done.kind)

        // 5. Business logic: stock of that size down by 2, revenue up by 2 x 450.
        assertEquals(10_000L, env.db.productDao().byId(shirtXl.id)!!.currentStock)
        assertEquals(8_000L, env.db.productDao().active().single { it.name == "Shirt L" }.currentStock)
        val sale = env.db.tradeDao().salesBetween(0, Long.MAX_VALUE).single()
        assertEquals(90_000L, sale.sale.total)

        // 6. The owner's own file is updated: the stock cell of that row and an "AIStock Sales" sheet with the revenue.
        val link = ExcelLinkEntity(
            "L", "content://shop.xlsx", "shop.xlsx", candidate.sheet, "Itm", 0, "Sz", 1, "Bal Qty", 2, "Rt", 3,
            enabled = true, addNewRows = true, salesSheet = true, lastSyncAt = null, lastResult = "", createdAt = System.currentTimeMillis(),
        )
        val products = env.db.productDao().active().map { SyncProduct(it.name, it.currentStock, it.sellingPrice, it.createdAt, it.productCode) }
        val sync = ExcelSync(env.context, env.db)
        val update = ExcelSyncPlan.update(bytes, link, products, sync.salesSheet())
        assertEquals("UPDATED", update.outcome.code)
        assertEquals(1, update.outcome.changed)
        val after = XlsxReader.readAll(ByteArrayInputStream(update.bytes!!))
        assertEquals(listOf("Shirt", "XL", "10", "450"), after[0].rows[1])
        assertEquals(listOf("Shirt", "L", "8", "430"), after[0].rows[2])
        val salesRows = after.single { it.name == ExcelSync.SALES_SHEET }.rows
        assertEquals(listOf("Total revenue", "900"), salesRows.first { it[0] == "Total revenue" }.take(2))
        assertTrue(salesRows.any { r -> r.contains("900") && r.any { it.contains("Shirt") } })
    }

    @Test
    fun theConfirmedColumnsAreReusedWithoutTheAiAndFailuresFallBackToTheRules(): Unit = runBlocking {
        val first = DataExchange(env.context, env.db, env.inventory, env.parties, FakeAi { aiReply })
        val (smart, _) = first.smartColumns(first.analyzeWorkbook("a.xlsx", ByteArrayInputStream(workbook())))
        first.importWorkbook(smart)

        // Same layout next month, AI unreachable: the saved mapping is used.
        val broken = FakeAi { throw java.io.IOException("offline") }
        val second = DataExchange(env.context, env.db, env.inventory, env.parties, broken)
        val (again, status) = second.smartColumns(second.analyzeWorkbook("b.xlsx", ByteArrayInputStream(workbook())))
        assertEquals(ColumnAiStatus.SAVED, status)
        assertTrue(broken.prompts.isEmpty())
        assertEquals("saved", HeaderMapper.choose(again.single(), ImportKind.PRODUCTS).source["size"])

        // A new layout with a model that answers nonsense: nothing from it is used.
        val out = ByteArrayOutputStream()
        XlsxWriter.write(out, listOf(Sheet("Items", listOf(listOf("Product", "Selling Price", "Stock"), listOf("Cap", BigDecimal(150), BigDecimal(3))))))
        val junk = DataExchange(env.context, env.db, env.inventory, env.parties, FakeAi { "Sorry, I can't help" })
        val (rules, st) = junk.smartColumns(junk.analyzeWorkbook("c.xlsx", ByteArrayInputStream(out.toByteArray())))
        assertEquals(ColumnAiStatus.FAILED, st)
        assertEquals(mapOf("name" to 0, "sellingPrice" to 1, "openingStock" to 2), junk.workbookPlan(rules).single().choice.mapping)

        // No AI set up at all.
        val none = DataExchange(env.context, env.db, env.inventory, env.parties)
        assertEquals(ColumnAiStatus.OFF, none.smartColumns(none.analyzeWorkbook("c.xlsx", ByteArrayInputStream(out.toByteArray()))).second)
    }

    @Test
    fun voiceSizesFindTheRightProduct(): Unit = runBlocking {
        env.product("Shirt M", stock = 5, sell = 400)
        env.product("Shirt XXL", stock = 3, sell = 500)
        var step = 0
        val llm = object : LlmClient {
            override val name = "scripted"
            override suspend fun chat(messages: List<LlmMessage>, tools: List<ToolSpec>): LlmReply = when (step++) {
                0 -> LlmReply("", listOf(ToolCall("c1", "propose_stock_in", mapOf("product" to "shirt", "size" to "double xl", "quantity" to "4"))))
                else -> LlmReply("4 added to Shirt XXL?")
            }
        }
        val stocky = Stocky(env.backend, AssistantEngine(env.backend, LocalFallbackAIProvider()), { llm })
        assertEquals(ReplyKind.CONFIRM, stocky.handle("double XL shirt 4 vandhuchu").kind)
        assertEquals(ReplyKind.DONE, stocky.handle("Yes").kind)
        assertEquals(7_000L, env.db.productDao().active().single { NameMatcher.key(it.name) == "shirt xxl" }.currentStock)
        assertEquals(5_000L, env.db.productDao().active().single { it.name == "Shirt M" }.currentStock)
    }

    @Test
    fun version3DatabasesUpgradeWithoutLosingData(): Unit = runBlocking {
        val file = File(env.context.cacheDir, "migrate-test.db").apply { delete() }
        val v4 = Room.databaseBuilder(env.context, AppDatabase::class.java, file.absolutePath).allowMainThreadQueries().build()
        v4.categoryDao().insert(com.aistock.assistant.database.CategoryEntity("c1", "Shirts", "", true, 0, 0))
        // Turn it back into a version 3 database: drop the new tables and lower the version.
        v4.openHelper.writableDatabase.execSQL("DROP TABLE column_maps")
        v4.openHelper.writableDatabase.execSQL("DROP TABLE excel_links")
        v4.openHelper.writableDatabase.version = 3
        v4.close()
        val upgraded = Room.databaseBuilder(env.context, AppDatabase::class.java, file.absolutePath).addMigrations(*AppDatabase.MIGRATIONS).allowMainThreadQueries().build()
        // Room checks every table against the entities while opening; a wrong migration throws here.
        assertNotNull(upgraded.categoryDao().active().singleOrNull { it.name == "Shirts" })
        upgraded.excelDao().saveColumnMap(ColumnMapEntity("sig", "PRODUCTS", "{}", 1))
        assertEquals("PRODUCTS", upgraded.excelDao().columnMap("sig")!!.purpose)
        assertTrue(upgraded.excelDao().links().isEmpty())
        upgraded.close()
        file.delete()
    }
}
