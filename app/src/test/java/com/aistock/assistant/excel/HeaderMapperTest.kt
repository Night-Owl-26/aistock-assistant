package com.aistock.assistant.excel

import com.aistock.assistant.utils.MiniJson
import com.aistock.assistant.utils.NameMatcher
import com.aistock.assistant.utils.Match
import com.aistock.assistant.utils.Sizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderMapperTest {
    private fun profile(name: String, rows: List<List<String>>, purpose: SheetPurpose = SheetPurpose.OTHER, confidence: Int = 20) =
        SheetProfile(name, false, purpose, confidence, emptyList(), rows)

    private val items = profile(
        "Godown", listOf(
            listOf("Itm", "Sz", "Bal Qty", "Rt/pc", "Mob", ""),
            listOf("Shirt", "XL", "12", "450", "9876543210", "x"),
            listOf("Shirt", "L", "8", "430", "9876543211", "y"),
            listOf("Pant", "32", "5", "799", "", ""),
        ),
    )

    @Test
    fun readsAValidReplyInsideFencesAndChecksEveryColumn() {
        val reply = """
            Sure! Here it is:
            ```json
            {"sheets":[{"sheet":"Godown","purpose":"STOCK","confidence":88,"columns":{
              "name":"Itm","size":"Sz","stock":"Bal Qty","sellingPrice":"Rt/pc",
              "colour":"Itm","phone":"Not there","purchasePrice":"Itm","notes":"Column 6"}}]}
            ```
        """.trimIndent()
        val g = HeaderMapper.parse(reply, listOf(items)).getValue("Godown")
        assertEquals(SheetPurpose.STOCK, g.purpose)
        assertEquals(88, g.confidence)
        // unknown field, missing header and a price on a text column are all dropped; a blank title can be named "Column 6"
        assertEquals(mapOf("name" to 0, "size" to 1, "stock" to 2, "sellingPrice" to 3, "notes" to 5), g.fields)
    }

    @Test
    fun oneColumnIsNeverUsedTwiceAndBadJsonGivesNothing() {
        val reply = """{"sheets":[{"sheet":"godown","purpose":"PRODUCTS","columns":{"name":"Itm","description":"Itm","stock":"bal qty"}}]}"""
        val g = HeaderMapper.parse(reply, listOf(items)).getValue("Godown")
        assertEquals(mapOf("name" to 0, "stock" to 2), g.fields)
        assertTrue(HeaderMapper.parse("I could not do that", listOf(items)).isEmpty())
        assertTrue(HeaderMapper.parse("""{"sheets":[{"sheet":"Other","columns":{"name":"Itm"}}]}""", listOf(items)).isEmpty())
    }

    @Test
    fun promptHidesPhoneNumbersAndEmails() {
        val p = HeaderMapper.prompt(listOf(items))
        assertTrue(p.contains("\"Itm\""))
        assertTrue(p.contains("\"XL\""))
        assertFalse(p.contains("9876543210"))
        assertTrue(p.contains("9999999999"))
        assertEquals("name@example.com", HeaderMapper.mask("kumar@shop.in"))
        assertEquals("450", HeaderMapper.mask("450"))
    }

    @Test
    fun aiChoicesComeFirstAndTheRulesFillTheRest() {
        val p = profile("Items", listOf(listOf("Itm", "Sz", "Selling Price", "Unit"), listOf("Shirt", "XL", "450", "pcs")))
        val withAi = HeaderMapper.apply(listOf(p), mapOf("Items" to SheetGuess(SheetPurpose.PRODUCTS, 90, mapOf("name" to 0, "size" to 1)))).single()
        assertEquals(SheetPurpose.PRODUCTS, withAi.purpose)
        assertEquals("ai", withAi.purposeSource)
        val c = HeaderMapper.choose(withAi, ImportKind.PRODUCTS)
        assertEquals(mapOf("name" to 0, "size" to 1, "sellingPrice" to 2, "unit" to 3), c.mapping)
        assertEquals("ai", c.source["name"])
        assertEquals("rules", c.source["sellingPrice"])

        // The owner said "not used": the rules must not bring it back.
        val off = withAi.copy(fields = withAi.fields + ("sellingPrice" to -1), fieldSource = withAi.fieldSource + ("sellingPrice" to "you"))
        assertNull(HeaderMapper.choose(off, ImportKind.PRODUCTS).mapping["sellingPrice"])
    }

    @Test
    fun theAiPurposeIsOnlyTakenWhenItIsSurerThanTheRules() {
        val sure = profile("Customers", listOf(listOf("Name", "Phone"), listOf("Kumar", "1")), SheetPurpose.CUSTOMERS, 90)
        val out = HeaderMapper.apply(listOf(sure), mapOf("Customers" to SheetGuess(SheetPurpose.SUPPLIERS, 60, emptyMap()))).single()
        assertEquals(SheetPurpose.CUSTOMERS, out.purpose)
        val chosenByOwner = sure.copy(purposeSource = "you")
        assertEquals(SheetPurpose.CUSTOMERS, HeaderMapper.apply(listOf(chosenByOwner), mapOf("Customers" to SheetGuess(SheetPurpose.SUPPLIERS, 99, emptyMap()))).single().purpose)
    }

    @Test
    fun stockAndCostMeanTheRightThingForEachKindOfImport() {
        val p = profile("Stock", listOf(listOf("Code", "Qty", "Cost"), listOf("P1", "5", "10")))
            .copy(fields = mapOf("code" to 0, "stock" to 1, "purchasePrice" to 2), fieldSource = mapOf("code" to "ai", "stock" to "ai", "purchasePrice" to "ai"))
        assertEquals(mapOf("name" to 0, "quantity" to 1, "unitCost" to 2), HeaderMapper.choose(p, ImportKind.OPENING_STOCK).mapping)
        assertEquals("openingStock", HeaderMapper.kindKey(ImportKind.PRODUCTS, "stock"))
        assertNull(HeaderMapper.kindKey(ImportKind.CUSTOMERS, "stock"))
        assertEquals("stock", HeaderMapper.sharedField(ImportKind.OPENING_STOCK, "quantity"))
    }

    @Test
    fun aSizeColumnMakesEachSizeItsOwnProduct() {
        val table = listOf(listOf("Product", "Size", "Stock"), listOf("Shirt", "XL", "12"), listOf("Shirt", "L", "8"), listOf("Shirt XL", "XL", "1"))
        val map = ImportSpec.autoMap(table[0], ImportKind.PRODUCTS)
        assertEquals(1, map["size"])
        val pre = ImportSpec.preview(ImportKind.PRODUCTS, table, map, emptyList())
        assertEquals(listOf("Shirt XL", "Shirt L"), pre.ready.map { it.values["name"] })
        // "Shirt XL" with size XL is the same product as row 2, not "Shirt XL XL".
        assertEquals(1, pre.skipped.size)
        assertEquals("Shirt XL", ImportSpec.nameWithSize("Shirt XL", "xl"))
        assertEquals("Pant 32", ImportSpec.nameWithSize("Pant", "32"))
        assertEquals("Pant", ImportSpec.nameWithSize("Pant", " "))
    }

    @Test
    fun spokenSizesAndWordOrder() {
        assertEquals("M", Sizes.short("medium"))
        assertEquals("XXL", Sizes.short("double XL"))
        assertEquals("XL", Sizes.short("xl size"))
        assertEquals("32", Sizes.short("32"))
        assertEquals("500ml", Sizes.short("500ml"))
        val names = listOf("Shirt XL", "Shirt L", "Pant 32")
        assertEquals(Match.Exact("Shirt XL"), NameMatcher.match("XL shirt", names) { it })
        assertTrue(NameMatcher.match("shirt", names) { it } is Match.Multiple)
    }

    @Test
    fun miniJsonReadsWhatModelsSend() {
        val v = MiniJson.parse("""{"a":[1,2.5,-3e2],"b":"x\"yé\n","c":true,"d":null,"e":{}}""") as Map<*, *>
        assertEquals(listOf(1.0, 2.5, -300.0), v["a"])
        assertEquals("x\"yé\n", v["b"])
        assertEquals(true, v["c"])
        assertTrue(v.containsKey("d"))
        assertNull(MiniJson.firstObject("no json here {broken"))
        assertEquals(mapOf("k" to "v"), MiniJson.firstObject("text {not json} then {\"k\":\"v\"} end"))
    }
}
