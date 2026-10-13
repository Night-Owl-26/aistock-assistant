package com.aistock.assistant.excel

/** What a sheet of the owner's workbook holds. */
enum class SheetPurpose { PRODUCTS, STOCK, CUSTOMERS, SUPPLIERS, SALES, PURCHASES, EXPENSES, PAYMENTS, OTHER }

data class ColumnProfile(val name: String, val type: String, val example: String)

/**
 * One sheet as Stocky understands it.
 * @param confidence 0..100: how sure the guess about [purpose] is
 * @param table the header row followed by the data rows (anything above the header is dropped)
 */
data class SheetProfile(
    val name: String, val hidden: Boolean, val purpose: SheetPurpose, val confidence: Int,
    val columns: List<ColumnProfile>, val table: List<List<String>>, val links: List<String> = emptyList(),
) {
    val rowCount: Int get() = (table.size - 1).coerceAtLeast(0)
    val headers: List<String> get() = table.firstOrNull().orEmpty()
}

/**
 * Looks at any workbook, whatever its sheet and column names, and works out what each sheet is for.
 * Nothing is assumed about fixed names: sheet titles and column headers are scored against business vocabulary
 * (English and Tamil), and the result carries a confidence so the owner can correct a wrong guess.
 */
object WorkbookAnalyzer {
    private val nameWords = mapOf(
        SheetPurpose.PRODUCTS to listOf("item master", "product master", "item", "items", "product", "products", "goods", "material", "materials", "catalog", "price list", "பொருள்"),
        SheetPurpose.STOCK to listOf("stock", "inventory", "closing", "godown", "warehouse", "இருப்பு"),
        SheetPurpose.CUSTOMERS to listOf("customer", "customers", "client", "clients", "buyer", "buyers", "debtor", "debtors", "வாடிக்கையாளர்"),
        SheetPurpose.SUPPLIERS to listOf("supplier", "suppliers", "vendor", "vendors", "creditor", "creditors", "சப்ளையர்"),
        SheetPurpose.SALES to listOf("sale", "sales", "outward", "invoice", "invoices", "billing", "விற்பனை"),
        SheetPurpose.PURCHASES to listOf("purchase", "purchases", "inward", "கொள்முதல்"),
        SheetPurpose.EXPENSES to listOf("expense", "expenses", "expenditure", "செலவு"),
        SheetPurpose.PAYMENTS to listOf("cash book", "cashbook", "payment", "payments", "receipt", "receipts", "ledger", "bank", "accounts"),
    )
    private val headerWords = mapOf(
        SheetPurpose.PRODUCTS to listOf("item name", "product name", "product", "item", "description", "selling price", "sale price", "mrp", "rate", "price", "unit", "uom", "category", "hsn", "barcode", "item code", "product code"),
        SheetPurpose.STOCK to listOf("closing stock", "closing", "opening", "inward", "outward", "balance qty", "stock", "qty", "quantity", "current stock", "reorder"),
        SheetPurpose.CUSTOMERS to listOf("customer name", "customer", "client", "party name", "phone", "mobile", "address", "gst", "gstin", "credit limit", "city"),
        SheetPurpose.SUPPLIERS to listOf("supplier name", "supplier", "vendor", "phone", "mobile", "address", "gst", "gstin", "city"),
        SheetPurpose.SALES to listOf("invoice no", "invoice", "bill no", "customer", "date", "amount", "total", "qty", "rate", "sold"),
        SheetPurpose.PURCHASES to listOf("purchase no", "bill no", "supplier", "vendor", "date", "amount", "total", "qty", "rate"),
        SheetPurpose.EXPENSES to listOf("expense", "category", "paid to", "date", "amount", "description", "particulars", "head"),
        SheetPurpose.PAYMENTS to listOf("receipt", "payment", "debit", "credit", "balance", "date", "particulars", "narration", "mode"),
    )

    private fun norm(s: String) = s.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").replace('_', ' ').lowercase().replace(Regex("[^\\p{L}\\p{N}\\p{M}]+"), " ").trim()
    private fun has(text: String, word: String) = (" $text ").contains(" ${norm(word)} ")
    private fun isNumber(s: String) = s.replace(",", "").toBigDecimalOrNull() != null

    /** The first row that looks like column titles: at least two cells, mostly text, with data underneath. */
    fun headerRow(rows: List<List<String>>): Int {
        var best = 0
        var bestScore = -1
        for (i in 0 until minOf(rows.size, 10)) {
            val cells = rows[i].filter { it.isNotBlank() }
            if (cells.size < 2) continue
            val text = cells.count { !isNumber(it) }
            if (text * 10 < cells.size * 6) continue
            val score = text * 2 + (if (i + 1 < rows.size) 1 else 0) - i
            if (score > bestScore) { bestScore = score; best = i }
        }
        return best
    }

    fun analyze(sheets: List<RawSheet>): List<SheetProfile> {
        val profiles = sheets.map { sheet ->
            val h = headerRow(sheet.rows)
            val table = if (sheet.rows.isEmpty()) emptyList() else sheet.rows.drop(h)
            val headers = table.firstOrNull().orEmpty()
            val name = norm(sheet.name)
            val headerText = headers.map { norm(it) }
            var bestPurpose = SheetPurpose.OTHER
            var bestScore = 0
            for (purpose in SheetPurpose.entries) {
                if (purpose == SheetPurpose.OTHER) continue
                val nameHit = nameWords.getValue(purpose).any { has(name, it) }
                val hits = headerWords.getValue(purpose).count { w -> headerText.any { has(it, w) } }
                val score = (if (nameHit) 55 else 0) + minOf(45, hits * 9)
                if (score > bestScore) { bestScore = score; bestPurpose = purpose }
            }
            // A dated list of product lines is a transaction log, not a product list, whatever the sheet is called.
            val hasDate = headerText.any { has(it, "date") || has(it, "தேதி") }
            val hasItem = headerText.any { h -> listOf("product", "item", "description", "particulars", "goods").any { has(h, it) } }
            val hasQty = headerText.any { h -> listOf("qty", "quantity", "units", "nos", "pcs").any { has(h, it) } }
            val namedAsList = listOf(SheetPurpose.PRODUCTS, SheetPurpose.STOCK, SheetPurpose.CUSTOMERS, SheetPurpose.SUPPLIERS).any { pp -> nameWords.getValue(pp).any { has(name, it) } }
            if (hasDate && hasItem && hasQty && !namedAsList && bestPurpose !in setOf(SheetPurpose.SALES, SheetPurpose.PURCHASES)) {
                val buying = nameWords.getValue(SheetPurpose.PURCHASES).any { has(name, it) } || headerText.any { has(it, "supplier") || has(it, "vendor") }
                bestPurpose = if (buying) SheetPurpose.PURCHASES else SheetPurpose.SALES
                bestScore = maxOf(bestScore, 65)
            }
            if (bestScore < 27 || headers.size < 2 || table.size < 2) { bestPurpose = SheetPurpose.OTHER; bestScore = minOf(bestScore, 25) }
            val columns = headers.mapIndexed { c, title ->
                val values = table.drop(1).take(40).map { it.getOrNull(c).orEmpty() }.filter { it.isNotBlank() }
                val type = when {
                    values.isEmpty() -> "empty"
                    values.count { isNumber(it) } * 10 >= values.size * 8 -> "number"
                    else -> "text"
                }
                ColumnProfile(title.ifBlank { "Column ${c + 1}" }, type, values.firstOrNull().orEmpty().take(40))
            }
            SheetProfile(sheet.name, sheet.hidden, bestPurpose, bestScore.coerceIn(0, 100), columns, table)
        }
        // Columns with the same title in two sheets usually link them (e.g. Product Code in ITEM MASTER and STOCK).
        return profiles.map { p ->
            val mine = p.headers.map { norm(it) }.filter { it.isNotEmpty() }.toSet()
            val links = profiles.filter { it !== p }.mapNotNull { other ->
                val shared = other.headers.filter { norm(it) in mine && Regex("code|name|id|no|product|item|customer|supplier|party").containsMatchIn(norm(it)) }
                if (shared.isEmpty()) null else "${other.name} (${shared.take(2).joinToString(", ")})"
            }
            p.copy(links = links)
        }
    }

    fun importKind(purpose: SheetPurpose, haveProductSheet: Boolean): ImportKind? = when (purpose) {
        SheetPurpose.PRODUCTS -> ImportKind.PRODUCTS
        // A stock sheet on its own is also the product list; next to an item master it only carries quantities.
        SheetPurpose.STOCK -> if (haveProductSheet) ImportKind.OPENING_STOCK else ImportKind.PRODUCTS
        SheetPurpose.CUSTOMERS -> ImportKind.CUSTOMERS
        SheetPurpose.SUPPLIERS -> ImportKind.SUPPLIERS
        else -> null
    }

    /** Sheets in the order they must be imported: products before stock quantities. */
    fun importOrder(profiles: List<SheetProfile>): List<Pair<SheetProfile, ImportKind>> {
        val haveProducts = profiles.any { it.purpose == SheetPurpose.PRODUCTS }
        val order = listOf(SheetPurpose.PRODUCTS, SheetPurpose.STOCK, SheetPurpose.CUSTOMERS, SheetPurpose.SUPPLIERS)
        val direct = order.flatMap { purpose -> profiles.filter { it.purpose == purpose } }.mapNotNull { p -> importKind(p.purpose, haveProducts)?.let { p to it } }
        // A sales or purchase register also tells us which products exist: they are added with their price and no stock.
        val fromLogs = profiles.filter { it.purpose == SheetPurpose.SALES || it.purpose == SheetPurpose.PURCHASES }.mapNotNull { p -> productsFromLog(p)?.let { p.copy(table = it) to ImportKind.PRODUCTS } }
        return direct + fromLogs
    }

    /** The distinct products named in a transaction sheet, as a small product table; null when it has no product column. */
    fun productsFromLog(p: SheetProfile): List<List<String>>? {
        val h = p.headers.map { norm(it) }
        fun col(vararg words: String) = words.firstNotNullOfOrNull { w -> h.indices.firstOrNull { h[it] == w } } ?: words.firstNotNullOfOrNull { w -> h.indices.firstOrNull { has(h[it], w) } }
        val nameCol = col("product name", "item name", "product", "item", "description", "particulars") ?: return null
        val priceCol = col("unit price", "selling price", "sale price", "rate", "price", "mrp")
        val unitCol = col("unit", "uom")?.takeIf { it != priceCol }
        val catCol = col("category", "group")
        val priceTitle = if (p.purpose == SheetPurpose.PURCHASES) "Purchase price" else "Selling price"
        val seen = LinkedHashMap<String, List<String>>()
        for (row in p.table.drop(1)) {
            val name = row.getOrNull(nameCol).orEmpty().trim()
            if (name.isEmpty() || isNumber(name)) continue
            val key = name.lowercase()
            val price = priceCol?.let { row.getOrNull(it) }.orEmpty().trim()
            val old = seen[key]
            // The latest row with a price wins, so the product carries its most recent price.
            if (old == null || price.isNotEmpty()) seen[key] = listOf(name, price.ifEmpty { old?.get(1).orEmpty() }, unitCol?.let { row.getOrNull(it) }.orEmpty(), catCol?.let { row.getOrNull(it) }.orEmpty())
            if (seen.size >= 5000) break
        }
        if (seen.isEmpty()) return null
        return listOf(listOf("Product name", priceTitle, "Unit", "Category")) + seen.values
    }
}
