package com.aistock.assistant

import com.aistock.assistant.ai.llm.LlmMessage
import com.aistock.assistant.ai.llm.OpenAiCompat
import com.aistock.assistant.ai.llm.ToolCall
import com.aistock.assistant.ai.llm.ToolParam
import com.aistock.assistant.ai.llm.ToolSpec
import com.aistock.assistant.backup.BackupException
import com.aistock.assistant.backup.BackupManager
import com.aistock.assistant.data.LineInput
import com.aistock.assistant.data.TestEnv
import com.aistock.assistant.excel.DataExchange
import com.aistock.assistant.excel.ExportKind
import com.aistock.assistant.excel.ImportKind
import com.aistock.assistant.excel.ImportSpec
import com.aistock.assistant.excel.Sheet
import com.aistock.assistant.excel.XlsxReader
import com.aistock.assistant.excel.XlsxWriter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
class BackupAndImportTest {
    private lateinit var env: TestEnv
    private lateinit var backups: BackupManager
    private lateinit var exchange: DataExchange

    @Before
    fun setUp() {
        env = TestEnv()
        backups = BackupManager(env.context, env.db)
        exchange = DataExchange(env.context, env.db, env.inventory, env.parties)
    }

    @After fun tearDown() { env.close() }

    private suspend fun seed(): Pair<String, String> {
        val shirt = env.product("Blue Shirt", stock = 100, min = 10, sell = 450, buy = 300)
        val kumar = env.customer("Kumar Traders")
        env.supplier("ABC Traders")
        env.trade.createSale(kumar, listOf(LineInput(shirt.id, 20_000, 45_000)), paid = 0).orThrowTest()
        env.trade.addExpense("Transport", "", 250_000)
        return shirt.id to kumar
    }

    private fun <T> com.aistock.assistant.data.Op<T>.orThrowTest(): T = valueOrNull() ?: throw AssertionError("operation failed: ${errorOrNull()}")

    @Test
    fun backupThenRestoreBringsEverythingBack(): Unit = runBlocking {
        val (shirtId, kumarId) = seed()
        val bytes = backups.snapshot()
        val info = backups.inspect(ByteArrayInputStream(bytes))
        assertEquals(1, info.products); assertEquals(1, info.customers); assertEquals(1, info.suppliers); assertEquals(1, info.sales)

        // Change things after the backup.
        env.inventory.stockOut(shirtId, 30_000).orThrowTest()
        env.product("Something New", stock = 5)
        env.customer("Someone New")
        assertEquals(50L, env.stock(shirtId))

        backups.restore(ByteArrayInputStream(bytes))
        assertEquals(80L, env.stock(shirtId))
        assertEquals(listOf("Blue Shirt"), env.inventory.activeProducts().map { it.name })
        assertEquals(900_000L, env.customerBalance(kumarId))
        assertEquals(2, env.inventory.movementsFor(shirtId).size)
        assertEquals(250_000L, env.backend.expenses(0, Long.MAX_VALUE).totalPaise)
        // The restored database keeps working.
        env.inventory.stockIn(shirtId, 1_000).orThrowTest()
        assertEquals(81L, env.stock(shirtId))
    }

    @Test
    fun damagedOrForeignFilesAreRefusedAndChangeNothing(): Unit = runBlocking {
        val (shirtId, _) = seed()
        val good = backups.snapshot()

        fun expect(code: String, bytes: ByteArray) = runBlocking {
            try {
                backups.restore(ByteArrayInputStream(bytes)); fail("restore should have been refused")
            } catch (e: BackupException) {
                assertEquals(code, e.code)
            }
            assertEquals("data must be untouched", 80L, env.stock(shirtId))
            assertEquals(1, env.inventory.activeProducts().size)
        }
        expect(BackupException.NOT_A_BACKUP, "this is not a backup".toByteArray())
        expect(BackupException.NOT_A_BACKUP, ByteArray(0))
        // A real spreadsheet is a zip too, but not a backup.
        val xlsx = ByteArrayOutputStream().also { XlsxWriter.write(it, listOf(Sheet("x", listOf(listOf<Any?>("a"))))) }.toByteArray()
        expect(BackupException.NOT_A_BACKUP, xlsx)
        // Tampered data no longer matches the checksum.
        val tampered = rezip(good) { name, body -> if (name == "data.json") body.replace("Blue Shirt", "Hacked Item") else body }
        expect(BackupException.CORRUPT, tampered)
        // A backup from a newer app version is not applied.
        val newer = rezip(good) { name, body -> if (name == "manifest.json") body.replace(Regex("\"dbVersion\":\\d+"), "\"dbVersion\":99") else body }
        expect(BackupException.NEWER_VERSION, newer)
    }

    private fun rezip(bytes: ByteArray, edit: (String, String) -> String): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zo ->
            java.util.zip.ZipInputStream(ByteArrayInputStream(bytes)).use { zi ->
                while (true) {
                    val e = zi.nextEntry ?: break
                    zo.putNextEntry(java.util.zip.ZipEntry(e.name))
                    zo.write(edit(e.name, String(zi.readBytes(), Charsets.UTF_8)).toByteArray(Charsets.UTF_8))
                    zo.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    @Test
    fun backupFilesAreWrittenAndListed(): Unit = runBlocking {
        seed()
        val file = backups.create()
        assertTrue(file.exists() && file.length() > 0)
        assertTrue(backups.list().any { it.name == file.name })
        assertEquals(1, file.inputStream().use { backups.inspect(it) }.products)
        file.delete()
    }

    @Test
    fun excelImportAddsProductsWithOpeningStockAndNeverOverwrites(): Unit = runBlocking {
        val existing = env.product("Blue Shirt", stock = 100, sell = 450)
        val rows = listOf(
            listOf<Any?>("Item Name", "Category", "Unit", "Purchase Price", "Selling Price", "Stock", "Min Stock"),
            listOf<Any?>("Blue Shirt", "Garments", "pcs", 1, 1, 1, 1),
            listOf<Any?>("Cotton Yarn", "Yarn", "kg", 180, 220, 120.5, 25),
            listOf<Any?>("A4 Paper", "Stationery", "boxes", 250, 300, 40, 10),
            listOf<Any?>("Broken Row", "", "pcs", "abc", 10, 1, 0),
        )
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(it, listOf(Sheet("Products", rows))) }.toByteArray()
        val table = exchange.readTable("products.xlsx", ByteArrayInputStream(bytes))
        val preview = exchange.preview(ImportKind.PRODUCTS, table, ImportSpec.autoMap(table[0], ImportKind.PRODUCTS))
        assertEquals(2, preview.ready.size)
        assertEquals(1, preview.skipped.size)
        assertEquals(1, preview.errors.size)
        val result = exchange.import(ImportKind.PRODUCTS, preview)
        assertEquals(2, result.imported)

        val all = env.inventory.activeProducts().associateBy { it.name }
        assertEquals(3, all.size)
        assertEquals("existing product is untouched", 100_000L, all.getValue("Blue Shirt").currentStock)
        assertEquals(45_000L, env.db.productDao().byId(existing.id)!!.sellingPrice)
        val yarn = all.getValue("Cotton Yarn")
        assertEquals("KG", yarn.unit); assertEquals(120_500L, yarn.currentStock); assertEquals(18_000L, yarn.purchasePrice); assertEquals(22_000L, yarn.sellingPrice)
        assertEquals("OPENING", env.inventory.movementsFor(yarn.id).single().type)
        assertEquals("BOX", all.getValue("A4 Paper").unit)
        assertEquals("categories of imported rows are created", 2, env.inventory.activeCategories().size)

        // CSV customers
        val csv = "Name,Phone,Opening Balance\nKumar Traders,9876543210,1500\nRavi,,\n"
        val ctable = exchange.readTable("customers.csv", ByteArrayInputStream(csv.toByteArray()))
        val cprev = exchange.preview(ImportKind.CUSTOMERS, ctable, ImportSpec.autoMap(ctable[0], ImportKind.CUSTOMERS))
        assertEquals(2, exchange.import(ImportKind.CUSTOMERS, cprev).imported)
        assertEquals(150_000L, env.backend.parties(com.aistock.assistant.ai.PartyKind.CUSTOMER).first { it.name == "Kumar Traders" }.balancePaise)

        // Opening stock for an existing product
        val stock = "Product,Quantity\nA4 Paper,55\nNo Such Thing,5\n"
        val stable = exchange.readTable("stock.csv", ByteArrayInputStream(stock.toByteArray()))
        val sprev = exchange.preview(ImportKind.OPENING_STOCK, stable, ImportSpec.autoMap(stable[0], ImportKind.OPENING_STOCK))
        val sres = exchange.import(ImportKind.OPENING_STOCK, sprev)
        assertEquals(1, sres.imported); assertEquals(1, sres.errors.size)
        assertEquals(55_000L, env.db.productDao().byId(all.getValue("A4 Paper").id)!!.currentStock)
    }

    @Test
    fun exportProducesARealWorkbookThatCanBeImportedAgain(): Unit = runBlocking {
        seed()
        val sheets = exchange.exportSheets(ExportKind.EVERYTHING)
        assertEquals(8, sheets.size)
        val bytes = ByteArrayOutputStream().also { exchange.writeXlsx(it, sheets) }.toByteArray()
        val table = XlsxReader.read(ByteArrayInputStream(bytes))
        assertEquals("Name", table[0][0])
        assertEquals("Blue Shirt", table[1][0])
        assertEquals("80", table[1][8])
        val map = ImportSpec.autoMap(table[0], ImportKind.PRODUCTS)
        assertEquals(setOf("name", "code", "category", "unit", "barcode", "purchasePrice", "sellingPrice", "minStock", "openingStock", "description"), map.keys)
        val sales = exchange.exportSheets(ExportKind.SALES).single()
        assertEquals(2, sales.rows.size)
    }

    @Test
    fun modelRequestsAndRepliesUseTheToolCallingFormat() {
        val tools = listOf(ToolSpec("find_products", "Look up products", listOf(ToolParam("query", "string", "name", true))))
        val req = org.json.JSONObject(OpenAiCompat.request("gemini-test", listOf(
            LlmMessage.System("You are Stocky"), LlmMessage.User("blue cotton stock evlo?"),
            LlmMessage.Assistant("", listOf(ToolCall("c1", "find_products", mapOf("query" to "blue cotton")))),
            LlmMessage.ToolResult("c1", "find_products", "Blue Cotton: 486 meter"),
        ), tools))
        assertEquals("gemini-test", req.getString("model"))
        assertEquals(4, req.getJSONArray("messages").length())
        assertEquals("tool", req.getJSONArray("messages").getJSONObject(3).getString("role"))
        assertEquals("find_products", req.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals("query", req.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getJSONObject("parameters").getJSONArray("required").getString(0))

        val call = OpenAiCompat.parse("""{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"","type":"function","function":{"name":"propose_stock_in","arguments":"{\"product\":\"Blue Cotton\",\"quantity\":50,\"unit\":\"METER\"}"}}]}}]}""")
        assertEquals("", call.text)
        assertEquals("propose_stock_in", call.toolCalls.single().name)
        assertEquals("50", call.toolCalls.single().args["quantity"])
        assertTrue(call.toolCalls.single().id.isNotBlank())
        val text = OpenAiCompat.parse("""{"choices":[{"message":{"role":"assistant","content":"Blue Cotton 486 metres stock-la irukku."}}]}""")
        assertEquals("Blue Cotton 486 metres stock-la irukku.", text.text)
        assertTrue(text.toolCalls.isEmpty())
        assertEquals("API key not valid", OpenAiCompat.errorMessage("""[{"error":{"code":400,"message":"API key not valid"}}]"""))
    }

    @Test
    fun version1DatabaseUpgradesWithoutLosingData(): Unit = runBlocking {
        // Build a real version-1 database from the saved schema, put data in it, then open it with the current app.
        val schema = org.json.JSONObject(java.io.File("schemas/com.aistock.assistant.database.AppDatabase/1.json").readText()).getJSONObject("database")
        val file = env.context.getDatabasePath("upgrade-test.db")
        file.parentFile?.mkdirs(); file.delete()
        val raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            raw.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
            val idx = e.optJSONArray("indices") ?: org.json.JSONArray()
            for (j in 0 until idx.length()) raw.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) raw.execSQL(setup.getString(i))
        raw.execSQL("INSERT INTO products (id, productCode, name, description, categoryId, unit, barcode, purchasePrice, sellingPrice, minimumStock, currentStock, active, createdAt, updatedAt) VALUES ('p1','P0001','Blue Cotton','',NULL,'METER','',10000,15000,0,486000,1,1,1)")
        raw.version = 1
        raw.close()

        val db = androidx.room.Room.databaseBuilder(env.context, com.aistock.assistant.database.AppDatabase::class.java, "upgrade-test.db")
            .addMigrations(*com.aistock.assistant.database.AppDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try {
            assertEquals(486_000L, db.productDao().byId("p1")!!.currentStock)
            db.stockyDao().saveAlias(com.aistock.assistant.database.BusinessAliasEntity("a1", "blue cotton", "blue cotton", "Blue Cotton", 1))
            assertEquals(1, db.stockyDao().aliases().size)
            assertEquals(0, db.stockyDao().sheets().size)
            // Version 3 tables exist and work after the upgrade.
            assertEquals(null, db.workDao().profile())
            db.workDao().insertStaff(com.aistock.assistant.business.StaffEntity("s1", "Ravi", createdAt = 1))
            db.workDao().insertWork(com.aistock.assistant.business.WorkItemEntity("w1", "production", "PO-0001", "Polo", status = "Open", createdAt = 1, updatedAt = 1, staffId = "s1"))
            assertEquals("Ravi", db.workDao().rows("production", 10).single().staffName)
        } finally {
            db.close(); file.delete()
        }
    }
}
