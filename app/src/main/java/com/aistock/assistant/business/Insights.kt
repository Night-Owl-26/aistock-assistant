package com.aistock.assistant.business

import com.aistock.assistant.utils.Money
import com.aistock.assistant.utils.Qty

data class ProductSale(val name: String, val unit: String, val qtyMilli: Long, val totalPaise: Long)

/** Real numbers for one period and the period just before it, gathered by the backend. */
data class InsightsInput(
    val now: Long,
    val periodDays: Int,
    val salesNow: Long, val salesPrev: Long, val billsNow: Int,
    val expensesNow: Long, val expensesPrev: Long,
    val productSalesNow: List<ProductSale>,
    val products: List<ProductActivity>,
    val openWork: List<WorkRef>,
    val pendingFollowups: List<FollowupRef>,
    val expiring: List<BatchInfo>,
)

/**
 * One suggestion. [fact] is true for counted figures and false for estimates; [evidence] says what it is based on,
 * so the owner can check it. Nothing here is guessed: with no data there is simply no insight.
 */
data class Insight(val kind: String, val priority: Int, val text: String, val evidence: String, val fact: Boolean = true)

/** Turns the business records into short, checkable suggestions. Pure arithmetic, no AI. */
object Insights {
    private const val DAY = 86_400_000L

    fun build(i: InsightsInput, symbol: String = "₹"): List<Insight> {
        val out = ArrayList<Insight>()
        fun money(p: Long) = Money.format(p, symbol)
        fun pct(now: Long, prev: Long) = if (prev <= 0) null else ((now - prev) * 100.0 / prev)
        val period = if (i.periodDays == 1) "today" else "the last ${i.periodDays} days"
        val prevPeriod = if (i.periodDays == 1) "yesterday" else "the ${i.periodDays} days before"
        val byName = i.products.associateBy { it.name.lowercase() }

        // 1. Sales against the same length of time just before.
        if (i.salesNow > 0 || i.salesPrev > 0) {
            val p = pct(i.salesNow, i.salesPrev)
            val change = when {
                p == null -> ""
                p >= 0.5 -> ", up ${"%.0f".format(p)}% from ${money(i.salesPrev)}"
                p <= -0.5 -> ", down ${"%.0f".format(-p)}% from ${money(i.salesPrev)}"
                else -> ", the same as $prevPeriod"
            }
            out.add(Insight("sales", 1, "Sales in $period: ${money(i.salesNow)} from ${i.billsNow} bills$change.", "Saved sales bills, cancelled bills left out."))
        }

        // 2. Margin, only as an estimate and only where the cost is known.
        if (i.productSalesNow.isNotEmpty()) {
            var revenue = 0L; var cost = 0L; var unknown = 0
            for (s in i.productSalesNow) {
                val buy = byName[s.name.lowercase()]?.buyPaise ?: 0L
                if (buy <= 0) { unknown++; continue }
                revenue += s.totalPaise
                cost += (s.qtyMilli * buy + 500) / 1000
            }
            if (revenue > 0) {
                val gross = revenue - cost
                val net = gross - i.expensesNow
                val note = if (unknown > 0) " $unknown product(s) have no cost price, so they are left out." else ""
                out.add(Insight("margin", 2,
                    "Estimated gross margin in $period: ${money(gross)} on ${money(revenue)} of sales; after ${money(i.expensesNow)} expenses about ${money(net)}.$note",
                    "Estimate: uses each product's current purchase price, not the price of the exact stock sold.", fact = false))
            } else if (unknown > 0) {
                out.add(Insight("margin", 4, "Profit cannot be worked out yet: the products sold have no purchase price.", "Add purchase prices to see margins."))
            }
        }

        // 3. Fast movers.
        val fast = i.productSalesNow.filter { it.qtyMilli > 0 }.sortedByDescending { it.qtyMilli }.take(3)
        if (fast.isNotEmpty()) out.add(Insight("fast", 3, "Selling most in $period: " + fast.joinToString(", ") { "${it.name} (${Qty.format(it.qtyMilli)} ${it.unit.lowercase()})" } + ".", "Quantity sold."))

        // 4. Low stock with a reorder suggestion based on the real selling rate.
        val sold = i.productSalesNow.groupBy { it.name.lowercase() }.mapValues { e -> e.value.sumOf { it.qtyMilli } }
        val low = i.products.filter { it.minMilli > 0 && it.stockMilli <= it.minMilli }.sortedBy { it.stockMilli - it.minMilli }
        if (low.isNotEmpty()) {
            val lines = low.take(5).map { p ->
                val rate = (sold[p.name.lowercase()] ?: 0L).toDouble() / i.periodDays.coerceAtLeast(1)
                val suggest = if (rate > 0) ((rate * 14 - p.stockMilli).coerceAtLeast(0.0) / 1000.0) else null
                "${p.name} ${Qty.format(p.stockMilli)} ${p.unit.lowercase()} left" + (suggest?.takeIf { it >= 1 }?.let { " (about ${"%.0f".format(it)} more covers 2 weeks)" } ?: "")
            }
            out.add(Insight("low_stock", 1, "Low stock: " + lines.joinToString("; ") + ".", "At or below the minimum you set; the reorder amount uses this period's sales rate."))
        }

        // 5. Slow movers: in stock, not sold for 30 days.
        val slow = i.products.filter { it.stockMilli > 0 && it.createdAt < i.now - 30 * DAY && (it.lastSaleAt == null || it.lastSaleAt < i.now - 30 * DAY) }
            .sortedByDescending { (it.stockMilli * it.buyPaise) / 1000 }.take(5)
        if (slow.isNotEmpty()) out.add(Insight("slow", 4, "Not sold in 30 days: " + slow.joinToString(", ") { it.name } + ".", "No sales bill for these in the last 30 days while they are in stock."))

        // 6. Selling at or below cost.
        val loss = i.products.filter { it.buyPaise > 0 && it.sellPaise in 1..it.buyPaise }.take(5)
        if (loss.isNotEmpty()) out.add(Insight("margin_low", 2, "Selling price is at or below cost for: " + loss.joinToString(", ") { "${it.name} (sell ${money(it.sellPaise)}, cost ${money(it.buyPaise)})" } + ".", "Product prices as saved."))

        // 7. Expenses jumping.
        val ep = pct(i.expensesNow, i.expensesPrev)
        if (ep != null && ep > 30 && i.expensesNow - i.expensesPrev > 50_000) {
            out.add(Insight("expenses", 2, "Expenses in $period are ${money(i.expensesNow)}, ${"%.0f".format(ep)}% more than $prevPeriod (${money(i.expensesPrev)}).", "Saved expenses."))
        }

        // 8. Gaps in the records.
        val lastSale = i.products.mapNotNull { it.lastSaleAt }.maxOrNull()
        if (lastSale != null && i.now - lastSale > 3 * DAY && i.salesPrev > 0) {
            out.add(Insight("missing", 2, "No sale has been recorded for ${(i.now - lastSale) / DAY} days. If the shop was open, some bills may be missing.", "Date of the latest sales bill."))
        }

        // 9. Work past its due date and due today.
        val overdue = i.openWork.filter { it.dueAt != null && it.dueAt < i.now }.sortedBy { it.dueAt }
        if (overdue.isNotEmpty()) out.add(Insight("overdue_work", 1, "${overdue.size} overdue: " + overdue.take(3).joinToString("; ") { "${it.number} ${it.title}" + (it.staffName?.let { s -> " ($s)" } ?: "") } + ".", "Open items whose due date has passed."))
        val endOfDay = i.now - Math.floorMod(i.now + 19_800_000L, DAY) + DAY
        val today = i.openWork.filter { it.dueAt != null && it.dueAt >= i.now && it.dueAt < endOfDay }
        if (today.isNotEmpty()) out.add(Insight("due_today", 2, "Due today: " + today.take(4).joinToString("; ") { "${it.number} ${it.title}" } + ".", "Open items due before midnight."))

        // 10. Follow-ups.
        val late = i.pendingFollowups.filter { it.dueAt < i.now }
        if (late.isNotEmpty()) out.add(Insight("followups", 1, "${late.size} follow-up(s) pending: " + late.take(3).joinToString("; ") { "${it.kind.lowercase()} ${it.personName}" + (if (it.note.isNotBlank()) " – ${it.note.take(40)}" else "") } + ".", "Follow-ups past their time and not marked done."))

        // 11. Expiry.
        if (i.expiring.isNotEmpty()) out.add(Insight("expiry", 1, "Expiring within a week: " + i.expiring.take(5).joinToString(", ") { "${it.productName} ${Qty.format(it.quantityMilli)} ${it.unit.lowercase()}" + (if (it.batchNo.isNotBlank()) " (batch ${it.batchNo})" else "") } + ".", "Batches with an expiry date in the next 7 days."))

        return out.sortedBy { it.priority }
    }

    /** Text for Stocky: facts and estimates are labelled so the model cannot present an estimate as a fact. */
    fun forModel(list: List<Insight>): String =
        if (list.isEmpty()) "No records yet for this period, so there is nothing to report."
        else list.joinToString("\n") { (if (it.fact) "FACT: " else "ESTIMATE: ") + it.text + " [based on: " + it.evidence + "]" }
}
