package com.aistock.assistant.ai

import com.aistock.assistant.utils.DateKey
import com.aistock.assistant.utils.DateRanges
import com.aistock.assistant.utils.Money
import com.aistock.assistant.utils.NameMatcher
import com.aistock.assistant.utils.Qty
import com.aistock.assistant.utils.Units

/** Works fully offline. Understands the common V1 commands in English and mixed Tamil-English. */
class LocalFallbackAIProvider : AIProvider {
    override val name: String = "local"
    override suspend fun understand(conversation: List<ChatTurn>, input: String): AICommand = LocalParser.parse(input)
}

object LocalParser {
    const val GUIDE_PAYMENT = "GUIDE_PAYMENT"
    const val GUIDE_DELETE = "GUIDE_DELETE"
    const val GUIDE_GREETING = "GUIDE_GREETING"
    const val GUIDE_THANKS = "GUIDE_THANKS"
    const val FIELD_RECORD_TYPE = "recordType"

    private const val NUM = "(\\d+(?:\\.\\d+)?)"
    private const val CUR = "(?:rupees|inr)"
    private const val EACH = "(?:each|per\\s+[a-z]+|a\\s+piece|apiece)"
    private val UNITS = Units.WORD_PATTERN

    private val QTY = Regex("(?<![a-z0-9.])$NUM\\s*(?:($UNITS)(?![a-z0-9]))?(?:\\s+of\\b)?")
    private val ANY_NUM = Regex("(?<![a-z0-9.])$NUM(?![a-z0-9])")
    private val PRICE_UNIT1 = Regex("(?:\\bat|@|\\brate|\\bprice)\\s*(?:of\\s+)?(?:$CUR\\s*)?$NUM\\s*$CUR?(?:\\s*$EACH)?")
    private val PRICE_UNIT2 = Regex("\\bfor\\s+(?:$CUR\\s*)?$NUM\\s*$CUR?\\s*$EACH")
    private val PRICE_UNIT3 = Regex("(?<![a-z0-9.])$NUM\\s*$CUR\\s*$EACH")
    private val PRICE_TOTAL = Regex("\\bfor\\s+(?:$CUR\\s*)?$NUM\\s*$CUR?(?:\\s*(?:in total|total|altogether))?(?![a-z0-9])")
    private val AMOUNT = Regex("(?:$CUR\\s*)?(?<![a-z0-9.])$NUM\\s*$CUR?")
    private val STOP = "(?:at|for|from|to|because|since|on|@|paid|cash|credit)"
    private val FROM = Regex("\\bfrom\\s+(.+?)(?=\\s+$STOP\\b|\\s+\\d|$)")
    private val TO = Regex("\\bto\\s+(.+?)(?=\\s+$STOP\\b|\\s+\\d|$)")
    private val REASON = Regex("\\b(?:because|since|due to|as)\\s+(.+)$")
    private val PRONOUN = Regex("\\b(them|they|it|those|these|that)\\b")
    private val STARTS_Q = Regex("^(what|whats|which|show|list|how|tell|display|view|give|did|do|does|is|are|any|total|get)\\b")

    private val FILLERS = setOf(
        "please", "the", "a", "an", "of", "we", "i", "have", "has", "had", "just", "now", "some", "new", "them", "they",
        "it", "those", "these", "that", "this", "were", "was", "are", "is", "and", "there", "my", "our", "more", "to",
        "in", "into", "stock", "inventory", "with", "been", "product", "products", "item", "items", "me", "us", "do",
        "did", "can", "you", "kindly", "from", "at", "for", "on", "only", "also", "worth", "then", "so", "ok", "okay",
        "units", "unit", "quantity", "qty", "number", "total", "current", "currently", "available", "left", "remaining",
    )
    private val IN_VERBS = setOf(
        "received", "receive", "got", "add", "added", "adding", "came", "arrived", "inward", "put", "increase",
        "increased", "plus", "load", "loaded", "restock", "restocked", "returned", "return", "by", "customer",
    )
    private val OUT_VERBS = setOf(
        "remove", "removed", "deduct", "deducted", "reduce", "reduced", "minus", "subtract", "take", "took", "taken",
        "out", "write", "wrote", "off", "damaged", "damage", "wasted", "waste", "expired", "lost", "missing", "stolen",
        "issued", "issue", "used", "consumed", "discard", "discarded", "scrap", "scrapped", "broken", "returned",
        "return", "supplier",
    )
    private val ADJUST_VERBS = setOf(
        "counted", "count", "actually", "actual", "physical", "physically", "adjust", "adjusted", "correct", "corrected",
        "recount", "recounted", "set", "update", "found", "exactly", "should", "be", "as", "make", "change",
    )
    private val SALE_VERBS = setOf("sold", "sell", "selling", "sale", "billed", "bill", "gave", "delivered")
    private val BUY_VERBS = setOf("bought", "purchased", "purchase", "buy", "buying", "ordered")

    private val IN_RE = Regex("\\b(received|receive|got|add|added|adding|stock in|came|arrived|inward|put|increase|increased|plus|load|loaded|restock|restocked|return from|returned by)\\b")
    private val OUT_RE = Regex("\\b(remove|removed|deduct|deducted|reduce|reduced|minus|subtract|take out|took out|taken out|write off|wrote off|damaged|damage|wasted|waste|expired|lost|missing|stolen|issued|issue|used|consumed|stock out|discard|discarded|scrap|scrapped|broken|returned to|return to)\\b")
    private val ADJUST_RE = Regex("\\b(counted|recount|recounted|actually|actual stock|physical stock|physical count|adjust|adjusted|correct stock|set stock|set the stock|stock is|stock should be|make stock|change stock)\\b")
    private val LOW_RE = Regex("\\b(low stock|low on stock|running low|running out|out of stock|reorder|re order|need to order|low products|low items)\\b|\\b(products|items|stock|what|which|anything)\\b.*\\blow\\b|\\blow\\b.*\\b(products|items|stock)\\b")

    fun parse(raw: String): AICommand {
        val s = TextNormalizer.normalize(raw)
        if (s.isBlank()) return unknown()
        val date = DateRanges.detect(s)
        val noDate = DateRanges.strip(s)
        val startsQ = STARTS_Q.containsMatchIn(s)
        val hasNum = ANY_NUM.containsMatchIn(noDate) || QTY.containsMatchIn(noDate)

        if (Regex("^(hi|hii+|hello|hey|vanakkam|good (morning|afternoon|evening)|namaste)( there| aistock| assistant)?$").containsMatchIn(s)) return unknown(GUIDE_GREETING)
        if (Regex("^(thanks|thank you|thankyou|nandri|super|ok thanks|great)( you| a lot| so much)?$").containsMatchIn(s)) return unknown(GUIDE_THANKS)
        if (Regex("^(help|what can you do|how to use|how do i use|commands|examples)\\b").containsMatchIn(s)) {
            return cmd(Intent.HELP, 0.99)
        }
        if (Regex("^(delete|erase|cancel)\\b").containsMatchIn(s)) {
            return unknown(GUIDE_DELETE)
        }

        balance(s, raw)?.let { return it }
        partyCreate(s, raw)?.let { return it }
        productExplicit(s, raw)?.let { return it }
        if (LOW_RE.containsMatchIn(s)) return cmd(Intent.LOW_STOCK_QUERY, 0.96)
        // "list my products", "show all items", "give me the list of tea products"
        Regex("^(?:please )?(?:list|show|give|display|view|tell)(?: me)?(?: the| a)?(?: list of| names of)?(?: all| my| our| the)* ?(.*?) ?(?:products|items)(?: list)?$").find(s)?.let { m ->
            val word = m.groupValues[1].trim()
            return if (word.isEmpty()) cmd(Intent.REPORT_STOCK, 0.95) else cmd(Intent.STOCK_QUERY, 0.93, Entities(productName = restoreCase(raw, word)))
        }

        if (!startsQ && hasNum && ADJUST_RE.containsMatchIn(s)) return adjust(noDate, raw)

        if (!startsQ && hasNum && Regex("\\b(sold|sell|selling|billed|sale of|sale to)\\b").containsMatchIn(s)) {
            return trade(Intent.CREATE_SALE, noDate, raw)
        }
        if (!startsQ && hasNum && Regex("\\b(bought|purchased|purchase|buy|buying)\\b").containsMatchIn(s)) {
            return trade(Intent.CREATE_PURCHASE, noDate, raw)
        }
        // Money moving between you and a customer/supplier is a payment, not stock or an expense.
        if (!startsQ && hasNum) {
            val moneyIn = Regex("\\b(received|got|collected)\\b.*\\b(payment|cash|money|amount)\\b").containsMatchIn(s) ||
                Regex("\\b(received|got|collected)\\s+(?:$CUR\\s*)?$NUM\\s*$CUR\\s+from\\b").containsMatchIn(s) ||
                Regex("\\b(received|got|collected)\\s+$CUR\\s*$NUM\\s+from\\b").containsMatchIn(s)
            val moneyOut = Regex("\\b(paid|pay|gave)\\b").containsMatchIn(s) && Regex("\\bto\\s+\\S+").containsMatchIn(noDate) &&
                !Regex("\\bfor\\b").containsMatchIn(s) && !Regex("\\bexpenses?\\b").containsMatchIn(s)
            val either = Regex("\\bpayment\\b").containsMatchIn(s) && !moneyIn && !Regex("\\b(paid|pay|gave)\\b").containsMatchIn(s)
            if (moneyIn || moneyOut || either) {
                val amount = AMOUNT.find(noDate)?.let { Money.parse(it.groupValues[1]) }
                val from = FROM.find(noDate)?.groupValues?.get(1)?.let { restoreCase(raw, cleanName(it.replace(Regex("\\b(payment|cash|money|amount|rupees)\\b"), " "))) }
                val to = TO.find(noDate)?.groupValues?.get(1)?.let { restoreCase(raw, cleanName(it.replace(Regex("\\b(payment|cash|money|amount|rupees)\\b"), " "))) }
                val e = when {
                    moneyIn -> Entities(amountPaise = amount, customerName = from ?: to)
                    either -> Entities(amountPaise = amount, customerName = to ?: from, note = "EITHER")
                    else -> Entities(amountPaise = amount, supplierName = to, note = "EITHER")
                }
                return cmd(Intent.RECORD_PAYMENT, 0.95, e)
            }
        }
        if (!startsQ && hasNum && Regex("\\b(paid|pay|spent|spend|expense|expenses)\\b").containsMatchIn(s)) {
            return expense(noDate, raw)
        }

        val wantsReport = Regex("\\b(report|summary|statement)\\b").containsMatchIn(s)
        if (Regex("\\b(sales?|sold|selling)\\b").containsMatchIn(s)) {
            return if (wantsReport) cmd(Intent.REPORT_SALES, 0.95, Entities(dateKey = date ?: DateKey.THIS_MONTH))
            else cmd(Intent.SALES_QUERY, 0.95, Entities(dateKey = date ?: DateKey.TODAY))
        }
        if (Regex("\\b(purchases?|purchased|bought|buy)\\b").containsMatchIn(s)) {
            return if (wantsReport) cmd(Intent.REPORT_PURCHASES, 0.95, Entities(dateKey = date ?: DateKey.THIS_MONTH))
            else cmd(Intent.PURCHASE_QUERY, 0.95, Entities(dateKey = date ?: DateKey.TODAY))
        }
        if (Regex("\\b(expenses?|spent|spend|spending)\\b").containsMatchIn(s)) {
            return if (wantsReport) cmd(Intent.REPORT_EXPENSES, 0.95, Entities(dateKey = date ?: DateKey.THIS_MONTH))
            else cmd(Intent.EXPENSE_QUERY, 0.95, Entities(dateKey = date ?: DateKey.TODAY))
        }
        if (Regex("\\b(stock|inventory)\\s+(report|summary|value|valuation)\\b|^(what is|whats|show|view|display|tell me)?\\s*(my |our |the )?(current |total |all |full )?(stock|inventory)( value| list| position)?$").containsMatchIn(s)) {
            return cmd(Intent.REPORT_STOCK, 0.95)
        }

        stockQuery(s, raw)?.let { return it }

        if (!startsQ && OUT_RE.containsMatchIn(s)) return stockOut(noDate, raw)
        if (!startsQ && IN_RE.containsMatchIn(s)) {
            val bare = Regex("^(?:please )?(?:add|create|register|save) (.+)$").find(s)
            if (!hasNum && bare != null) {
                // "Add Kumar Traders" - could be a customer, a supplier or a product: ask.
                val name = restoreCase(raw, bare.groupValues[1].trim())
                return AICommand(
                    Intent.CREATE_CUSTOMER, 0.9, true, Entities(customerName = name),
                    listOf(FIELD_RECORD_TYPE), "Add $name"
                )
            }
            return stockIn(noDate, raw)
        }

        partyQuery(s, raw)?.let { return it }
        return unknown()
    }

    // ---------- individual rules ----------

    private fun balance(s: String, raw: String): AICommand? {
        Regex("\\bhow much (?:do |did |does |should )?(?:i|we) (?:still )?owe(?: to)? (.+)$").find(s)?.let {
            return cmd(Intent.SUPPLIER_BALANCE, 0.96, Entities(supplierName = restoreCase(raw, cleanName(it.groupValues[1]))))
        }
        Regex("\\bwhat (?:do |did )?(?:i|we) owe(?: to)? (.+)$").find(s)?.let {
            return cmd(Intent.SUPPLIER_BALANCE, 0.96, Entities(supplierName = restoreCase(raw, cleanName(it.groupValues[1]))))
        }
        Regex("\\b(?:how much|what) (?:does |do |did |is )?(.+?) (?:owes?|owing|has to pay|have to pay|need to pay|needs to pay)\\b").find(s)?.let {
            return cmd(Intent.CUSTOMER_BALANCE, 0.96, Entities(customerName = restoreCase(raw, cleanName(it.groupValues[1]))))
        }
        Regex("^(.+?) (?:owes?) (?:me|us)\\b").find(s)?.let {
            return cmd(Intent.CUSTOMER_BALANCE, 0.95, Entities(customerName = restoreCase(raw, cleanName(it.groupValues[1]))))
        }
        if (Regex("\\b(supplier|suppliers|payable|payables)\\s+(outstanding|balances?|dues?)\\b|\\bhow much (do )?(i|we) owe( suppliers| in total)?$").containsMatchIn(s)) {
            return cmd(Intent.SUPPLIER_BALANCE, 0.95)
        }
        if (Regex("\\b(customer|customers|receivable|receivables)\\s+(outstanding|balances?|dues?)\\b|\\bwho owes (me|us)\\b|\\b(total )?outstanding$").containsMatchIn(s)) {
            return cmd(Intent.CUSTOMER_BALANCE, 0.95)
        }
        Regex("\\b(?:balance|outstanding|dues?|pending amount|pending)(?: amount)? (?:of|for|from|with) (.+)$").find(s)?.let {
            return cmd(Intent.CUSTOMER_BALANCE, 0.93, Entities(customerName = restoreCase(raw, cleanName(it.groupValues[1]))))
        }
        Regex("^(?:show |what is |whats |tell me )?(.+?) (?:balance|outstanding|dues?)$").find(s)?.let {
            val n = cleanName(it.groupValues[1])
            if (n.isNotEmpty() && n !in setOf("stock", "my", "the", "total", "current")) {
                return cmd(Intent.CUSTOMER_BALANCE, 0.92, Entities(customerName = restoreCase(raw, n)))
            }
        }
        return null
    }

    private fun partyCreate(s: String, raw: String): AICommand? {
        var kind: String? = null
        var name: String? = null
        Regex("^(?:please )?(?:add|create|register|save|new) (?:a |an )?(?:new )?(customer|supplier|vendor)(?: named| called| name)? (.+)$").find(s)?.let {
            kind = it.groupValues[1]; name = it.groupValues[2]
        }
        if (kind == null) {
            Regex("^(?:please )?(?:add|create|register|save) (.+?) as (?:a |an |my |our )?(?:new )?(customer|supplier|vendor)$").find(s)?.let {
                kind = it.groupValues[2]; name = it.groupValues[1]
            }
        }
        if (kind == null) {
            // Tanglish order: "kumar traders customer add"
            Regex("^(.+?) (?:as )?(customer|supplier|vendor)(?: aa| ah)? add$").find(s)?.let {
                kind = it.groupValues[2]; name = it.groupValues[1]
            }
        }
        val k = kind ?: return null
        var n = name ?: return null
        var phone: String? = null
        Regex("\\b(?:phone|mobile|number|contact)(?: number| no)? (\\d{6,13})\\b").find(n)?.let {
            phone = it.groupValues[1]
            n = n.removeRange(it.range).trim()
        }
        val display = restoreCase(raw, n.trim())
        return if (k == "customer") {
            cmd(Intent.CREATE_CUSTOMER, 0.97, Entities(customerName = display, phone = phone), missing = if (display.isEmpty()) listOf("partyName") else emptyList())
        } else {
            cmd(Intent.CREATE_SUPPLIER, 0.97, Entities(supplierName = display, phone = phone), missing = if (display.isEmpty()) listOf("partyName") else emptyList())
        }
    }

    private fun productExplicit(s: String, raw: String): AICommand? {
        val fieldRe = "(selling price|sale price|sales price|price|mrp|cost price|purchase price|buying price|cost|minimum stock|min stock|minimum|reorder level)"
        val up1 = Regex("^(?:please )?(?:change|update|set|make) (?:the )?$fieldRe (?:of|for) (.+?) (?:to|as|=) (?:$CUR\\s*)?$NUM").find(s)
        val up2 = Regex("^(?:please )?(?:change|update|set|make) (?:the )?(.+?) $fieldRe (?:to|as|=) (?:$CUR\\s*)?$NUM").find(s)
        val upd = up1?.let { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
            ?: up2?.let { Triple(it.groupValues[2], it.groupValues[1], it.groupValues[3]) }
        if (upd != null) {
            val (field, name, value) = upd
            val base = Entities(productName = restoreCase(raw, name.trim()))
            val e = when {
                field.startsWith("min") || field.startsWith("reorder") -> base.copy(minMilli = Qty.parse(value))
                field.startsWith("cost") || field.startsWith("purchase") || field.startsWith("buying") -> base.copy(buyPaise = Money.parse(value))
                else -> base.copy(sellPaise = Money.parse(value))
            }
            return cmd(Intent.UPDATE_PRODUCT, 0.95, e)
        }

        val add = Regex("^(?:please )?(?:add|create|register|new) (?:a |an )?(?:new )?(?:product|item)(?: named| called| name)? ?(.*)$").find(s)
            ?: return null
        var rest = add.groupValues[1]
        var e = Entities()
        Regex("\\b(?:cost price|purchase price|buying price|cost)(?: is| of)? (?:$CUR\\s*)?$NUM\\s*$CUR?").find(rest)?.let {
            e = e.copy(buyPaise = Money.parse(it.groupValues[1])); rest = rest.removeRange(it.range)
        }
        Regex("\\b(?:selling price|sale price|sales price|price|mrp|rate)(?: is| of)? (?:$CUR\\s*)?$NUM\\s*$CUR?").find(rest)?.let {
            e = e.copy(sellPaise = Money.parse(it.groupValues[1])); rest = rest.removeRange(it.range)
        }
        Regex("\\b(?:minimum stock|min stock|minimum|min|reorder level)(?: is| of)? $NUM").find(rest)?.let {
            e = e.copy(minMilli = Qty.parse(it.groupValues[1])); rest = rest.removeRange(it.range)
        }
        Regex("\\b(?:opening stock|opening|stock|qty|quantity)(?: is| of)? $NUM\\s*(?:($UNITS)(?![a-z0-9]))?").find(rest)?.let {
            e = e.copy(quantityMilli = Qty.parse(it.groupValues[1]), unit = Units.normalize(it.groupValues[2]))
            rest = rest.removeRange(it.range)
        }
        if (e.quantityMilli == null) {
            QTY.find(rest)?.let {
                e = e.copy(quantityMilli = Qty.parse(it.groupValues[1]), unit = Units.normalize(it.groupValues[2]))
                rest = rest.removeRange(it.range)
            }
        }
        Regex("\\b(?:unit|in)(?: is)? ($UNITS)(?![a-z0-9])").find(rest)?.let {
            e = e.copy(unit = Units.normalize(it.groupValues[1])); rest = rest.removeRange(it.range)
        }
        val name = remainder(rest, setOf("with", "and", "at"), keepStock = false)
        val display = if (name.isEmpty()) null else restoreCase(raw, name)
        return cmd(Intent.ADD_PRODUCT, 0.96, e.copy(productName = display), missing = if (display == null) listOf("productName") else emptyList())
    }

    private fun adjust(s: String, raw: String): AICommand {
        var t = s
        var qty: Long? = null
        var unit: String? = null
        val fromTo = Regex("\\bfrom\\s+$NUM\\s+to\\s+$NUM\\s*(?:($UNITS)(?![a-z0-9]))?").find(t)
        val to = Regex("\\b(?:to|is|are|be|as|only)\\s+$NUM\\s*(?:($UNITS)(?![a-z0-9]))?").find(t)
        if (fromTo != null) {
            qty = Qty.parse(fromTo.groupValues[2]); unit = Units.normalize(fromTo.groupValues[3]); t = t.removeRange(fromTo.range)
        } else if (to != null) {
            qty = Qty.parse(to.groupValues[1]); unit = Units.normalize(to.groupValues[2]); t = t.removeRange(to.range)
        } else {
            QTY.find(t)?.let { qty = Qty.parse(it.groupValues[1]); unit = Units.normalize(it.groupValues[2]); t = t.removeRange(it.range) }
        }
        val pronoun = PRONOUN.containsMatchIn(t)
        val name = remainder(t, ADJUST_VERBS)
        return build(Intent.STOCK_ADJUST, raw, name, pronoun, Entities(quantityMilli = qty, unit = unit), needQty = true)
    }

    private fun trade(intent: Intent, s: String, raw: String): AICommand {
        var t = s.replace(Regex("\\b(to|in|into) (stock|inventory)\\b"), " ")
        var paid: Boolean? = null
        Regex("\\b(on credit|credit|unpaid|not paid|pay later|payment later|later)\\b").find(t)?.let { paid = false; t = t.removeRange(it.range) }
        if (paid == null) {
            Regex("\\b(fully paid|paid in full|paid|by cash|cash|upi|gpay)\\b").find(t)?.let { paid = true; t = t.removeRange(it.range) }
        }
        val price = cutPrice(t); t = price.rest
        var customer: String? = null
        var supplier: String? = null
        if (intent == Intent.CREATE_SALE) {
            TO.find(t)?.let { customer = it.groupValues[1]; t = t.removeRange(it.range) }
        } else {
            FROM.find(t)?.let { supplier = it.groupValues[1]; t = t.removeRange(it.range) }
        }
        val q = QTY.find(t)
        var e = Entities(
            unitPricePaise = price.unit, totalPaise = price.total, paid = paid,
            customerName = customer?.let { restoreCase(raw, cleanName(it)) }?.ifEmpty { null },
            supplierName = supplier?.let { restoreCase(raw, cleanName(it)) }?.ifEmpty { null },
        )
        if (q != null) {
            e = e.copy(quantityMilli = Qty.parse(q.groupValues[1]), unit = Units.normalize(q.groupValues[2]))
            t = t.removeRange(q.range)
        }
        val pronoun = PRONOUN.containsMatchIn(t)
        val name = remainder(t, if (intent == Intent.CREATE_SALE) SALE_VERBS else BUY_VERBS)
        return build(intent, raw, name, pronoun, e, needQty = true)
    }

    private fun stockIn(s: String, raw: String): AICommand {
        var t = s.replace(Regex("\\b(to|in|into) (stock|inventory)\\b"), " ")
        val price = cutPrice(t, allowTotal = false); t = price.rest
        var supplier: String? = null
        FROM.find(t)?.let { supplier = it.groupValues[1]; t = t.removeRange(it.range) }
        val returned = Regex("\\b(return from|returned by|customer return)\\b").containsMatchIn(s)
        val q = QTY.find(t)
        var e = Entities(
            unitPricePaise = price.unit,
            supplierName = if (returned) null else supplier?.let { restoreCase(raw, cleanName(it)) }?.ifEmpty { null },
            reason = if (returned) "RETURN_IN" else null,
        )
        if (q != null) {
            e = e.copy(quantityMilli = Qty.parse(q.groupValues[1]), unit = Units.normalize(q.groupValues[2]))
            t = t.removeRange(q.range)
        }
        val pronoun = PRONOUN.containsMatchIn(t)
        val name = remainder(t, IN_VERBS)
        return build(Intent.STOCK_IN, raw, name, pronoun, e, needQty = true)
    }

    private fun stockOut(s: String, raw: String): AICommand {
        var t = s.replace(Regex("\\b(from|in) (stock|inventory)\\b"), " ")
        var reasonText: String? = null
        REASON.find(t)?.let { reasonText = it.groupValues[1]; t = t.removeRange(it.range) }
        val all = s
        val reason = when {
            Regex("\\b(damag\\w*|broken|broke|spoil\\w*|torn|defect\\w*|crack\\w*|leak\\w*|wasted|waste|expired|rotten)\\b").containsMatchIn(all) -> "DAMAGE"
            Regex("\\b(returned to|return to|sent back)\\b").containsMatchIn(all) -> "RETURN_OUT"
            else -> "OTHER"
        }
        val q = QTY.find(t)
        var e = Entities(reason = reason, note = reasonText?.trim()?.take(100))
        if (q != null) {
            e = e.copy(quantityMilli = Qty.parse(q.groupValues[1]), unit = Units.normalize(q.groupValues[2]))
            t = t.removeRange(q.range)
        }
        val pronoun = PRONOUN.containsMatchIn(s)
        val name = remainder(t, OUT_VERBS)
        return build(Intent.STOCK_OUT, raw, name, pronoun, e, needQty = true)
    }

    private fun expense(s: String, raw: String): AICommand {
        var t = s
        var amount: Long? = null
        AMOUNT.find(t)?.let { amount = Money.parse(it.groupValues[1]); t = t.removeRange(it.range) }
        var what: String? = Regex("\\b(?:for|on|towards)\\s+(.+)$").find(t)?.groupValues?.get(1)
        if (what == null) {
            val left = t.split(" ").filter { it.isNotEmpty() && it !in FILLERS && it !in setOf("paid", "pay", "spent", "spend", "expense", "expenses", "rupees", "bill", "charges", "charge") }
            what = left.joinToString(" ").ifBlank { null }
        }
        val category = what?.let { expenseCategory(it) }
        val missing = ArrayList<String>()
        if (amount == null) missing.add("amount")
        if (category == null) missing.add("expenseCategory")
        return cmd(Intent.CREATE_EXPENSE, 0.95, Entities(amountPaise = amount, expenseCategory = category, note = what?.trim()?.take(100)), missing)
    }

    fun expenseCategory(text: String): String? {
        val t = text.lowercase().replace(Regex("\\b(the|a|an|my|our|bill|bills|charges|charge|expense|expenses|payment|fees|fee)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        val src = text.lowercase()
        return when {
            Regex("\\b(transport|transportation|lorry|freight|delivery|courier|shipping|cargo|auto|van|tempo)\\b|போக்குவரத்து").containsMatchIn(src) -> "Transport"
            Regex("\\b(rent|rental)\\b|வாடகை").containsMatchIn(src) -> "Rent"
            Regex("\\b(salary|salaries|wages|wage|labour|labor|staff|coolie|sambalam)\\b|சம்பளம்|கூலி").containsMatchIn(src) -> "Salary"
            Regex("\\b(electricity|eb|current|power)\\b|மின்சாரம்").containsMatchIn(src) -> "Electricity"
            Regex("\\b(tea|coffee|snacks|food|lunch|dinner|breakfast|meals)\\b|டீ|சாப்பாடு").containsMatchIn(src) -> "Food"
            Regex("\\b(fuel|petrol|diesel|gas)\\b|பெட்ரோல்|டீசல்").containsMatchIn(src) -> "Fuel"
            Regex("\\b(repair|repairs|maintenance|service|servicing)\\b").containsMatchIn(src) -> "Maintenance"
            Regex("\\b(phone|mobile|internet|recharge|wifi|broadband)\\b").containsMatchIn(src) -> "Phone & Internet"
            Regex("\\b(packing|packaging|cover|covers|tape)\\b").containsMatchIn(src) -> "Packing"
            Regex("\\b(stationery|stationary|printing|xerox)\\b").containsMatchIn(src) -> "Stationery"
            t.isEmpty() -> null
            else -> NameMatcher.titleCase(t).take(40)
        }
    }

    private fun stockQuery(s: String, raw: String): AICommand? {
        var name: String? = null
        var matched = false
        val patterns = listOf(
            Regex("\\bhow (?:many|much) (.*?)\\s*(?:\\b(?:do|does|did|are|is|have|has|left|in stock|available|remaining|we|i)\\b.*)?$"),
            Regex("\\b(?:stock|quantity|qty|balance|count) (?:of|for) (.+)$"),
            Regex("^(?:do|does) (?:we|i) have (?:any )?(.+)$"),
            Regex("^(?:check|show|view|tell me|what is|whats) (?:the )?(?:current )?(?:stock )?(.+?) (?:stock|quantity|qty)$"),
            Regex("^(.+?) (?:stock|quantity|qty)(?: level| count| balance| position)?$"),
            Regex("^(.+?) how (?:many|much)(?: have| stock)?$"),
        )
        var cleaned = ""
        val drop = setOf("how", "many", "much", "check", "show", "what", "whats", "tell", "view", "any")
        for (p in patterns) {
            val m = p.find(s) ?: continue
            matched = true
            name = m.groupValues[1]
            cleaned = remainder(name, drop)
            if (cleaned.isNotEmpty()) break
        }
        if (!matched) return null
        val pronoun = cleaned.isEmpty() && (PRONOUN.containsMatchIn(s) || Regex("^how (many|much)\\b").containsMatchIn(s))
        if (cleaned.isEmpty() && !pronoun) return null
        return if (cleaned.isEmpty()) {
            cmd(Intent.STOCK_QUERY, 0.93, Entities(usesContext = true))
        } else {
            cmd(Intent.STOCK_QUERY, 0.95, Entities(productName = restoreCase(raw, cleaned)))
        }
    }

    private fun partyQuery(s: String, raw: String): AICommand? {
        if (Regex("^(?:show |list |view |display |get )?(?:all |my |our )?customers(?: list)?$").containsMatchIn(s)) return cmd(Intent.CUSTOMER_QUERY, 0.95)
        if (Regex("^(?:show |list |view |display |get )?(?:all |my |our )?(?:suppliers|vendors)(?: list)?$").containsMatchIn(s)) return cmd(Intent.SUPPLIER_QUERY, 0.95)
        val m = Regex("^(?:find|search|search for|show|get|lookup|look up|details of|details for|who is|open) (customer |supplier |vendor )?(.+)$").find(s) ?: return null
        val kind = m.groupValues[1].trim()
        val name = restoreCase(raw, cleanName(m.groupValues[2]))
        if (name.isEmpty()) return null
        return if (kind == "supplier" || kind == "vendor") cmd(Intent.SUPPLIER_QUERY, 0.93, Entities(supplierName = name))
        else cmd(Intent.CUSTOMER_QUERY, 0.93, Entities(customerName = name))
    }

    // ---------- helpers ----------

    private data class Price(val unit: Long?, val total: Long?, val rest: String)

    private fun cutPrice(s: String, allowTotal: Boolean = true): Price {
        for (re in listOf(PRICE_UNIT1, PRICE_UNIT2, PRICE_UNIT3)) {
            val m = re.find(s) ?: continue
            return Price(Money.parse(m.groupValues[1]), null, s.removeRange(m.range))
        }
        val m = PRICE_TOTAL.find(s)
        if (m != null) {
            return if (allowTotal) Price(null, Money.parse(m.groupValues[1]), s.removeRange(m.range))
            else Price(Money.parse(m.groupValues[1]), null, s.removeRange(m.range))
        }
        return Price(null, null, s)
    }

    private fun remainder(s: String, verbs: Set<String>, keepStock: Boolean = false): String =
        s.split(" ").filter { w ->
            w.isNotEmpty() && w !in verbs && (w !in FILLERS || (keepStock && w == "stock")) && w != "rupees"
        }.joinToString(" ").trim()

    private fun cleanName(s: String): String =
        s.replace(Regex("\\b(me|us|now|please|in total|totally|still|currently|the)\\b"), " ").replace(Regex("\\s+"), " ").trim()

    /** Uses the capitals the user typed when the phrase can be found in the original text. */
    fun restoreCase(raw: String, extracted: String): String {
        val ex = extracted.trim()
        if (ex.isEmpty()) return ""
        val lower = raw.lowercase()
        val idx = if (lower.length == raw.length) lower.indexOf(ex) else -1
        val piece = if (idx >= 0) raw.substring(idx, idx + ex.length) else ex
        return NameMatcher.titleCase(piece)
    }

    private fun build(intent: Intent, raw: String, name: String, pronoun: Boolean, base: Entities, needQty: Boolean): AICommand {
        val display = if (name.isEmpty()) null else restoreCase(raw, name)
        val useCtx = display == null && pronoun
        val missing = ArrayList<String>()
        if (display == null && !useCtx) missing.add("productName")
        if (needQty && base.quantityMilli == null) missing.add("quantity")
        return cmd(intent, 0.95, base.copy(productName = display, usesContext = useCtx), missing)
    }

    private fun cmd(intent: Intent, confidence: Double, e: Entities = Entities(), missing: List<String> = emptyList()): AICommand =
        AICommand(intent, confidence, intent.isWrite, e, missing, describe(intent, e))

    private fun unknown(guide: String? = null): AICommand =
        AICommand(Intent.UNKNOWN, if (guide != null) 0.9 else 0.3, false, Entities(note = guide), emptyList(), "")

    private fun describe(intent: Intent, e: Entities): String {
        val parts = ArrayList<String>()
        e.quantityMilli?.let { parts.add(Qty.format(it) + (e.unit?.let { u -> " $u" } ?: "")) }
        e.productName?.let { parts.add(it) }
        e.customerName?.let { parts.add("customer $it") }
        e.supplierName?.let { parts.add("supplier $it") }
        e.unitPricePaise?.let { parts.add("at ${Money.format(it)} each") }
        e.amountPaise?.let { parts.add(Money.format(it)) }
        e.expenseCategory?.let { parts.add(it) }
        return "I understood ${intent.name}" + if (parts.isEmpty()) "." else ": " + parts.joinToString(", ") + "."
    }
}
