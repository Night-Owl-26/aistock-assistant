package com.aistock.assistant.conversation

import com.aistock.assistant.ai.AICommand
import com.aistock.assistant.ai.AssistantBackend
import com.aistock.assistant.ai.AssistantEngine
import com.aistock.assistant.ai.AssistantReply
import com.aistock.assistant.ai.ChatTurn
import com.aistock.assistant.ai.Entities
import com.aistock.assistant.ai.Intent
import com.aistock.assistant.ai.PartyKind
import com.aistock.assistant.ai.PartyRef
import com.aistock.assistant.ai.Plan
import com.aistock.assistant.ai.ReplyKind
import com.aistock.assistant.ai.TxnSummary
import com.aistock.assistant.ai.llm.LlmClient
import com.aistock.assistant.ai.llm.LlmMessage
import com.aistock.assistant.ai.llm.LlmProblem
import com.aistock.assistant.ai.llm.ToolCall
import com.aistock.assistant.ai.llm.ToolParam
import com.aistock.assistant.ai.llm.ToolSpec
import com.aistock.assistant.utils.DateKey
import com.aistock.assistant.utils.DateRanges
import com.aistock.assistant.utils.Match
import com.aistock.assistant.utils.Money
import com.aistock.assistant.utils.NameMatcher
import com.aistock.assistant.utils.Qty
import com.aistock.assistant.utils.Units
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException

/**
 * Stocky: the conversation manager.
 *
 *   user text -> language model -> tool choice -> app tool -> real data -> model -> natural reply
 *
 * The model decides what to look up and what to propose. It can read through tools, but every change is only a
 * proposal that goes through [AssistantEngine]'s validation and the user's confirmation, and "done" is only ever
 * said by the app after the database confirms it. Without a model (offline, or no key) the on-device rules answer.
 */
class Stocky(
    private val backend: AssistantBackend,
    private val engine: AssistantEngine,
    private val llm: suspend () -> LlmClient?,
    private val businessName: suspend () -> String = { "" },
    private val symbol: () -> String = { "₹" },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val now: () -> Instant = { Instant.now() },
    private val online: () -> Boolean = { true },
    private val tamil: () -> Boolean = { false },
) {
    private val turns = ArrayList<ChatTurn>()
    private var summary = ""
    private var currentParty: String? = null
    private var currentPeriod: DateKey? = null

    /** "cloud" when the last reply came from the language model, "local" when the on-device rules answered. */
    var lastSource: String = "local"
        private set

    /** Why the language model could not answer last time, or null when it did. */
    var lastError: String? = null
        private set

    val awaitingConfirmation: Boolean get() = engine.awaitingConfirmation

    fun reset() {
        engine.reset(); turns.clear(); summary = ""; currentParty = null; currentPeriod = null
    }

    fun seed(history: List<ChatTurn>) {
        turns.clear(); turns.addAll(history.takeLast(MAX_TURNS)); engine.seed(history)
    }

    /** What the conversation is about, so it can be saved and restored with the chat. */
    fun exportContext(): Map<String, String> = buildMap {
        engine.contextProduct?.let { put("product", it) }
        currentParty?.let { put("party", it) }
        currentPeriod?.let { put("period", it.name) }
        if (summary.isNotBlank()) put("summary", summary)
    }

    fun importContext(ctx: Map<String, String>) {
        engine.contextProduct = ctx["product"]
        currentParty = ctx["party"]
        currentPeriod = ctx["period"]?.let { n -> DateKey.entries.firstOrNull { it.name == n } }
        summary = ctx["summary"].orEmpty()
    }

    suspend fun handle(input: String): AssistantReply {
        val text = input.trim()
        if (text.isEmpty()) return engine.handle(text)
        val client = try { llm() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        // A plain yes/no to a waiting confirmation never needs the model.
        val plainAnswer = engine.awaitingConfirmation && (engine.isYesWord(text) || engine.isNoWord(text))
        val reply = if (client == null || plainAnswer) {
            lastSource = if (client == null) "local" else lastSource
            engine.handle(text)
        } else {
            try {
                engine.commandText = text
                val r = converse(client, text)
                lastSource = "cloud"
                lastError = null
                r
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The service failed: the on-device rules still work for real commands,
                // but the owner is told why the conversation did not happen instead of getting a canned line.
                lastSource = "local"
                lastError = LlmProblem.explain(e, online(), tamil())
                val local = engine.handle(text)
                if (local.intent != Intent.UNKNOWN || local.kind == ReplyKind.CONFIRM) local
                else AssistantReply(lastError!!, ReplyKind.ERROR, Intent.UNKNOWN)
            }
        }
        remember(text, reply.text)
        return reply
    }

    suspend fun confirm(): AssistantReply = engine.confirm().also { remember(null, it.text) }
    suspend fun cancel(): AssistantReply = engine.cancel().also { remember(null, it.text) }
    suspend fun undo(token: String): AssistantReply = engine.undo(token).also { remember(null, it.text) }

    private fun remember(user: String?, assistant: String) {
        if (user != null) turns.add(ChatTurn("user", user))
        if (assistant.isNotBlank()) turns.add(ChatTurn("assistant", assistant))
        // Old turns are folded into a short summary instead of being sent to the model forever.
        while (turns.size > MAX_TURNS) {
            val old = turns.removeAt(0)
            summary = (summary + "\n" + (if (old.role == "user") "Owner: " else "Stocky: ") + old.text.replace("\n", " ").take(140)).takeLast(1600)
        }
    }

    // ------------------------------------------------------------------ the model loop

    private suspend fun converse(client: LlmClient, text: String): AssistantReply {
        val messages = ArrayList<LlmMessage>()
        messages.add(LlmMessage.System(systemPrompt()))
        for (t in turns) messages.add(if (t.role == "user") LlmMessage.User(t.text) else LlmMessage.Assistant(t.text))
        messages.add(LlmMessage.User(text))

        val turn = TurnState()
        var finalText = ""
        for (round in 0 until MAX_ROUNDS) {
            val reply = client.chat(messages, TOOLS)
            if (reply.toolCalls.isEmpty()) { finalText = reply.text.trim(); break }
            messages.add(LlmMessage.Assistant(reply.text, reply.toolCalls))
            for (call in reply.toolCalls.take(6)) {
                val result = try {
                    runTool(call, turn)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "ERROR: that could not be done. Nothing was changed."
                }
                messages.add(LlmMessage.ToolResult(call.id, call.name, result.take(6000)))
            }
            if (round == MAX_ROUNDS - 1) finalText = reply.text.trim()
        }

        // What the app itself verified always wins over what the model wrote.
        turn.executed?.let { return it }
        val card = engine.pendingReply
        if (card != null && turn.proposed) {
            return AssistantReply(finalText.ifBlank { card.text }, ReplyKind.CONFIRM, card.intent, lines = card.lines, dangerous = card.dangerous, spoken = finalText.ifBlank { card.text })
        }
        if (finalText.isBlank()) throw IllegalStateException("empty model reply")
        return AssistantReply(finalText, if (turn.choices.isNotEmpty()) ReplyKind.CLARIFY else ReplyKind.ANSWER, Intent.UNKNOWN, choices = turn.choices)
    }

    private class TurnState {
        var proposed = false
        var executed: AssistantReply? = null
        var choices: List<String> = emptyList()
    }

    private suspend fun systemPrompt(): String {
        val z = zone()
        val nowText = now().atZone(z).format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, h:mm a"))
        val sb = StringBuilder(PERSONA)
        sb.append("\n\nNOW: ").append(nowText).append(". Currency symbol: ").append(symbol()).append('.')
        val name = try { businessName() } catch (e: Exception) { "" }
        if (name.isNotBlank()) sb.append("\nBUSINESS: ").append(name)
        val ctx = ArrayList<String>()
        engine.contextProduct?.let { ctx.add("product being discussed = $it") }
        currentParty?.let { ctx.add("customer/supplier being discussed = $it") }
        currentPeriod?.let { ctx.add("period being discussed = ${it.name}") }
        if (ctx.isNotEmpty()) sb.append("\nCONTEXT: ").append(ctx.joinToString("; "))
        engine.pendingText?.let { sb.append("\nWAITING FOR THE OWNER'S YES/NO ON: ").append(it) }
        val sheets = try { backend.sheets() } catch (e: Exception) { emptyList() }
        if (sheets.isNotEmpty()) {
            sb.append("\nTHE OWNER'S WORKBOOK SHEETS (name | what it holds | rows | columns):")
            for (s in sheets.take(30)) sb.append("\n- ").append(s.name).append(" | ").append(s.purpose).append(" | ").append(s.rowCount).append(" | ").append(s.columns.take(14).joinToString(", "))
        }
        val aliases = try { backend.aliases() } catch (e: Exception) { emptyList() }
        if (aliases.isNotEmpty()) {
            sb.append("\nTHE OWNER'S OWN WORDS FOR PRODUCTS: ").append(aliases.take(60).joinToString("; ") { "\"${it.phrase}\" = ${it.productName}" })
        }
        if (summary.isNotBlank()) sb.append("\nEARLIER IN THIS CONVERSATION (summary):").append(summary)
        return sb.toString()
    }

    // ------------------------------------------------------------------ tools

    private fun money(p: Long) = Money.format(p, symbol())
    private fun qty(milli: Long, unit: String) = Qty.format(milli) + " " + unit.lowercase()
    private fun a(call: ToolCall, key: String): String? = call.args[key]?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) }
    private fun bool(call: ToolCall, key: String): Boolean? = a(call, key)?.lowercase()?.let { it == "true" || it == "yes" || it == "1" }

    private fun period(call: ToolCall): DateKey {
        val k = a(call, "period")?.uppercase()?.replace(' ', '_')
        val key = DateKey.entries.firstOrNull { it.name == k } ?: currentPeriod ?: DateKey.TODAY
        currentPeriod = key
        return key
    }

    /** "", "same", "it", "that" all mean the product the conversation is already about. */
    private fun productArg(call: ToolCall): Pair<String?, Boolean> {
        val v = a(call, "product")
        return if (v == null || v.lowercase() in SAME_WORDS) null to true else v to false
    }

    private suspend fun findParty(name: String, kinds: List<PartyKind>): List<PartyRef> {
        val out = ArrayList<PartyRef>()
        for (k in kinds) when (val m = NameMatcher.match(name, backend.parties(k)) { it.name }) {
            is Match.Exact -> out.add(m.item)
            is Match.Suggest -> out.add(m.item)
            is Match.Multiple -> out.addAll(m.items)
            Match.None -> {}
        }
        return out
    }

    private fun summaryText(label: String, s: TxnSummary, unpaidLabel: String): String {
        if (s.count == 0) return "$label: none recorded."
        val sb = StringBuilder("$label: ${s.count} entries, total ${money(s.totalPaise)}")
        if (s.unpaidPaise > 0) sb.append(", $unpaidLabel ${money(s.unpaidPaise)}")
        for (l in s.lines.take(12)) sb.append("\n- ${l.label} ${l.detail} ${money(l.amountPaise)}")
        return sb.toString()
    }

    private fun proposalResult(r: AssistantReply, turn: TurnState): String = when (r.kind) {
        ReplyKind.CONFIRM -> {
            turn.proposed = true
            "READY, WAITING FOR THE OWNER'S CONFIRMATION (nothing is saved yet): ${r.text}\n" +
                r.lines.joinToString("\n") { "${it.label}: ${it.value}" } + "\nAsk the owner to confirm in one short sentence, in their language. Do not say it is done."
        }
        ReplyKind.CLARIFY -> {
            turn.choices = r.choices
            "NEED MORE FROM THE OWNER: ${r.text}" + (if (r.choices.isNotEmpty()) " Options: " + r.choices.joinToString("; ") else "") + "\nAsk this one question."
        }
        ReplyKind.DONE -> "DONE (verified by the app): ${r.text}"
        ReplyKind.ANSWER -> r.text
        else -> "NOT POSSIBLE: ${r.text} Tell the owner plainly. Nothing was changed."
    }

    private suspend fun runTool(call: ToolCall, turn: TurnState): String {
        when (call.name) {
            // ---------------------------------------------------------- reading
            "find_products" -> {
                val q = a(call, "query")
                val all = backend.products()
                val aliasHit = q?.let { qq -> backend.aliases().firstOrNull { NameMatcher.key(it.phrase) == NameMatcher.key(qq) }?.productName }
                val hits = when {
                    q == null -> all
                    aliasHit != null -> all.filter { NameMatcher.key(it.name) == NameMatcher.key(aliasHit) }
                    else -> when (val m = NameMatcher.match(q, all) { it.name }) {
                        is Match.Exact -> listOf(m.item)
                        is Match.Suggest -> listOf(m.item)
                        is Match.Multiple -> m.items
                        Match.None -> all.filter { it.name.contains(q, ignoreCase = true) }
                    }
                }
                if (hits.isEmpty()) return "No product matches \"${q.orEmpty()}\". There are ${all.size} products in total."
                if (hits.size == 1) engine.contextProduct = hits[0].name
                return "${hits.size} product(s)" + (if (hits.size > 40) ", first 40 shown" else "") + " (name | stock | minimum | selling price | cost):\n" +
                    hits.sortedBy { it.name.lowercase() }.take(40).joinToString("\n") {
                        "- ${it.name} | ${qty(it.stockMilli, it.unit)}${if (it.isLow) " (LOW)" else ""} | ${qty(it.minMilli, it.unit)} | ${money(it.sellPaise)} | ${money(it.buyPaise)}"
                    }
            }
            "low_stock" -> {
                val low = backend.products().filter { it.isLow }.sortedBy { it.name.lowercase() }
                return if (low.isEmpty()) "No products are at or below their minimum stock." else "${low.size} product(s) low:\n" +
                    low.take(40).joinToString("\n") { "- ${it.name}: ${qty(it.stockMilli, it.unit)} (minimum ${qty(it.minMilli, it.unit)})" }
            }
            "business_summary" -> {
                val key = period(call)
                val w = DateRanges.range(key, zone(), now())
                val s = backend.sales(w.start, w.end); val pu = backend.purchases(w.start, w.end); val ex = backend.expenses(w.start, w.end)
                val st = backend.stockSummary()
                val recv = backend.parties(PartyKind.CUSTOMER).filter { it.balancePaise > 0 }.sumOf { it.balancePaise }
                val pay = backend.parties(PartyKind.SUPPLIER).filter { it.balancePaise > 0 }.sumOf { it.balancePaise }
                val sb = StringBuilder("Period ${key.name}: sales ${money(s.totalPaise)} (${s.count}), purchases ${money(pu.totalPaise)} (${pu.count}), expenses ${money(ex.totalPaise)} (${ex.count}).")
                if (key != DateKey.ALL && key != DateKey.TODAY) {
                    val prevKey = when (key) { DateKey.THIS_MONTH -> DateKey.LAST_MONTH; DateKey.THIS_WEEK -> DateKey.LAST_WEEK; else -> null }
                    if (prevKey != null) {
                        val pw = DateRanges.range(prevKey, zone(), now())
                        sb.append(" Previous period (${prevKey.name}) sales ${money(backend.sales(pw.start, pw.end).totalPaise)}.")
                    }
                }
                sb.append("\nStock: ${st.productCount} products worth ${money(st.valuePaise)} at cost, ${st.lowCount} low, ${st.outCount} out of stock.")
                sb.append("\nCustomers owe ${money(recv)}. Owed to suppliers ${money(pay)}.")
                if (s.lines.isNotEmpty()) sb.append("\nTop sellers: " + s.lines.take(5).joinToString("; ") { "${it.label} ${it.detail} ${money(it.amountPaise)}" })
                sb.append("\nProfit cannot be calculated exactly from this; do not invent it.")
                return sb.toString()
            }
            "get_sales", "get_purchases" -> {
                val sales = call.name == "get_sales"
                val key = period(call)
                val w = DateRanges.range(key, zone(), now())
                val who = a(call, if (sales) "customer" else "supplier")?.takeIf { it.lowercase() !in SAME_WORDS } ?: (if (a(call, if (sales) "customer" else "supplier") != null) currentParty else null)
                if (who != null) {
                    val found = findParty(who, listOf(if (sales) PartyKind.CUSTOMER else PartyKind.SUPPLIER))
                    if (found.isEmpty()) return "No ${if (sales) "customer" else "supplier"} matches \"$who\"."
                    if (found.size > 1) { turn.choices = found.map { it.name }; return "Several match \"$who\": " + found.joinToString("; ") { it.name } + ". Ask which one." }
                    currentParty = found[0].name
                    val s = if (sales) backend.partySales(found[0].id, w.start, w.end) else backend.partyPurchases(found[0].id, w.start, w.end)
                    return summaryText("${if (sales) "Sales to" else "Purchases from"} ${found[0].name}, ${key.name}", s, "unpaid")
                }
                val s = if (sales) backend.sales(w.start, w.end) else backend.purchases(w.start, w.end)
                return summaryText("${if (sales) "Sales" else "Purchases"}, ${key.name}", s, "unpaid")
            }
            "get_expenses" -> {
                val key = period(call)
                val w = DateRanges.range(key, zone(), now())
                return summaryText("Expenses, ${key.name}", backend.expenses(w.start, w.end), "")
            }
            "find_party" -> {
                val q = a(call, "name")?.takeIf { it.lowercase() !in SAME_WORDS } ?: currentParty
                val kinds = when (a(call, "kind")?.lowercase()) { "customer" -> listOf(PartyKind.CUSTOMER); "supplier" -> listOf(PartyKind.SUPPLIER); else -> listOf(PartyKind.CUSTOMER, PartyKind.SUPPLIER) }
                if (q == null) {
                    val sb = StringBuilder()
                    for (k in kinds) {
                        val list = backend.parties(k).sortedByDescending { it.balancePaise }
                        sb.append("${list.size} ${if (k == PartyKind.CUSTOMER) "customers (name | owes you)" else "suppliers (name | you owe)"}:\n")
                        sb.append(list.take(30).joinToString("\n") { "- ${it.name} | ${money(it.balancePaise)}" }).append('\n')
                    }
                    return sb.toString()
                }
                val found = findParty(q, kinds)
                if (found.isEmpty()) return "No customer or supplier matches \"$q\"."
                if (found.size == 1) currentParty = found[0].name
                return found.joinToString("\n") {
                    if (it.kind == PartyKind.CUSTOMER) "Customer ${it.name}${if (it.phone.isNotBlank()) ", phone ${it.phone}" else ""}: owes you ${money(it.balancePaise)}"
                    else "Supplier ${it.name}${if (it.phone.isNotBlank()) ", phone ${it.phone}" else ""}: you owe ${money(it.balancePaise)}"
                }
            }
            "recent_activity" -> {
                val who = a(call, "party")?.takeIf { it.lowercase() !in SAME_WORDS } ?: (if (a(call, "party") != null) currentParty else null)
                val limit = a(call, "limit")?.toDoubleOrNull()?.toInt()?.coerceIn(1, 30) ?: 10
                var id: String? = null
                if (who != null) {
                    val found = findParty(who, listOf(PartyKind.CUSTOMER, PartyKind.SUPPLIER))
                    if (found.isEmpty()) return "No customer or supplier matches \"$who\"."
                    if (found.size > 1 && found.none { NameMatcher.key(it.name) == NameMatcher.key(who) }) { turn.choices = found.map { it.name }; return "Several match: " + found.joinToString("; ") { it.name } }
                    val pick = found.firstOrNull { NameMatcher.key(it.name) == NameMatcher.key(who) } ?: found[0]
                    id = pick.id; currentParty = pick.name
                }
                val rows = backend.recentActivity(limit, id)
                return if (rows.isEmpty()) "Nothing recorded${if (who != null) " for ${currentParty}" else ""} yet." else rows.joinToString("\n") { "- $it" }
            }
            "list_sheets" -> {
                val sheets = backend.sheets()
                return if (sheets.isEmpty()) "The owner has not added a workbook yet. They can upload one from More > Workbook." else
                    sheets.joinToString("\n") { "- ${it.name} | ${it.purpose} | ${it.rowCount} rows | columns: ${it.columns.joinToString(", ")}" }
            }
            "search_sheet" -> {
                val sheet = a(call, "sheet") ?: return "Say which sheet."
                val limit = a(call, "limit")?.toDoubleOrNull()?.toInt()?.coerceIn(1, 40) ?: 15
                val rows = backend.sheetRows(sheet, a(call, "query").orEmpty(), limit)
                if (rows.isEmpty()) return "No sheet called \"$sheet\"."
                if (rows.size == 1) return "No rows match in ${sheet}. Columns: " + rows[0].joinToString(" | ")
                return rows.joinToString("\n") { it.joinToString(" | ") }
            }

            // ---------------------------------------------------------- proposing changes (never saved here)
            "propose_stock_in", "propose_stock_out", "propose_stock_set", "propose_sale" -> {
                val (product, same) = productArg(call)
                val qtyKey = if (call.name == "propose_stock_set") "counted_quantity" else "quantity"
                val e = Entities(
                    productName = product, usesContext = same,
                    quantityMilli = a(call, qtyKey)?.let { Qty.parse(it) },
                    unit = a(call, "unit")?.let { Units.normalize(it) },
                    unitPricePaise = a(call, "unit_price")?.let { Money.parse(it) },
                    totalPaise = a(call, "total")?.let { Money.parse(it) },
                    supplierName = a(call, "supplier"), customerName = a(call, "customer"),
                    paid = bool(call, "paid"),
                    reason = if (call.name == "propose_stock_out") (a(call, "reason")?.uppercase()?.takeIf { it in setOf("DAMAGE", "RETURN_OUT", "OTHER") } ?: "OTHER") else null,
                    note = a(call, "note")?.take(100),
                )
                val intent = when (call.name) {
                    "propose_stock_in" -> Intent.STOCK_IN
                    "propose_stock_out" -> Intent.STOCK_OUT
                    "propose_stock_set" -> Intent.STOCK_ADJUST
                    else -> Intent.CREATE_SALE
                }
                if (e.quantityMilli == null) return "NEED MORE FROM THE OWNER: the quantity."
                if (product == null && engine.contextProduct == null) return "NEED MORE FROM THE OWNER: which product."
                a(call, "customer")?.let { currentParty = it }; a(call, "supplier")?.let { currentParty = it }
                return proposalResult(engine.propose(AICommand(intent, 0.99, true, e, source = "cloud")), turn)
            }
            "propose_expense" -> {
                val e = Entities(amountPaise = a(call, "amount")?.let { Money.parse(it) }, expenseCategory = a(call, "category")?.let { NameMatcher.titleCase(it).take(40) }, note = a(call, "note")?.take(100))
                if (e.amountPaise == null) return "NEED MORE FROM THE OWNER: the amount."
                if (e.expenseCategory == null) return "NEED MORE FROM THE OWNER: what the expense was for."
                return proposalResult(engine.propose(AICommand(Intent.CREATE_EXPENSE, 0.99, true, e, source = "cloud")), turn)
            }
            "propose_payment" -> {
                val who = a(call, "party")?.takeIf { it.lowercase() !in SAME_WORDS } ?: currentParty
                val hint = when (a(call, "direction")?.lowercase()) { "received" -> PartyKind.CUSTOMER; "paid" -> PartyKind.SUPPLIER; else -> null }
                who?.let { currentParty = it }
                return proposalResult(engine.proposePayment(who, a(call, "amount")?.let { Money.parse(it) }, hint), turn)
            }
            "propose_new_product" -> {
                val e = Entities(
                    productName = a(call, "name")?.let { NameMatcher.titleCase(it) }, unit = a(call, "unit")?.let { Units.custom(it) },
                    quantityMilli = a(call, "opening_stock")?.let { Qty.parse(it) }, sellPaise = a(call, "selling_price")?.let { Money.parse(it) },
                    buyPaise = a(call, "purchase_price")?.let { Money.parse(it) }, minMilli = a(call, "minimum_stock")?.let { Qty.parse(it) },
                )
                if (e.productName == null) return "NEED MORE FROM THE OWNER: the product name."
                return proposalResult(engine.propose(AICommand(Intent.ADD_PRODUCT, 0.99, true, e, source = "cloud")), turn)
            }
            "propose_update_product" -> {
                val (product, same) = productArg(call)
                val e = Entities(productName = product, usesContext = same, sellPaise = a(call, "selling_price")?.let { Money.parse(it) }, buyPaise = a(call, "purchase_price")?.let { Money.parse(it) }, minMilli = a(call, "minimum_stock")?.let { Qty.parse(it) })
                if (e.sellPaise == null && e.buyPaise == null && e.minMilli == null) return "NEED MORE FROM THE OWNER: the new price or minimum stock."
                return proposalResult(engine.propose(AICommand(Intent.UPDATE_PRODUCT, 0.99, true, e, source = "cloud")), turn)
            }
            "propose_new_party" -> {
                val name = a(call, "name")?.let { NameMatcher.titleCase(it) } ?: return "NEED MORE FROM THE OWNER: the name."
                val supplier = a(call, "kind")?.lowercase() == "supplier"
                val phone = a(call, "phone")?.filter { it.isDigit() || it == '+' }?.takeIf { it.length in 5..16 }
                val e = if (supplier) Entities(supplierName = name, phone = phone) else Entities(customerName = name, phone = phone)
                currentParty = name
                return proposalResult(engine.propose(AICommand(if (supplier) Intent.CREATE_SUPPLIER else Intent.CREATE_CUSTOMER, 0.99, true, e, source = "cloud")), turn)
            }
            "propose_undo" -> return proposalResult(engine.proposeUndo(), turn)
            "propose_remember_name" -> {
                val phrase = a(call, "phrase") ?: return "NEED MORE FROM THE OWNER: the word they use."
                val product = a(call, "product")?.takeIf { it.lowercase() !in SAME_WORDS } ?: engine.contextProduct ?: return "NEED MORE FROM THE OWNER: which product it means."
                return proposalResult(engine.proposeAlias(phrase, product), turn)
            }
            "propose_create_sheet" -> {
                val name = a(call, "name") ?: return "NEED MORE FROM THE OWNER: a name for the sheet."
                val cols = a(call, "columns")?.split(',', ';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                return proposalResult(engine.proposeSheet(Plan.CreateSheet(NameMatcher.titleCase(name), cols)), turn)
            }
            "propose_add_column" -> {
                val sheet = a(call, "sheet") ?: return "NEED MORE FROM THE OWNER: which sheet."
                val col = a(call, "column") ?: return "NEED MORE FROM THE OWNER: the column name."
                return proposalResult(engine.proposeSheet(Plan.AddColumn(sheet, col)), turn)
            }
            "propose_add_row" -> {
                val sheet = a(call, "sheet") ?: return "NEED MORE FROM THE OWNER: which sheet."
                val values = LinkedHashMap<String, String>()
                for (part in a(call, "values").orEmpty().split(';')) {
                    val i = part.indexOf('=')
                    if (i > 0) values[part.substring(0, i).trim()] = part.substring(i + 1).trim()
                }
                if (values.isEmpty()) return "Give the values as Column=value; Column=value using this sheet's column names."
                return proposalResult(engine.proposeSheet(Plan.AddRow(sheet, values)), turn)
            }
            "confirm_pending" -> {
                if (!engine.awaitingConfirmation) return "Nothing is waiting for confirmation."
                val r = engine.confirm()
                turn.executed = r
                return if (r.kind == ReplyKind.DONE) "DONE (verified by the app): ${r.text}" else "FAILED, nothing was changed: ${r.text}"
            }
            "cancel_pending" -> {
                if (!engine.awaitingConfirmation) return "Nothing is waiting for confirmation."
                turn.executed = engine.cancel()
                return "Cancelled. Nothing was changed."
            }
            else -> return "Unknown tool."
        }
    }

    companion object {
        private const val MAX_TURNS = 14
        private const val MAX_ROUNDS = 6
        private val SAME_WORDS = setOf("same", "it", "that", "this", "same one", "same product", "that one", "them", "adhu", "idhu", "adhe", "அது", "இது", "they", "he", "she", "avanga", "avar")

        private const val PERIOD = "TODAY, YESTERDAY, THIS_WEEK, LAST_WEEK, THIS_MONTH, LAST_MONTH or ALL. Leave empty to keep the period already being discussed."
        private fun s(n: String, d: String, req: Boolean = false) = ToolParam(n, "string", d, req)
        private fun n(n: String, d: String, req: Boolean = false) = ToolParam(n, "number", d, req)
        private const val PRODUCT = "Product name as the owner said it. Leave empty to mean the product already being discussed."

        val PERSONA = """
You are Stocky, an intelligent, friendly business assistant inside the AIStock app. You help the owner of a small Indian business understand and run it by simply talking.

LANGUAGE: The owner speaks English, Tamil, Tanglish (Tamil in English letters) or a mix, often in incomplete sentences. Understand all of it. Reply in the same language and style they just used: if they write Tanglish, reply in natural Tanglish ("Blue Cotton currently 486 metres stock-la irukku."); if Tamil script, reply in Tamil; if English, English. Never sound machine-translated. Keep product, customer and unit words as the owner says them.

DATA: You know nothing about this business except what the tools return. For any stock, sales, purchase, expense, balance, customer, supplier or workbook question, call a tool first and use only its numbers. Never invent a quantity, amount, name, product or transaction. If the tools do not have it, say you don't have that information. If several things match, say so and ask which one.

CONTEXT: Remember the conversation. Resolve "it", "that", "same one", "that customer", "adhu", "idhu", "avanga", "அது", "இது", "அந்த customer" from what was just discussed. A short follow-up like "Kumar Traders?" or "yesterday?" continues the previous question with that change.

CHANGES: You cannot change anything yourself. To add or remove stock, record a sale, purchase, expense or payment, create a product, customer or supplier, undo something, remember the owner's own word for a product, or edit the workbook, call the matching propose_ tool. The app then shows the owner a confirmation. Ask them to confirm in one short sentence. When they agree ("yes", "seri", "pannidu", "ok", "ஆமா"), call confirm_pending. When they decline, call cancel_pending. If they correct you ("actually 30", "5 illa 8"), call the propose_ tool again with the corrected values: it replaces the earlier proposal, so nothing is added twice. Never say something was saved, added, sold or updated unless a tool result says DONE. If a tool says it is not possible or failed, say so plainly and that nothing was changed.

QUESTIONS: Ask only what is needed, one question at a time. Do not ask for confirmation on questions that only read data. Do not invent prices or other financial details for new items: ask.

STYLE: Short, natural and warm, like a helpful person at the shop, not a program. Simple answers in one or two sentences. Use short lines or "• " bullets for lists. No markdown, no JSON, and never mention tools, functions or databases. Greet naturally and differently each time, using the time of day or what is going on in the business if useful.
""".trim()

        val TOOLS: List<ToolSpec> = listOf(
            ToolSpec("find_products", "Look up products and their current stock, minimum, selling price and cost. Empty query lists all products.", listOf(s("query", "Product name, part of a name, or the owner's own word for it."))),
            ToolSpec("low_stock", "List products at or below their minimum stock."),
            ToolSpec("business_summary", "Sales, purchases, expenses, stock value, money owed by customers and to suppliers, and top sellers for a period. Use for 'how is my business doing'.", listOf(s("period", PERIOD))),
            ToolSpec("get_sales", "Sales total and what was sold in a period, optionally for one customer.", listOf(s("period", PERIOD), s("customer", "Customer name, or 'same' for the one being discussed."))),
            ToolSpec("get_purchases", "Purchase total and what was bought in a period, optionally from one supplier.", listOf(s("period", PERIOD), s("supplier", "Supplier name, or 'same'."))),
            ToolSpec("get_expenses", "Expenses in a period by category.", listOf(s("period", PERIOD))),
            ToolSpec("find_party", "Look up a customer or supplier and their balance. Empty name lists them all with balances.", listOf(s("name", "Name, or 'same' for the one being discussed."), ToolParam("kind", "string", "customer or supplier, if known.", options = listOf("customer", "supplier")))),
            ToolSpec("recent_activity", "The latest sales, purchases, payments, expenses and stock movements, newest first, optionally for one customer or supplier. Use for 'last purchase', 'last 10 transactions', 'what did I add today'.", listOf(s("party", "Customer or supplier name, or 'same'."), n("limit", "How many, up to 30."))),
            ToolSpec("list_sheets", "List the sheets of the owner's Excel workbook with their columns."),
            ToolSpec("search_sheet", "Read rows from one workbook sheet that contain a word. Empty query returns the first rows.", listOf(s("sheet", "Sheet name.", true), s("query", "Word to look for."), n("limit", "How many rows, up to 40."))),
            ToolSpec("propose_stock_in", "Propose adding stock that arrived. If the product does not exist the app offers to create it.", listOf(s("product", PRODUCT), n("quantity", "How many.", true), s("unit", "Unit if the owner said one (pieces, kg, meter, box ...)."), s("supplier", "Supplier, if said."), n("unit_price", "Price per unit in rupees, if said."), n("total", "Total amount in rupees, if said instead of a unit price."), ToolParam("paid", "boolean", "true if already paid, false if on credit, if said."))),
            ToolSpec("propose_stock_out", "Propose removing stock that was damaged, lost, used or returned to a supplier (not a sale).", listOf(s("product", PRODUCT), n("quantity", "How many.", true), s("unit", "Unit if said."), ToolParam("reason", "string", "Why.", options = listOf("DAMAGE", "RETURN_OUT", "OTHER")), s("note", "Short note in the owner's words."))),
            ToolSpec("propose_stock_set", "Propose setting the stock to a counted quantity (stock taking / correction).", listOf(s("product", PRODUCT), n("counted_quantity", "The actual quantity now.", true), s("unit", "Unit if said."))),
            ToolSpec("propose_sale", "Propose recording a sale. Uses the product's selling price unless a price is given.", listOf(s("product", PRODUCT), n("quantity", "How many.", true), s("unit", "Unit if said."), s("customer", "Customer, if said."), n("unit_price", "Price per unit in rupees, if said."), n("total", "Total in rupees, if said."), ToolParam("paid", "boolean", "true for cash/paid, false for credit, if said."))),
            ToolSpec("propose_expense", "Propose recording a business expense.", listOf(s("category", "What it was for, e.g. Transport, Rent, Salary.", true), n("amount", "Amount in rupees.", true), s("note", "Short note."))),
            ToolSpec("propose_payment", "Propose recording money received from a customer or paid to a supplier.", listOf(s("party", "Customer or supplier name, or 'same'.", true), n("amount", "Amount in rupees.", true), ToolParam("direction", "string", "received = from a customer, paid = to a supplier. Leave empty if unclear.", options = listOf("received", "paid")))),
            ToolSpec("propose_new_product", "Propose creating a new product. Only include prices and minimum stock the owner actually gave.", listOf(s("name", "Product name.", true), s("unit", "Unit."), n("opening_stock", "Opening quantity."), n("selling_price", "Rupees."), n("purchase_price", "Rupees."), n("minimum_stock", "Reorder level."))),
            ToolSpec("propose_update_product", "Propose changing a product's selling price, purchase price or minimum stock.", listOf(s("product", PRODUCT), n("selling_price", "Rupees."), n("purchase_price", "Rupees."), n("minimum_stock", "Reorder level."))),
            ToolSpec("propose_new_party", "Propose adding a customer or supplier.", listOf(ToolParam("kind", "string", "customer or supplier.", true, listOf("customer", "supplier")), s("name", "Name.", true), s("phone", "Phone number, if given."))),
            ToolSpec("propose_undo", "Propose reversing the most recent change made in this conversation."),
            ToolSpec("propose_remember_name", "Propose remembering that a word the owner uses means a particular product.", listOf(s("phrase", "The owner's word or short name.", true), s("product", "The product it means. Empty for the product being discussed."))),
            ToolSpec("propose_create_sheet", "Propose creating a new sheet in the owner's workbook.", listOf(s("name", "Sheet name.", true), s("columns", "Column names separated by commas. Leave empty for Date, Product, Quantity, Reason."))),
            ToolSpec("propose_add_column", "Propose adding a column to an existing workbook sheet. Existing data is kept.", listOf(s("sheet", "Sheet name or what it holds (e.g. customer).", true), s("column", "New column name.", true))),
            ToolSpec("propose_add_row", "Propose adding one row to a workbook sheet.", listOf(s("sheet", "Sheet name.", true), s("values", "Column=value pairs separated by semicolons, using that sheet's exact column names.", true))),
            ToolSpec("confirm_pending", "The owner agreed to the waiting proposal: carry it out. The result says whether it really worked."),
            ToolSpec("cancel_pending", "The owner declined the waiting proposal: drop it."),
        )
    }
}
