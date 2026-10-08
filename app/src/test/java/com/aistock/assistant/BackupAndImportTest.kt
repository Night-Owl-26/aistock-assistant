package com.aistock.assistant

import com.aistock.assistant.ai.AICommandJson
import com.aistock.assistant.ai.Intent
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
        val newer = rezip(good) { name, body -> if (name == "manifest.json") body.replace("\"dbVersion\":1", "\"dbVersion\":99") else body }
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
        assertEquals(3, env.inventory.activeCategories().size)

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
    fun cloudReplyIsParsedStrictly() {
        val ok = AICommandJson.parse("""Sure! {"intent":"STOCK_IN","confidence":0.96,"requiresConfirmation":false,"entities":{"productName":"A4 Paper","quantity":50,"unit":"BOX","supplierName":"Kumar Traders","unitPrice":250},"missingFields":[],"response":"ok"}""")
        assertEquals(Intent.STOCK_IN, ok.intent)
        assertEquals(50_000L, ok.entities.quantityMilli)
        assertEquals("BOX", ok.entities.unit)
        assertEquals(25_000L, ok.entities.unitPricePaise)
        assertTrue("writes always need confirmation, whatever the service says", ok.requiresConfirmation)
        assertEquals("cloud", ok.source)

        for (bad in listOf(
            "no json here",
            """{"intent":"DROP_TABLES","confidence":0.9}""",
            """{"intent":"STOCK_IN","confidence":7}""",
            """{"intent":"STOCK_IN","confidence":0.9,"entities":{"quantity":-5}}""",
            """{"intent":"STOCK_IN","confidence":0.9,"entities":{"quantity":"lots"}}""",
            """{"intent":"STOCK_IN","confidence":0.9,"entities":{"unit":"PARSECS"}}""",
            """{"intent":"STOCK_IN","confidence":0.9,"entities":{"productName":{"sql":"DROP"}}}""",
            """{"intent":"STOCK_IN","confidence":0.9,"entities":{"paid":"yes"}}""",
            """{"intent":"STOCK_IN","confidence":0.9,"entities":"oops"}""".replace("\"oops\"", "{\"dateRange\":\"SOMEDAY\"}"),
        )) {
            try {
                AICommandJson.parse(bad); fail("should reject: $bad")
            } catch (e: Exception) {
                // expected
            }
        }
    }
}
