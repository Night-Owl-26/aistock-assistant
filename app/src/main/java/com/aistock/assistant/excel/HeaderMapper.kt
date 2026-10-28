package com.aistock.assistant.excel

import com.aistock.assistant.utils.MiniJson
import com.aistock.assistant.utils.Money
import com.aistock.assistant.utils.Qty

/** What the AI said about one sheet, after checking. [fields] are column indexes by [HeaderMapper] field name. */
data class SheetGuess(val purpose: SheetPurpose?, val confidence: Int, val fields: Map<String, Int>)

/** Final column choice for one import: kind field -> column, and who chose each ("ai", "saved", "you" or "rules"). */
data class ColumnChoice(val mapping: Map<String, Int>, val source: Map<String, String>)

/**
 * Lets a language model read a workbook's column titles and a few example values, whatever language or spelling the
 * owner used ("Itm nm", "Rate/pc", "சைஸ்", "Qty bal"), and say which column holds which field.
 *
 * The model only suggests. Every answer is checked here: unknown fields and headers that are not in the sheet are
 * dropped, no column is used twice, and a price or quantity column must actually hold numbers. Anything the model
 * leaves out falls back to the header rules in [ImportSpec.autoMap], and the owner sees and can change every choice
 * before anything is imported.
 */
object HeaderMapper {
    /** The fields the model may use, with the description it is given. One vocabulary for every kind of sheet. */
    val FIELDS: LinkedHashMap<String, String> = linkedMapOf(
        "name" to "product, customer or supplier name",
        "size" to "size or variant of a product: S, M, L, XL, 28, 32, 500 ml, 1 kg",
        "code" to "product or item code, SKU",
        "category" to "product group or category",
        "unit" to "unit of measure: pcs, kg, metre, box",
        "barcode" to "barcode / EAN",
        "purchasePrice" to "cost / purchase price per unit",
        "sellingPrice" to "selling price / MRP / rate per unit",
        "minStock" to "minimum stock / reorder level",
        "stock" to "current, opening or closing stock quantity on hand",
        "description" to "free-text description of a product",
        "phone" to "phone or mobile number",
        "email" to "email address",
        "address" to "address, city or place",
        "openingBalance" to "money owed: opening balance / outstanding / due",
        "notes" to "remarks or notes",
    )
    private val MONEY = setOf("purchasePrice", "sellingPrice", "openingBalance")
    private val QTY = setOf("stock", "minStock")

    /** Kind-specific import key for one shared field name, or null when that kind has no such field. */
    fun kindKey(kind: ImportKind, field: String): String? {
        val key = when {
            field == "stock" && kind == ImportKind.PRODUCTS -> "openingStock"
            field == "stock" && kind == ImportKind.OPENING_STOCK -> "quantity"
            field == "purchasePrice" && kind == ImportKind.OPENING_STOCK -> "unitCost"
            else -> field
        }
        return key.takeIf { k -> ImportSpec.fields(kind).any { it.key == k } }
    }

    /** The shared field name for one kind-specific import key (the reverse of [kindKey]). */
    fun sharedField(kind: ImportKind, key: String): String = when {
        key == "openingStock" || key == "quantity" -> "stock"
        key == "unitCost" -> "purchasePrice"
        else -> key
    }

    /** Header text used to refer to a column; blank titles become "Column 3". */
    fun title(headers: List<String>, i: Int): String = headers.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() } ?: "Column ${i + 1}"

    /**
     * Hides personal data before anything leaves the phone: digits in long numbers (phones, accounts) become 9s and
     * email addresses become a placeholder. Short values such as prices, sizes and names are kept: they are what
     * tells the model what a column holds.
     */
    fun mask(v: String): String {
        var t = v.trim().take(30)
        if (t.contains('@')) return "name@example.com"
        t = t.replace(Regex("\\d[\\d \\-]{6,}\\d")) { m -> m.value.map { if (it.isDigit()) '9' else it }.joinToString("") }
        return t
    }

    private const val MAX_SHEETS = 12
    private const val MAX_COLS = 40

    /** The request sent to the model: titles and up to three masked example values per column, never the whole sheet. */
    fun prompt(profiles: List<SheetProfile>): String = buildString {
        append("You match spreadsheet columns for Follower, an inventory and billing app used by small shops in Tamil Nadu.\n")
        append("For each sheet below, say what it holds and which column holds each field. Titles may be English, Tamil, Tanglish, short forms or misspelt.\n\n")
        append("Purposes: PRODUCTS (item or price list), STOCK (stock quantities per item), CUSTOMERS, SUPPLIERS, SALES (sales register or bills), PURCHASES, EXPENSES, PAYMENTS (cash book, receipts), OTHER.\n")
        append("Fields:\n")
        for ((k, d) in FIELDS) append("- ").append(k).append(": ").append(d).append('\n')
        append("\nRules: use a column title exactly as written below. Leave a field out when no column fits; never guess. ")
        append("Never use one column for two fields. A sheet that is a dated list of bills is SALES or PURCHASES, not PRODUCTS. ")
        append("confidence is 0-100 for the purpose.\n")
        append("Reply with JSON only, no other text: {\"sheets\":[{\"sheet\":\"<sheet name>\",\"purpose\":\"PRODUCTS\",\"confidence\":90,\"columns\":{\"name\":\"<column title>\",\"size\":\"<column title>\"}}]}\n")
        for (p in profiles.take(MAX_SHEETS)) {
            append("\nSheet \"").append(p.name).append("\" (").append(p.rowCount).append(" rows)\n")
            val heads = p.headers.take(MAX_COLS)
            for (i in heads.indices) {
                val examples = p.table.drop(1).asSequence().mapNotNull { it.getOrNull(i)?.takeIf { v -> v.isNotBlank() } }.take(3).map { "\"" + mask(it).replace("\"", "'") + "\"" }.toList()
                append(i + 1).append(". \"").append(title(heads, i).replace("\"", "'").take(60)).append('"')
                if (examples.isNotEmpty()) append(" e.g. ").append(examples.joinToString(", "))
                append('\n')
            }
        }
    }

    /**
     * Reads and checks the model's reply. Returns sheet name -> guess, only for sheets that were asked about.
     * A reply that is not valid JSON gives an empty map, so the rules are used.
     */
    fun parse(reply: String, profiles: List<SheetProfile>): Map<String, SheetGuess> {
        val root = MiniJson.firstObject(reply) ?: return emptyMap()
        val list = root["sheets"] as? List<*> ?: return emptyMap()
        val out = LinkedHashMap<String, SheetGuess>()
        for (item in list) {
            val o = item as? Map<*, *> ?: continue
            val sheetName = (o["sheet"] as? String)?.trim() ?: continue
            val p = profiles.firstOrNull { it.name == sheetName } ?: profiles.firstOrNull { it.name.equals(sheetName, ignoreCase = true) } ?: continue
            val purpose = (o["purpose"] as? String)?.trim()?.uppercase()?.let { s -> SheetPurpose.entries.firstOrNull { it.name == s } }
            val conf = ((o["confidence"] as? Double) ?: (o["confidence"] as? String)?.toDoubleOrNull() ?: 50.0).toInt().coerceIn(0, 100)
            val cols = o["columns"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val fields = LinkedHashMap<String, Int>()
            val used = HashSet<Int>()
            for ((k, v) in cols) {
                val field = k as? String ?: continue
                if (field !in FIELDS) continue
                val header = (v as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val idx = columnOf(p.headers, header) ?: continue
                if (idx in used || field in fields) continue
                if (!valuesFit(field, p.table, idx)) continue
                fields[field] = idx; used.add(idx)
            }
            out[p.name] = SheetGuess(purpose, conf, fields)
        }
        return out
    }

    /** Column index for a title the model gave back: exact first, then ignoring case and spacing, then "Column 3". */
    fun columnOf(headers: List<String>, header: String): Int? {
        val heads = headers.take(MAX_COLS)
        heads.indexOfFirst { it.trim() == header }.takeIf { it >= 0 }?.let { return it }
        fun n(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}\\p{M}]+"), "")
        val want = n(header)
        if (want.isNotEmpty()) {
            val hits = heads.indices.filter { n(heads[it]) == want }
            if (hits.size == 1) return hits[0]
        }
        Regex("(?i)^column\\s*(\\d+)$").find(header)?.let { m ->
            val i = m.groupValues[1].toInt() - 1
            if (i in heads.indices && heads[i].isBlank()) return i
        }
        return null
    }

    /** A price or quantity column must mostly hold numbers; a name column must not be only numbers. */
    fun valuesFit(field: String, table: List<List<String>>, col: Int): Boolean {
        val values = table.drop(1).asSequence().mapNotNull { it.getOrNull(col)?.trim()?.takeIf { v -> v.isNotEmpty() } }.take(40).toList()
        if (values.isEmpty()) return true
        return when (field) {
            in MONEY -> values.count { Money.parse(it) != null } * 10 >= values.size * 7
            in QTY -> values.count { Qty.parse(it)?.let { q -> q >= 0 } == true } * 10 >= values.size * 7
            "name" -> values.any { it.replace(",", "").toBigDecimalOrNull() == null }
            else -> true
        }
    }

    /** Applies the model's guesses to the analysed sheets. The model's purpose is used only when it is surer than the rules. */
    fun apply(profiles: List<SheetProfile>, guesses: Map<String, SheetGuess>): List<SheetProfile> = profiles.map { p ->
        val g = guesses[p.name] ?: return@map p
        var out = p
        if (p.purposeSource == "rules" && g.purpose != null && g.purpose != p.purpose && g.confidence > p.confidence) {
            out = out.copy(purpose = g.purpose, confidence = g.confidence, purposeSource = "ai")
        } else if (p.purposeSource == "rules" && g.purpose == p.purpose) {
            // The model agrees with the rules: that agreement is worth more than either guess alone.
            out = out.copy(confidence = maxOf(p.confidence, minOf(95, g.confidence)))
        }
        val fields = LinkedHashMap(out.fields)
        val source = LinkedHashMap(out.fieldSource)
        for ((f, c) in g.fields) if (source[f] != "you" && source[f] != "saved") { fields[f] = c; source[f] = "ai" }
        out.copy(fields = fields, fieldSource = source)
    }

    /**
     * The columns one import will use: choices in [SheetProfile.fields] first (owner, saved or AI), then the header
     * rules for whatever is still missing, never reusing a column.
     */
    fun choose(p: SheetProfile, kind: ImportKind): ColumnChoice {
        val mapping = LinkedHashMap<String, Int>()
        val source = LinkedHashMap<String, String>()
        val used = HashSet<Int>()
        val off = HashSet<String>()
        for ((f, c) in p.fields) {
            val key = kindKey(kind, f) ?: continue
            if (c < 0) { off.add(key); continue }
            if (c >= p.headers.size || c in used) continue
            mapping[key] = c; used.add(c); source[key] = p.fieldSource[f] ?: "ai"
        }
        // An opening-stock sheet may identify products by code only.
        if (kind == ImportKind.OPENING_STOCK && "name" !in mapping && "name" !in off) {
            p.fields["code"]?.takeIf { it >= 0 && it !in used }?.let { mapping["name"] = it; used.add(it); source["name"] = p.fieldSource["code"] ?: "ai" }
        }
        for ((key, c) in ImportSpec.autoMap(p.headers, kind)) {
            if (key in mapping || key in off || c in used) continue
            mapping[key] = c; used.add(c); source[key] = "rules"
        }
        return ColumnChoice(mapping, source)
    }

    /** Fingerprint of a sheet's column titles, so a mapping the owner confirmed is reused for the same layout next time. */
    fun signature(headers: List<String>): String =
        headers.joinToString("|") { it.lowercase().replace(Regex("[^\\p{L}\\p{N}\\p{M}]+"), " ").trim() }.take(1000)
}
