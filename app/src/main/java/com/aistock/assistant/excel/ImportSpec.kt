package com.aistock.assistant.excel

import com.aistock.assistant.utils.Money
import com.aistock.assistant.utils.NameMatcher
import com.aistock.assistant.utils.Qty
import com.aistock.assistant.utils.Units

enum class ImportKind { PRODUCTS, CUSTOMERS, SUPPLIERS, OPENING_STOCK }

data class ImportField(val key: String, val required: Boolean, val synonyms: List<String>)

data class ImportRow(val rowNumber: Int, val values: Map<String, String>)
data class ImportIssue(val rowNumber: Int, val message: String)
data class ImportPreview(val ready: List<ImportRow>, val skipped: List<ImportIssue>, val errors: List<ImportIssue>)

/** Which columns each import understands, how headers are guessed, and how rows are checked before anything is saved. */
object ImportSpec {
    fun fields(kind: ImportKind): List<ImportField> = when (kind) {
        ImportKind.PRODUCTS -> listOf(
            ImportField("name", true, listOf("name", "product", "product name", "item", "item name", "description of goods", "particulars", "பொருள்", "பெயர்")),
            ImportField("code", false, listOf("code", "product code", "item code", "sku", "id")),
            ImportField("category", false, listOf("category", "group", "type", "வகை")),
            ImportField("unit", false, listOf("unit", "uom", "units", "யூனிட்")),
            ImportField("barcode", false, listOf("barcode", "bar code", "ean", "upc")),
            ImportField("purchasePrice", false, listOf("purchase price", "cost", "cost price", "buying price", "purchase rate", "buy price")),
            ImportField("sellingPrice", false, listOf("selling price", "sale price", "sales price", "price", "mrp", "rate", "sell price", "விலை")),
            ImportField("minStock", false, listOf("minimum stock", "min stock", "minimum", "reorder level", "reorder", "min")),
            ImportField("openingStock", false, listOf("opening stock", "stock", "quantity", "qty", "current stock", "closing stock", "balance", "இருப்பு")),
            ImportField("description", false, listOf("description", "notes", "remarks", "details")),
        )
        ImportKind.CUSTOMERS, ImportKind.SUPPLIERS -> listOf(
            ImportField("name", true, listOf("name", "customer", "customer name", "supplier", "supplier name", "party", "party name", "vendor", "company", "பெயர்")),
            ImportField("phone", false, listOf("phone", "mobile", "contact", "phone number", "mobile number", "contact number", "தொலைபேசி")),
            ImportField("email", false, listOf("email", "e-mail", "mail", "email id")),
            ImportField("address", false, listOf("address", "city", "location", "place", "முகவரி")),
            ImportField("openingBalance", false, listOf("opening balance", "balance", "outstanding", "due", "amount due", "நிலுவை")),
            ImportField("notes", false, listOf("notes", "remarks", "comments")),
        )
        ImportKind.OPENING_STOCK -> listOf(
            ImportField("name", true, listOf("name", "product", "product name", "item", "item name", "code", "product code", "பொருள்")),
            ImportField("quantity", true, listOf("quantity", "qty", "stock", "opening stock", "count", "closing stock", "இருப்பு")),
            ImportField("unitCost", false, listOf("unit cost", "cost", "purchase price", "rate", "cost price")),
        )
    }

    private fun norm(h: String) = h.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").replace('_', ' ').lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Guesses which column holds which field. Returns field key -> column index. */
    fun autoMap(headers: List<String>, kind: ImportKind): Map<String, Int> {
        val normalized = headers.map { norm(it) }
        val used = HashSet<Int>()
        val map = LinkedHashMap<String, Int>()
        // exact header matches first, then partial ones, so "price" does not steal "purchase price".
        for (field in fields(kind)) {
            for (syn in field.synonyms) {
                val i = normalized.indexOfFirst { it == norm(syn) }
                if (i >= 0 && i !in used) { map[field.key] = i; used.add(i); break }
            }
        }
        for (field in fields(kind)) {
            if (field.key in map) continue
            for (syn in field.synonyms.sortedByDescending { it.length }) {
                val s = norm(syn)
                val i = normalized.indices.firstOrNull { it !in used && normalized[it].isNotEmpty() && (" " + normalized[it] + " ").contains(" $s ") }
                if (i != null) { map[field.key] = i; used.add(i); break }
            }
        }
        // Many price lists call the product column "Description" or "Particulars": with no name column, that is the name.
        if ("name" !in map && "description" in map) map["name"] = map.remove("description")!!
        return map
    }

    /**
     * Checks every data row. Existing records are skipped (never overwritten) and reported.
     * @param existingNames names already saved, for PRODUCTS/CUSTOMERS/SUPPLIERS; for OPENING_STOCK the known product names and codes.
     */
    fun preview(kind: ImportKind, table: List<List<String>>, mapping: Map<String, Int>, existingNames: Collection<String>): ImportPreview {
        val ready = ArrayList<ImportRow>()
        val skipped = ArrayList<ImportIssue>()
        val errors = ArrayList<ImportIssue>()
        val existing = existingNames.map { NameMatcher.key(it) }.toHashSet()
        val seen = HashSet<String>()
        val defs = fields(kind)
        for (f in defs) {
            if (f.required && mapping[f.key] == null) {
                errors.add(ImportIssue(1, "Choose the column for ${f.key}"))
            }
        }
        if (errors.isNotEmpty()) return ImportPreview(ready, skipped, errors)

        for (r in 1 until table.size) {
            val row = table[r]
            val rowNo = r + 1
            val values = LinkedHashMap<String, String>()
            for (f in defs) {
                val col = mapping[f.key] ?: continue
                val v = row.getOrNull(col)?.trim().orEmpty()
                if (v.isNotEmpty()) values[f.key] = v
            }
            if (values.isEmpty()) continue
            val name = values["name"]
            if (name.isNullOrBlank()) { errors.add(ImportIssue(rowNo, "Name is missing")); continue }
            if (name.length > 120) { errors.add(ImportIssue(rowNo, "Name is too long")); continue }
            val key = NameMatcher.key(name)
            var bad: String? = null
            for (moneyField in listOf("purchasePrice", "sellingPrice", "openingBalance", "unitCost")) {
                val v = values[moneyField] ?: continue
                if (Money.parse(v) == null) bad = "$moneyField '$v' is not a valid amount"
            }
            for (qtyField in listOf("minStock", "openingStock", "quantity")) {
                val v = values[qtyField] ?: continue
                val q = Qty.parse(v)
                if (q == null || q < 0) bad = "$qtyField '$v' is not a valid quantity"
            }
            if (kind == ImportKind.OPENING_STOCK && values["quantity"] == null) bad = "Quantity is missing"
            values["unit"]?.let { values["unit"] = Units.custom(it) }
            if (bad != null) { errors.add(ImportIssue(rowNo, bad)); continue }

            if (kind == ImportKind.OPENING_STOCK) {
                if (key !in existing) { errors.add(ImportIssue(rowNo, "Product '$name' was not found")); continue }
                if (!seen.add(key)) { skipped.add(ImportIssue(rowNo, "'$name' appears more than once in the file")); continue }
            } else {
                if (key in existing) { skipped.add(ImportIssue(rowNo, "'$name' already exists and was left unchanged")); continue }
                if (!seen.add(key)) { skipped.add(ImportIssue(rowNo, "'$name' appears more than once in the file")); continue }
            }
            ready.add(ImportRow(rowNo, values))
        }
        return ImportPreview(ready, skipped, errors)
    }
}
