package com.aistock.assistant.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InventoryRepositoryTest {
    private lateinit var env: TestEnv

    @Before fun setUp() { env = TestEnv() }
    @After fun tearDown() { env.close() }

    @Test
    fun productCreationRecordsOpeningStock(): Unit = runBlocking {
        val p = env.product("Blue Shirt", stock = 100, min = 10, sell = 450, buy = 300)
        assertEquals(100_000L, p.currentStock)
        assertEquals(45_000L, p.sellingPrice)
        assertTrue(p.productCode.isNotBlank())
        val moves = env.inventory.movementsFor(p.id)
        assertEquals(1, moves.size)
        assertEquals(TxnType.OPENING, moves[0].type)
        assertEquals(0L, moves[0].beforeQuantity)
        assertEquals(100_000L, moves[0].afterQuantity)
    }

    @Test
    fun productWithoutOpeningStockHasNoMovement(): Unit = runBlocking {
        val p = env.product("Pen")
        assertEquals(0L, p.currentStock)
        assertTrue(env.inventory.movementsFor(p.id).isEmpty())
    }

    @Test
    fun duplicateAndInvalidProductsAreRejected(): Unit = runBlocking {
        env.product("Blue Shirt")
        assertEquals(ErrCode.DUPLICATE, env.inventory.createProduct(ProductInput("blue shirts")).errorOrNull())
        assertEquals(ErrCode.INVALID, env.inventory.createProduct(ProductInput("  ")).errorOrNull())
        assertEquals(ErrCode.INVALID, env.inventory.createProduct(ProductInput("X", sellingPrice = -1)).errorOrNull())
        assertEquals(1, env.inventory.activeProducts().size)
    }

    @Test
    fun stockInCreatesTransaction(): Unit = runBlocking {
        val p = env.product("A4 Paper", stock = 10, unit = "BOX")
        val t = env.inventory.stockIn(p.id, 50_000, TxnType.ADJUSTMENT_IN, 25_000, "restock").orThrow()
        assertEquals(60L, env.stock(p.id))
        assertEquals(10_000L, t.beforeQuantity)
        assertEquals(60_000L, t.afterQuantity)
        assertEquals(50_000L, t.quantity)
        assertEquals("BOX", t.unit)
        assertEquals(25_000L, t.unitCost)
        assertEquals(2, env.inventory.movementsFor(p.id).size)
    }

    @Test
    fun stockOutCreatesTransactionAndProtectsAgainstNegativeStock(): Unit = runBlocking {
        val p = env.product("Blue Shirt", stock = 30)
        val t = env.inventory.stockOut(p.id, 25_000, TxnType.DAMAGE, "damaged").orThrow()
        assertEquals(TxnType.DAMAGE, t.type)
        assertEquals(5L, env.stock(p.id))
        val r = env.inventory.stockOut(p.id, 6_000, TxnType.DAMAGE)
        assertEquals(ErrCode.INSUFFICIENT_STOCK, r.errorOrNull())
        assertEquals("stock must be untouched after a refused movement", 5L, env.stock(p.id))
        assertEquals(2, env.inventory.movementsFor(p.id).size)
    }

    @Test
    fun negativeStockOnlyWhenTheRuleAllowsIt(): Unit = runBlocking {
        val p = env.product("Blue Shirt", stock = 5)
        env.allowNegative = true
        env.inventory.stockOut(p.id, 8_000).orThrow()
        assertEquals(-3L, env.stock(p.id))
    }

    @Test
    fun zeroAndNegativeQuantitiesAreInvalid(): Unit = runBlocking {
        val p = env.product("Blue Shirt", stock = 5)
        assertEquals(ErrCode.INVALID, env.inventory.stockIn(p.id, 0).errorOrNull())
        assertEquals(ErrCode.INVALID, env.inventory.stockOut(p.id, -5).errorOrNull())
        assertEquals(ErrCode.NOT_FOUND, env.inventory.stockIn("nope", 1_000).errorOrNull())
    }

    @Test
    fun stockAdjustmentRecordsTheDifference(): Unit = runBlocking {
        val p = env.product("Blue Shirt", stock = 80)
        val t = env.inventory.adjustTo(p.id, 72_000).orThrow()
        assertEquals(TxnType.ADJUSTMENT_OUT, t.type)
        assertEquals(8_000L, t.quantity)
        assertEquals(72L, env.stock(p.id))
        val up = env.inventory.adjustTo(p.id, 75_000).orThrow()
        assertEquals(TxnType.ADJUSTMENT_IN, up.type)
        assertEquals(ErrCode.INVALID, env.inventory.adjustTo(p.id, 75_000).errorOrNull())
    }

    @Test
    fun lowStockDetection(): Unit = runBlocking {
        val shirt = env.product("Blue Shirt", stock = 20, min = 10)
        env.product("A4 Paper", stock = 50, min = 10)
        fun low() = runBlocking { env.backend.products().filter { it.isLow }.map { it.name } }
        assertTrue(low().isEmpty())
        env.inventory.stockOut(shirt.id, 10_000).orThrow()
        assertEquals("stock equal to the minimum counts as low", listOf("Blue Shirt"), low())
        env.inventory.stockIn(shirt.id, 1_000).orThrow()
        assertTrue(low().isEmpty())
    }

    @Test
    fun undoAddsAReversingMovementAndKeepsHistory(): Unit = runBlocking {
        val p = env.product("A4 Paper", stock = 10)
        val t = env.inventory.stockIn(p.id, 50_000).orThrow()
        val undo = env.inventory.undoMovement(t.id).orThrow()
        assertEquals(10L, env.stock(p.id))
        assertEquals("UNDO", undo.referenceType)
        assertEquals(t.id, undo.referenceId)
        assertEquals(3, env.inventory.movementsFor(p.id).size)
        assertNotNull(env.db.inventoryDao().byId(t.id))
        assertEquals(ErrCode.ALREADY_DONE, env.inventory.undoMovement(t.id).errorOrNull())
        assertEquals(ErrCode.INVALID, env.inventory.undoMovement(undo.id).errorOrNull())
    }

    @Test
    fun productsWithHistoryAreHiddenNotDeleted(): Unit = runBlocking {
        val withHistory = env.product("Blue Shirt", stock = 5)
        val fresh = env.product("Pen")
        assertEquals(false, env.inventory.removeProduct(withHistory.id).orThrow())
        assertEquals(true, env.inventory.removeProduct(fresh.id).orThrow())
        assertFalse(env.db.productDao().byId(withHistory.id)!!.active)
        assertNull(env.db.productDao().byId(fresh.id))
        assertEquals(1, env.inventory.movementsFor(withHistory.id).size)
    }

    @Test
    fun updateDoesNotTouchStock(): Unit = runBlocking {
        val p = env.product("Blue Shirt", stock = 40, sell = 450)
        val u = env.inventory.updateProduct(p.id, ProductInput("Blue Shirt XL", sellingPrice = 55_000, openingStock = 999_000)).orThrow()
        assertEquals("Blue Shirt XL", u.name)
        assertEquals(55_000L, u.sellingPrice)
        assertEquals(40_000L, u.currentStock)
    }

    @Test
    fun categories(): Unit = runBlocking {
        val c = env.inventory.saveCategory(null, "Garments", "").orThrow()
        assertEquals(ErrCode.DUPLICATE, env.inventory.saveCategory(null, "garments", "").errorOrNull())
        env.inventory.createProduct(ProductInput("Blue Shirt", categoryId = c.id)).orThrow()
        env.inventory.saveCategory(c.id, "Clothing", "all clothes").orThrow()
        assertEquals("Clothing", env.inventory.activeCategories().single().name)
    }
}
