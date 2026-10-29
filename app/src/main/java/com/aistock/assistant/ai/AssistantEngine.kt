package com.aistock.assistant.ai

import com.aistock.assistant.utils.DateKey
import com.aistock.assistant.utils.DateRanges
import com.aistock.assistant.utils.Match
import com.aistock.assistant.utils.Money
import com.aistock.assistant.utils.NameMatcher
import com.aistock.assistant.utils.Qty
import com.aistock.assistant.utils.Units
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/**
 * The conversation brain. Voice and text both end up here.
 *
 * sentence -> AIProvider -> AICommand -> validation -> clarification -> confirmation -> AssistantBackend
 *
 * The AI only proposes. Nothing is written until the user confirms, and the backend's business rules have the last word.
 */
class AssistantEngine(
    private val backend: AssistantBackend,
    private val provider: AIProvider,
    private val tamil: () -> Boolean = { false },
    private val symbol: () -> String = { "₹" },
    private val allowNegativeStock: suspend () -> Boolean = { false },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val now: () -> Instant = { Instant.now() },
    /** True when sensitive changes must be approved with the phone lock or app PIN, not just a spoken or typed "yes". */
    private val ownerCheck: () -> Boolean = { false },
) {
    private val p: Phrases get() = Phrases(tamil())

    private class Draft(cmd: AICommand) {
        var intent: Intent = cmd.intent
        var e: Entities = cmd.entities
        val json: String = cmd.toJson()
        val summary: String = cmd.response
        var product: ProductRef? = null
        var newProductName: String? = null
        var productDone = false
        var party: PartyRef? = null
        var newPartyName: String? = null
        var partyDone = false
        var needsRecordType = LocalParser.FIELD_RECORD_TYPE in cmd.missingFields
    }

    private sealed class Pending(val draft: Draft) {
        class Field(d: Draft, val field: String, val question: AssistantReply) : Pending(d)
        class DidYouMeanProduct(d: Draft, val candidate: ProductRef) : Pending(d)
        class DidYouMeanParty(d: Draft, val candidate: PartyRef) : Pending(d)
        class ChooseProduct(d: Draft, val options: List<ProductRef>) : Pending(d)
        class ChooseParty(d: Draft, val options: List<PartyRef>) : Pending(d)
        class Confirm(d: Draft, val plan: Plan, val question: AssistantReply) : Pending(d)
        class LowConfidence(d: Draft) : Pending(d)
    }

    private var pending: Pending? = null
    private var lastProduct: String? = null
    private val history = ArrayList<ChatTurn>()

    /** The last reversible action of this conversation (undo token and what it did), for "undo that". */
    var lastUndo: Pair<String, String>? = null
        private set
    /** What the user actually said for the action being prepared; kept for the audit trail. */
    var commandText: String = ""
    /** The product the conversation is currently about ("it", "the same one", "adhu"). */
    var contextProduct: String?
        get() = lastProduct
        set(value) { lastProduct = value }
    /** The question waiting for a yes/no, if any. */
    val pendingText: String? get() = (pending as? Pending.Confirm)?.question?.text
    val pendingReply: AssistantReply? get() = (pending as? Pending.Confirm)?.question
    fun isYesWord(text: String) = isYes(text)
    fun isNoWord(text: String) = isNo(text)

    val awaitingConfirmation: Boolean get() = pending is Pending.Confirm

    /** The waiting change moves money or makes a big correction. */
    val pendingSensitive: Boolean get() = (pending as? Pending.Confirm)?.let { it.question.dangerous || it.plan is Plan.Payment || it.plan is Plan.UndoLast } ?: false

    /** A "yes" in words is not enough for the waiting change: the owner must press Confirm and prove it is them. */
    val needsOwnerCheck: Boolean get() = pendingSensitive && ownerCheck()

    private fun verifyFirst(pend: Pending.Confirm) = pend.question.copy(text = p.verifyFirst(), spoken = p.verifyFirst())
    val awaitingAnswer: Boolean get() = pending != null

    fun reset() {
        pending = null
        lastProduct = null
        lastUndo = null
        history.clear()
    }

    /** Restores earlier turns (for example when a saved conversation is reopened). */
    fun seed(turns: List<ChatTurn>) {
        history.clear()
        history.addAll(turns.takeLast(12))
    }

    suspend fun handle(input: String): AssistantReply {
        val text = input.trim()
        if (text.isEmpty()) return AssistantReply(p.didntCatch(), ReplyKind.ERROR)
        history.add(ChatTurn("user", text))
        commandText = text
        val r = guard { route(text) }
        history.add(ChatTurn("assistant", r.text))
        while (history.size > 12) history.removeAt(0)
        return r
    }

    suspend fun confirm(): AssistantReply {
        val pend = pending as? Pending.Confirm ?: return AssistantReply(p.nothingToConfirm(), ReplyKind.ERROR)
        return guard { execute(pend) }
    }

    suspend fun cancel(): AssistantReply = guard { cancelPending() }

    // ------------------------------------------------------------------ proposals from a conversational AI model
    // A model never writes. It proposes; the same validation and confirmation as everywhere else then applies.

    /** Validates a structured command and prepares its confirmation. Questions that need no change are answered directly. */
    suspend fun propose(cmd: AICommand): AssistantReply = guard {
        pending = null
        val v = CommandValidator.validate(cmd)
        if (!v.ok) {
            AssistantReply(p.invalid(), ReplyKind.ERROR, cmd.intent)
        } else {
            val r = if (cmd.intent.isWrite) advance(Draft(cmd)) else answer(Draft(cmd))
            // Only a ready confirmation is kept; follow-up questions are the model's job to ask.
            if (pending !is Pending.Confirm) pending = null
            r
        }
    }

    /** Puts an already-resolved plan in front of the user for confirmation. */
    fun proposePlan(intent: Intent, plan: Plan, text: String, lines: List<ConfirmLine>, dangerous: Boolean = false): AssistantReply =
        confirm(Draft(AICommand(intent, 1.0, true, response = text)), plan, text, lines, dangerous)

    suspend fun proposePayment(name: String?, amountPaise: Long?, hint: PartyKind?): AssistantReply = guard {
        pending = null
        if (amountPaise == null || amountPaise <= 0) return@guard AssistantReply(p.whatAmount(), ReplyKind.CLARIFY, Intent.RECORD_PAYMENT)
        if (name.isNullOrBlank()) return@guard AssistantReply(p.whoPaid(), ReplyKind.CLARIFY, Intent.RECORD_PAYMENT)
        val found = ArrayList<PartyRef>()
        for (k in if (hint != null) listOf(hint) else listOf(PartyKind.CUSTOMER, PartyKind.SUPPLIER)) {
            when (val m = NameMatcher.match(name, backend.parties(k)) { it.name }) {
                is Match.Exact -> found.add(m.item)
                is Match.Suggest -> found.add(m.item)
                is Match.Multiple -> found.addAll(m.items)
                Match.None -> {}
            }
        }
        if (found.isEmpty()) return@guard AssistantReply(p.partyNotFound(name), ReplyKind.ERROR, Intent.RECORD_PAYMENT)
        val exact = found.filter { NameMatcher.key(it.name) == NameMatcher.key(name) }
        val pick = when {
            exact.size == 1 -> exact[0]
            found.size == 1 -> found[0]
            else -> return@guard AssistantReply(
                p.multipleParties(found.size, name), ReplyKind.CLARIFY, Intent.RECORD_PAYMENT,
                choices = found.map { it.name + " (" + (if (it.kind == PartyKind.CUSTOMER) p.lCustomer else p.lSupplier) + ")" },
            )
        }
        val customer = pick.kind == PartyKind.CUSTOMER
        val lines = listOf(
            ConfirmLine(if (customer) p.lCustomer else p.lSupplier, pick.name),
            ConfirmLine(p.lAmount, money(amountPaise)),
            ConfirmLine(p.lBalanceAfter, money(pick.balancePaise - amountPaise)),
        )
        proposePlan(Intent.RECORD_PAYMENT, Plan.Payment(pick, amountPaise), if (customer) p.askPaymentIn(pick.name, money(amountPaise)) else p.askPaymentOut(pick.name, money(amountPaise)), lines)
    }

    fun proposeUndo(): AssistantReply {
        val u = lastUndo ?: return AssistantReply(p.nothingToUndo(), ReplyKind.ANSWER, Intent.UNDO)
        return proposePlan(Intent.UNDO, Plan.UndoLast(u.first, u.second), p.askUndo(u.second), emptyList())
    }

    suspend fun proposeAlias(phrase: String, productName: String): AssistantReply = guard {
        pending = null
        val product = when (val m = NameMatcher.match(productName, backend.products()) { it.name }) {
            is Match.Exact -> m.item
            is Match.Suggest -> m.item
            else -> return@guard AssistantReply(p.productNotFound(productName), ReplyKind.ERROR, Intent.LEARN_ALIAS)
        }
        val ph = phrase.trim()
        if (ph.isEmpty() || ph.length > 60) return@guard AssistantReply(p.invalid(), ReplyKind.ERROR, Intent.LEARN_ALIAS)
        proposePlan(Intent.LEARN_ALIAS, Plan.RememberAlias(ph, product.name), p.askAlias(ph, product.name), emptyList())
    }

    /** Workbook changes: a new sheet, a new column, or a new row. Existing data is never removed. */
    suspend fun proposeSheet(plan: Plan): AssistantReply = guard {
        pending = null
        val sheets = backend.sheets()
        fun find(name: String): SheetRef? {
            val key = NameMatcher.key(name)
            sheets.firstOrNull { NameMatcher.key(it.name) == key }?.let { return it }
            val q = NameMatcher.tokens(name).filter { it != "sheet" }.toSet()
            if (q.isEmpty()) return null
            return sheets.filter { NameMatcher.tokens(it.name).toSet().containsAll(q) || q.containsAll(NameMatcher.tokens(it.name).toSet()) }.singleOrNull()
                ?: sheets.filter { s -> q.any { it == s.purpose.lowercase().removeSuffix("s") || it + "s" == s.purpose.lowercase() } }.singleOrNull()
        }
        when (plan) {
            is Plan.CreateSheet -> {
                val name = plan.name.trim().take(31)
                if (name.isEmpty()) return@guard AssistantReply(p.whatName(), ReplyKind.CLARIFY, Intent.WORKBOOK_EDIT)
                if (sheets.any { NameMatcher.key(it.name) == NameMatcher.key(name) }) return@guard AssistantReply(p.sheetExists(name), ReplyKind.ERROR, Intent.WORKBOOK_EDIT)
                val cols = plan.columns.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.ifEmpty { listOf("Date", "Product", "Quantity", "Reason") }.take(30)
                proposePlan(Intent.WORKBOOK_EDIT, Plan.CreateSheet(name, cols), p.askCreateSheet(name, cols.joinToString(", ")), listOf(ConfirmLine(p.lSheet, name), ConfirmLine(p.lColumns, cols.joinToString(", "))))
            }
            is Plan.AddColumn -> {
                val sheet = find(plan.sheet) ?: return@guard AssistantReply(p.sheetNotFound(plan.sheet), ReplyKind.ERROR, Intent.WORKBOOK_EDIT)
                val col = plan.column.trim().take(40)
                if (col.isEmpty()) return@guard AssistantReply(p.whatName(), ReplyKind.CLARIFY, Intent.WORKBOOK_EDIT)
                if (sheet.columns.any { it.equals(col, ignoreCase = true) }) return@guard AssistantReply(p.columnExists(col), ReplyKind.ERROR, Intent.WORKBOOK_EDIT)
                proposePlan(Intent.WORKBOOK_EDIT, Plan.AddColumn(sheet.name, col), p.askAddColumn(sheet.name, col), listOf(ConfirmLine(p.lSheet, sheet.name), ConfirmLine(p.lColumns, col)))
            }
            is Plan.AddRow -> {
                val sheet = find(plan.sheet) ?: return@guard AssistantReply(p.sheetNotFound(plan.sheet), ReplyKind.ERROR, Intent.WORKBOOK_EDIT)
                val values = LinkedHashMap<String, String>()
                for ((k, v) in plan.values) {
                    val col = sheet.columns.firstOrNull { NameMatcher.key(it) == NameMatcher.key(k) }
                        ?: return@guard AssistantReply(p.sheetNotFound(k) + " " + p.lColumns + ": " + sheet.columns.joinToString(", "), ReplyKind.ERROR, Intent.WORKBOOK_EDIT)
                    if (v.isNotBlank()) values[col] = v.trim().take(200)
                }
                if (values.isEmpty()) return@guard AssistantReply(p.invalid(), ReplyKind.ERROR, Intent.WORKBOOK_EDIT)
                proposePlan(Intent.WORKBOOK_EDIT, Plan.AddRow(sheet.name, values), p.askAddRow(sheet.name), listOf(ConfirmLine(p.lSheet, sheet.name)) + values.map { ConfirmLine(it.key, it.value) })
            }
            else -> AssistantReply(p.invalid(), ReplyKind.ERROR, Intent.WORKBOOK_EDIT)
        }
    }

    private suspend fun matchProduct(name: String): Match<ProductRef> {
        val products = backend.products()
        val key = NameMatcher.key(name)
        val alias = backend.aliases().firstOrNull { NameMatcher.key(it.phrase) == key }
        if (alias != null) {
            val target = NameMatcher.key(alias.productName)
            products.firstOrNull { NameMatcher.key(it.name) == target }?.let { return Match.Exact(it) }
        }
        return NameMatcher.match(name, products) { it.name }
    }

    private suspend fun greetingText(): String {
        val w = DateRanges.range(DateKey.TODAY, zone(), now())
        val sales = backend.sales(w.start, w.end)
        val low = backend.products().count { it.isLow }
        return p.greeting(now().atZone(zone()).hour, if (sales.count > 0) money(sales.totalPaise) else null, low)
    }

    suspend fun undo(token: String): AssistantReply = guard {
        val r = backend.undo(token)
        if (r.ok && lastUndo?.first == token) lastUndo = null
        backend.audit("UNDO", "{\"token\":\"$token\"}", r.error ?: "OK", if (r.ok) "SUCCESS" else "FAILED")
        if (r.ok) AssistantReply(p.undone(), ReplyKind.DONE) else AssistantReply(p.undoFailed(), ReplyKind.ERROR)
    }

    private suspend fun guard(block: suspend () -> AssistantReply): AssistantReply = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        pending = null
        AssistantReply(p.saveFailed(), ReplyKind.ERROR)
    }

    // ------------------------------------------------------------------ routing

    private suspend fun route(text: String): AssistantReply {
        val pend = pending ?: return startNew(text)
        val yes = isYes(text)
        val no = isNo(text)
        val d = pend.draft
        when (pend) {
            is Pending.Confirm -> {
                if (yes) return if (needsOwnerCheck) verifyFirst(pend) else execute(pend)
                if (no) return cancelPending()
            }
            is Pending.LowConfidence -> {
                if (yes) { pending = null; return advance(d) }
                if (no) return cancelPending()
            }
            is Pending.DidYouMeanProduct -> {
                if (yes) {
                    val phrase = d.e.productName
                    d.product = pend.candidate; d.productDone = true; pending = null
                    val r = advance(d)
                    if (phrase != null && NameMatcher.key(phrase) != NameMatcher.key(pend.candidate.name)) {
                        // The owner just confirmed what they call this product: remember it, and say so.
                        val saved = try { backend.execute(Plan.RememberAlias(phrase, pend.candidate.name)).ok } catch (e: Exception) { false }
                        if (saved) return r.copy(text = r.text + p.learned(phrase, pend.candidate.name))
                    }
                    return r
                }
                if (no) { pending = null; return productRejected(d) }
            }
            is Pending.DidYouMeanParty -> {
                if (yes) { d.party = pend.candidate; d.partyDone = true; pending = null; return advance(d) }
                if (no) { d.newPartyName = partyNameOf(d); d.partyDone = true; pending = null; return advance(d) }
            }
            is Pending.ChooseProduct -> {
                if (no) return cancelPending()
                val i = pick(text, pend.options.map { it.name })
                if (i != null) { d.product = pend.options[i]; d.productDone = true; pending = null; return advance(d) }
            }
            is Pending.ChooseParty -> {
                if (no) return cancelPending()
                val i = pick(text, pend.options.map { it.name })
                if (i != null) { d.party = pend.options[i]; d.partyDone = true; pending = null; return advance(d) }
            }
            is Pending.Field -> {
                if (no) return cancelPending()
            }
        }

        if (pend is Pending.Confirm && d.e.quantityMilli != null) {
            val norm = TextNormalizer.normalize(text)
            if (Regex("^(actually|no|illa|illai|sorry|wait|correction|change|make it|not)\\b").containsMatchIn(norm)) {
                val last = Regex("(?<![a-z0-9.])(\\d+(?:\\.\\d+)?)\\s*(\\S+)?").findAll(norm).lastOrNull()
                val q = last?.let { Qty.parse(it.groupValues[1]) }
                if (q != null && q > 0) {
                    d.e = d.e.copy(quantityMilli = q, unit = Units.normalize(last.groupValues[2]) ?: d.e.unit, totalPaise = null)
                    pending = null
                    return advance(d)
                }
            }
        }

        // Not a direct answer. Did the user move on to a completely new command?
        val fresh = LocalParser.parse(text)
        val isNewCommand = when (pend) {
            is Pending.Field -> fresh.intent.isWrite && fresh.confidence >= 0.9 && LocalParser.FIELD_RECORD_TYPE !in fresh.missingFields
            else -> fresh.intent != Intent.UNKNOWN
        }
        if (isNewCommand) {
            auditDraft(d, "replaced by a new command", "CANCELLED")
            return startNew(text)
        }
        return when (pend) {
            is Pending.Field -> fill(pend, text)
            is Pending.Confirm -> pend.question
            is Pending.LowConfidence -> AssistantReply(p.lowConfidence(describe(d)), ReplyKind.CLARIFY, d.intent, choices = yesNo())
            is Pending.DidYouMeanProduct -> {
                // Treat the reply as a corrected name.
                d.e = d.e.copy(productName = cleanAnswer(text), usesContext = false); pending = null; advance(d)
            }
            is Pending.DidYouMeanParty -> {
                setPartyName(d, cleanAnswer(text)); pending = null; advance(d)
            }
            is Pending.ChooseProduct -> AssistantReply(p.pickOne(), ReplyKind.CLARIFY, d.intent, choices = pend.options.map { it.name })
            is Pending.ChooseParty -> AssistantReply(p.pickOne(), ReplyKind.CLARIFY, d.intent, choices = pend.options.map { it.name })
        }
    }

    private suspend fun startNew(text: String): AssistantReply {
        pending = null
        val norm = TextNormalizer.normalize(text)
        if (Regex("^(please )?(undo|reverse|revert)( that| it| this| the last one| last| pannu| pannunga)*$").containsMatchIn(norm)) return proposeUndo()
        Regex("^(?:please )?(?:create|make|add|new)(?: a| an| one)?(?: new| separate)? sheet(?: for| called| named| to track)? (.+)$").find(norm)?.let {
            return proposeSheet(Plan.CreateSheet(NameMatcher.titleCase(it.groupValues[1].removeSuffix(" sheet")), emptyList()))
        }
        Regex("^(?:please )?(?:in|to)(?: the)? (.+?) sheet add (?:a |an )?(.+?) column$").find(norm)?.let {
            return proposeSheet(Plan.AddColumn(it.groupValues[1], LocalParser.restoreCase(text, it.groupValues[2])))
        }
        Regex("^(?:please )?add (?:a |an )?(.+?) column (?:to|in)(?: the)? (.+?)(?: sheet)?$").find(norm)?.let {
            return proposeSheet(Plan.AddColumn(it.groupValues[2], LocalParser.restoreCase(text, it.groupValues[1])))
        }
        val cmd = try {
            provider.understand(history.toList(), text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LocalParser.parse(text)
        }
        val v = CommandValidator.validate(cmd)
        if (!v.ok) {
            backend.audit(cmd.intent.name, cmd.toJson(), v.errors.joinToString("; "), "REJECTED")
            return AssistantReply(p.invalid(), ReplyKind.ERROR, cmd.intent)
        }
        return when {
            cmd.intent == Intent.UNKNOWN -> AssistantReply(
                when {
                    cmd.entities.note == LocalParser.GUIDE_PAYMENT -> p.paymentGuide()
                    cmd.entities.note == LocalParser.GUIDE_DELETE -> p.deleteGuide()
                    // A cloud model answered in its own words (chat, advice, questions about the data).
                    cmd.source == "cloud" && cmd.response.isNotBlank() -> cmd.response
                    cmd.entities.note == LocalParser.GUIDE_GREETING -> greetingText()
                    cmd.entities.note == LocalParser.GUIDE_THANKS -> p.welcome()
                    else -> p.unknown()
                }, ReplyKind.ANSWER, Intent.UNKNOWN
            )
            cmd.intent == Intent.HELP -> AssistantReply(p.help(), ReplyKind.ANSWER, Intent.HELP)
            cmd.intent == Intent.RECORD_PAYMENT -> proposePayment(
                cmd.entities.customerName ?: cmd.entities.supplierName, cmd.entities.amountPaise,
                when {
                    cmd.entities.note == "EITHER" -> null
                    cmd.entities.supplierName != null -> PartyKind.SUPPLIER
                    else -> PartyKind.CUSTOMER
                },
            )
            cmd.confidence < 0.70 -> AssistantReply(if (cmd.intent.isWrite) p.notSure() else p.unknown(), ReplyKind.ANSWER, cmd.intent)
            !cmd.intent.isWrite -> answer(Draft(cmd))
            cmd.confidence < 0.90 -> {
                val d = Draft(cmd)
                pending = Pending.LowConfidence(d)
                AssistantReply(p.lowConfidence(describe(d)), ReplyKind.CLARIFY, d.intent, choices = yesNo())
            }
            else -> advance(Draft(cmd))
        }
    }

    private suspend fun cancelPending(): AssistantReply {
        val pend = pending ?: return AssistantReply(p.nothingToConfirm(), ReplyKind.ERROR)
        pending = null
        auditDraft(pend.draft, "cancelled by user", "CANCELLED")
        return AssistantReply(p.cancelled(), ReplyKind.CANCELLED, pend.draft.intent)
    }

    // ------------------------------------------------------------------ building a plan

    private suspend fun advance(d: Draft): AssistantReply = when (d.intent) {
        Intent.CREATE_CUSTOMER, Intent.CREATE_SUPPLIER -> advanceParty(d)
        Intent.CREATE_EXPENSE -> advanceExpense(d)
        Intent.ADD_PRODUCT -> advanceAddProduct(d)
        Intent.UPDATE_PRODUCT -> advanceUpdate(d)
        Intent.STOCK_IN, Intent.CREATE_PURCHASE, Intent.STOCK_OUT, Intent.STOCK_ADJUST, Intent.CREATE_SALE -> advanceStock(d)
        else -> answer(d)
    }

    private suspend fun advanceParty(d: Draft): AssistantReply {
        val name = (d.e.customerName ?: d.e.supplierName)?.trim().orEmpty()
        if (name.isEmpty()) return ask(d, "partyName", p.whatName())
        if (d.needsRecordType) {
            return ask(d, LocalParser.FIELD_RECORD_TYPE, p.recordType(name), listOf(p.lCustomer, p.lSupplier, p.lProduct))
        }
        val kind = if (d.intent == Intent.CREATE_CUSTOMER) PartyKind.CUSTOMER else PartyKind.SUPPLIER
        val existing = NameMatcher.match(name, backend.parties(kind)) { it.name }
        if (existing is Match.Exact) return fail(d, p.partyExists(existing.item.name))
        val lines = ArrayList<ConfirmLine>()
        lines.add(ConfirmLine(p.lName, name))
        if (!d.e.phone.isNullOrBlank()) lines.add(ConfirmLine(p.lPhone, d.e.phone!!))
        return confirm(d, Plan.CreateParty(kind, name, d.e.phone.orEmpty()), p.askParty(kind, name), lines)
    }

    private suspend fun advanceExpense(d: Draft): AssistantReply {
        val amount = d.e.amountPaise ?: return ask(d, "amount", p.whatAmount())
        if (amount <= 0) return ask(d, "amount", p.needNumber())
        val category = d.e.expenseCategory ?: return ask(d, "expenseCategory", p.whatFor())
        val lines = listOf(ConfirmLine(p.lCategory, category), ConfirmLine(p.lAmount, money(amount)))
        return confirm(d, Plan.Expense(category, amount, d.e.note.orEmpty()), p.askExpense(category, money(amount)), lines)
    }

    private suspend fun advanceAddProduct(d: Draft): AssistantReply {
        val name = d.e.productName?.trim().orEmpty()
        if (name.isEmpty()) return ask(d, "productName", p.needProductFirst())
        val existing = NameMatcher.match(name, backend.products()) { it.name }
        if (existing is Match.Exact) return fail(d, p.productExists(existing.item.name))
        val unit = d.e.unit ?: "PCS"
        val opening = d.e.quantityMilli ?: 0L
        val lines = ArrayList<ConfirmLine>()
        lines.add(ConfirmLine(p.lNewProduct, name))
        lines.add(ConfirmLine(p.lUnit, unit))
        lines.add(ConfirmLine(p.lOpening, qty(opening, unit)))
        d.e.sellPaise?.let { lines.add(ConfirmLine(p.lSelling, money(it))) }
        d.e.buyPaise?.let { lines.add(ConfirmLine(p.lCost, money(it))) }
        d.e.minMilli?.let { lines.add(ConfirmLine(p.lMin, qty(it, unit))) }
        return confirm(d, Plan.AddProduct(name, unit, opening, d.e.sellPaise, d.e.buyPaise, d.e.minMilli), p.askAddProduct(name), lines)
    }

    private suspend fun advanceUpdate(d: Draft): AssistantReply {
        if (!d.productDone) {
            val name = d.e.productName ?: (if (d.e.usesContext) lastProduct else null)
                ?: return ask(d, "productName", p.needProductFirst())
            resolveProduct(d, name, allowNew = false)?.let { return it }
        }
        val prod = d.product ?: return fail(d, p.productNotFound(d.e.productName.orEmpty()))
        if (d.e.sellPaise == null && d.e.buyPaise == null && d.e.minMilli == null) {
            return ask(d, "updateValue", p.whatToChange(prod.name))
        }
        val lines = ArrayList<ConfirmLine>()
        lines.add(ConfirmLine(p.lProduct, prod.name))
        d.e.sellPaise?.let { lines.add(ConfirmLine(p.lSelling, money(prod.sellPaise) + " → " + money(it))) }
        d.e.buyPaise?.let { lines.add(ConfirmLine(p.lCost, money(prod.buyPaise) + " → " + money(it))) }
        d.e.minMilli?.let { lines.add(ConfirmLine(p.lMin, qty(prod.minMilli, prod.unit) + " → " + qty(it, prod.unit))) }
        return confirm(d, Plan.UpdateProduct(prod, d.e.sellPaise, d.e.buyPaise, d.e.minMilli), p.askUpdate(prod.name), lines)
    }

    private suspend fun advanceStock(d: Draft): AssistantReply {
        if (!d.productDone) {
            val name = d.e.productName ?: (if (d.e.usesContext) lastProduct else null)
            if (name == null) {
                val q = d.e.quantityMilli?.let { Qty.format(it) + (d.e.unit?.let { u -> " " + Units.label(u, it, tamil()) } ?: "") }
                return ask(d, "productName", if (q != null) p.whichProduct(q) else p.needProductFirst())
            }
            val allowNew = d.intent == Intent.STOCK_IN || d.intent == Intent.CREATE_PURCHASE
            resolveProduct(d, name, allowNew)?.let { return it }
        }
        val prod = d.product
        val prodName = prod?.name ?: d.newProductName ?: return fail(d, p.needProductFirst())
        val qtyIn = d.e.quantityMilli ?: return ask(d, "quantity", p.howMany(prodName))
        if (qtyIn < 0 || (qtyIn == 0L && d.intent != Intent.STOCK_ADJUST)) return ask(d, "quantity", p.needNumber())

        val unit: String
        val qtyMilli: Long
        if (prod != null) {
            val said = d.e.unit
            if (said == null || said == prod.unit) {
                unit = prod.unit; qtyMilli = qtyIn
            } else {
                val conv = Units.convert(qtyIn, said, prod.unit)
                    ?: return fail(d, p.unitMismatch(prod.name, Units.label(prod.unit, 2000, tamil()), Units.label(said, 2000, tamil())))
                unit = prod.unit; qtyMilli = conv
            }
        } else {
            unit = d.e.unit ?: "PCS"; qtyMilli = qtyIn
        }

        if (!d.partyDone) {
            val (pname, kind) = when (d.intent) {
                Intent.CREATE_SALE -> d.e.customerName to PartyKind.CUSTOMER
                Intent.STOCK_IN, Intent.CREATE_PURCHASE -> d.e.supplierName to PartyKind.SUPPLIER
                else -> null to PartyKind.CUSTOMER
            }
            if (pname.isNullOrBlank()) d.partyDone = true else resolveParty(d, pname, kind)?.let { return it }
        }
        val partyName = d.party?.name ?: d.newPartyName
        val q = qty(qtyMilli, unit)
        val lines = ArrayList<ConfirmLine>()
        lines.add(ConfirmLine(if (prod == null) p.lNewProduct else p.lProduct, prodName))

        when (d.intent) {
            Intent.STOCK_IN, Intent.CREATE_PURCHASE -> {
                var unitCost = d.e.unitPricePaise ?: d.e.totalPaise?.let { perUnit(it, qtyMilli) }
                val hasSupplier = partyName != null
                val returnIn = d.e.reason == "RETURN_IN"
                val asPurchase = !returnIn && (d.intent == Intent.CREATE_PURCHASE || (hasSupplier && unitCost != null))
                if (d.intent == Intent.CREATE_PURCHASE && unitCost == null) {
                    unitCost = prod?.buyPaise?.takeIf { it > 0 } ?: return ask(d, "unitPrice", p.whatPrice(prodName))
                }
                val total = if (unitCost != null) d.e.totalPaise ?: Money.lineTotal(qtyMilli, unitCost) else null
                val paid = d.e.paid ?: !hasSupplier
                lines.add(ConfirmLine(p.lQuantity, q))
                if (returnIn) lines.add(ConfirmLine(p.lReason, p.returnIn))
                if (partyName != null) lines.add(ConfirmLine(if (d.party == null) p.lNewSupplier else p.lSupplier, partyName))
                if (unitCost != null) lines.add(ConfirmLine(p.lRate, money(unitCost)))
                if (total != null) lines.add(ConfirmLine(p.lTotal, money(total)))
                if (asPurchase) lines.add(ConfirmLine(p.lPayment, if (paid) p.paid else p.credit))
                lines.add(ConfirmLine(p.lAfter, qty((prod?.stockMilli ?: 0L) + qtyMilli, unit)))
                val plan = Plan.StockIn(prod, d.newProductName, unit, qtyMilli, unitCost, d.party, d.newPartyName, asPurchase, paid, returnIn, if (asPurchase) total else null)
                val text = when {
                    asPurchase -> p.askPurchase(q, prodName, partyName ?: "-", money(total ?: 0L))
                    prod == null -> p.askAddNew(q, prodName)
                    else -> p.askStockIn(q, prodName)
                }
                return confirm(d, plan, text, lines)
            }
            Intent.STOCK_OUT -> {
                val pr = prod ?: return fail(d, p.productNotFound(prodName))
                if (qtyMilli > pr.stockMilli && !allowNegativeStock()) {
                    return fail(d, p.insufficient(pr.name, qty(pr.stockMilli, unit), q))
                }
                val reason = try { OutReason.valueOf(d.e.reason ?: "OTHER") } catch (e: IllegalArgumentException) { OutReason.OTHER }
                lines.add(ConfirmLine(p.lQuantity, q))
                lines.add(ConfirmLine(p.lReason, p.reason(reason)))
                lines.add(ConfirmLine(p.lAfter, qty(pr.stockMilli - qtyMilli, unit)))
                return confirm(d, Plan.StockOut(pr, qtyMilli, reason, d.e.note.orEmpty()), p.askStockOut(q, pr.name), lines)
            }
            Intent.STOCK_ADJUST -> {
                val pr = prod ?: return fail(d, p.productNotFound(prodName))
                if (qtyMilli == pr.stockMilli) return fail(d, p.noChange(pr.name, q))
                val diff = qtyMilli - pr.stockMilli
                lines.add(ConfirmLine(p.lCurrent, qty(pr.stockMilli, unit)))
                lines.add(ConfirmLine(p.lNew, q))
                lines.add(ConfirmLine(p.lAdjustment, (if (diff > 0) "+" else "-") + qty(Math.abs(diff), unit)))
                return confirm(d, Plan.StockAdjust(pr, qtyMilli), p.askAdjust(pr.name, qty(pr.stockMilli, unit), q), lines, dangerous = true)
            }
            else -> { // CREATE_SALE
                val pr = prod ?: return fail(d, p.productNotFound(prodName))
                val unitPrice = d.e.unitPricePaise ?: d.e.totalPaise?.let { perUnit(it, qtyMilli) }
                    ?: pr.sellPaise.takeIf { it > 0 } ?: return ask(d, "unitPrice", p.whatPrice(pr.name))
                if (qtyMilli > pr.stockMilli && !allowNegativeStock()) {
                    return fail(d, p.insufficient(pr.name, qty(pr.stockMilli, unit), q))
                }
                val total = d.e.totalPaise ?: Money.lineTotal(qtyMilli, unitPrice)
                val paid = d.e.paid ?: (partyName == null)
                lines.add(ConfirmLine(p.lQuantity, q))
                if (partyName != null) lines.add(ConfirmLine(if (d.party == null) p.lNewCustomer else p.lCustomer, partyName))
                else lines.add(ConfirmLine(p.lCustomer, p.cashSale))
                lines.add(ConfirmLine(p.lRate, money(unitPrice)))
                lines.add(ConfirmLine(p.lTotal, money(total)))
                lines.add(ConfirmLine(p.lPayment, if (paid) p.paid else p.credit))
                lines.add(ConfirmLine(p.lAfter, qty(pr.stockMilli - qtyMilli, unit)))
                val plan = Plan.Sale(pr, qtyMilli, unitPrice, d.party, d.newPartyName, paid, total)
                return confirm(d, plan, p.askSale(q, pr.name, partyName, money(total)), lines)
            }
        }
    }

    private suspend fun resolveProduct(d: Draft, name: String, allowNew: Boolean): AssistantReply? {
        when (val m = matchProduct(name)) {
            is Match.Exact -> { d.product = m.item; d.productDone = true }
            is Match.Suggest -> {
                d.e = d.e.copy(productName = name)
                pending = Pending.DidYouMeanProduct(d, m.item)
                return AssistantReply(p.didYouMean(m.item.name), ReplyKind.CLARIFY, d.intent, choices = yesNo())
            }
            is Match.Multiple -> {
                pending = Pending.ChooseProduct(d, m.items)
                return AssistantReply(p.multiple(m.items.size, name), ReplyKind.CLARIFY, d.intent, choices = m.items.map { it.name })
            }
            Match.None -> {
                if (!allowNew) return fail(d, p.productNotFound(name))
                d.newProductName = name; d.productDone = true
            }
        }
        return null
    }

    private suspend fun productRejected(d: Draft): AssistantReply {
        val name = d.e.productName.orEmpty()
        return if ((d.intent == Intent.STOCK_IN || d.intent == Intent.CREATE_PURCHASE) && name.isNotEmpty()) {
            d.newProductName = name; d.productDone = true; advance(d)
        } else fail(d, p.productNotFound(name))
    }

    private suspend fun resolveParty(d: Draft, name: String, kind: PartyKind): AssistantReply? {
        when (val m = NameMatcher.match(name, backend.parties(kind)) { it.name }) {
            is Match.Exact -> { d.party = m.item; d.partyDone = true }
            is Match.Suggest -> {
                pending = Pending.DidYouMeanParty(d, m.item)
                return AssistantReply(p.didYouMean(m.item.name), ReplyKind.CLARIFY, d.intent, choices = yesNo())
            }
            is Match.Multiple -> {
                pending = Pending.ChooseParty(d, m.items)
                return AssistantReply(p.multipleParties(m.items.size, name), ReplyKind.CLARIFY, d.intent, choices = m.items.map { it.name })
            }
            Match.None -> { d.newPartyName = name; d.partyDone = true }
        }
        return null
    }

    private fun partyNameOf(d: Draft): String? = if (d.intent == Intent.CREATE_SALE) d.e.customerName else d.e.supplierName

    private fun setPartyName(d: Draft, name: String) {
        d.e = if (d.intent == Intent.CREATE_SALE || d.intent == Intent.CREATE_CUSTOMER) d.e.copy(customerName = name) else d.e.copy(supplierName = name)
    }

    private suspend fun fill(pend: Pending.Field, text: String): AssistantReply {
        val d = pend.draft
        val norm = TextNormalizer.normalize(text)
        val number = Regex("(?<![a-z0-9.])(\\d+(?:\\.\\d+)?)\\s*(\\S+)?").find(norm)
        when (pend.field) {
            "productName" -> {
                val n = cleanAnswer(text)
                if (n.isEmpty()) return pend.question
                d.e = d.e.copy(productName = n, usesContext = false); d.productDone = false
            }
            "quantity" -> {
                val q = number?.let { Qty.parse(it.groupValues[1]) }
                val min = if (d.intent == Intent.STOCK_ADJUST) 0L else 1L
                if (q == null || q < min) return ask(d, "quantity", p.needNumber())
                d.e = d.e.copy(quantityMilli = q, unit = Units.normalize(number.groupValues[2]) ?: d.e.unit)
            }
            "unitPrice" -> {
                val v = number?.let { Money.parse(it.groupValues[1]) } ?: return ask(d, "unitPrice", p.needNumber())
                d.e = d.e.copy(unitPricePaise = v)
            }
            "amount" -> {
                val v = number?.let { Money.parse(it.groupValues[1]) }
                if (v == null || v <= 0) return ask(d, "amount", p.needNumber())
                d.e = d.e.copy(amountPaise = v)
            }
            "expenseCategory" -> {
                val c = LocalParser.expenseCategory(norm) ?: return pend.question
                d.e = d.e.copy(expenseCategory = c, note = d.e.note ?: text.trim().take(100))
            }
            "partyName" -> {
                val n = cleanAnswer(text)
                if (n.isEmpty()) return pend.question
                setPartyName(d, n)
            }
            "updateValue" -> {
                val v = number?.groupValues?.get(1) ?: return ask(d, "updateValue", p.needNumber())
                d.e = when {
                    Regex("\\b(min|minimum|reorder)\\b").containsMatchIn(norm) -> d.e.copy(minMilli = Qty.parse(v))
                    Regex("\\b(cost|purchase|buying)\\b").containsMatchIn(norm) -> d.e.copy(buyPaise = Money.parse(v))
                    else -> d.e.copy(sellPaise = Money.parse(v))
                }
            }
            LocalParser.FIELD_RECORD_TYPE -> {
                val name = (d.e.customerName ?: d.e.supplierName ?: d.e.productName).orEmpty()
                when {
                    Regex("\\b(customer|buyer|client)\\b").containsMatchIn(norm) -> {
                        d.intent = Intent.CREATE_CUSTOMER; d.e = d.e.copy(customerName = name, supplierName = null, productName = null)
                    }
                    Regex("\\b(supplier|vendor|seller)\\b").containsMatchIn(norm) -> {
                        d.intent = Intent.CREATE_SUPPLIER; d.e = d.e.copy(supplierName = name, customerName = null, productName = null)
                    }
                    Regex("\\b(product|products|item|items|stock)\\b").containsMatchIn(norm) -> {
                        d.intent = Intent.ADD_PRODUCT; d.e = d.e.copy(productName = name, customerName = null, supplierName = null)
                    }
                    else -> return pend.question
                }
                d.needsRecordType = false
            }
        }
        pending = null
        return advance(d)
    }

    // ------------------------------------------------------------------ executing

    private fun confirm(d: Draft, plan: Plan, text: String, lines: List<ConfirmLine>, dangerous: Boolean = false): AssistantReply {
        val reply = AssistantReply(text, ReplyKind.CONFIRM, d.intent, lines = lines, dangerous = dangerous)
        // The product being proposed becomes "it" for the next sentence ("actually 30", "same one").
        when (plan) {
            is Plan.StockIn -> lastProduct = plan.product?.name ?: plan.newProductName ?: lastProduct
            is Plan.StockOut -> lastProduct = plan.product.name
            is Plan.StockAdjust -> lastProduct = plan.product.name
            is Plan.Sale -> lastProduct = plan.product.name
            is Plan.UpdateProduct -> lastProduct = plan.product.name
            else -> {}
        }
        pending = Pending.Confirm(d, plan, reply)
        return reply
    }

    private suspend fun execute(pend: Pending.Confirm): AssistantReply {
        pending = null
        val d = pend.draft
        val plan = pend.plan
        val r = try {
            backend.execute(plan)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ExecResult(false, "FAILED")
        }
        if (!r.ok) {
            auditDraft(d, r.error ?: "FAILED", "FAILED")
            return AssistantReply(errorText(plan, r.error), ReplyKind.ERROR, d.intent)
        }
        auditDraft(d, "OK" + (r.reference?.let { " $it" } ?: ""), "SUCCESS")
        val text = when (plan) {
            is Plan.AddProduct -> { lastProduct = plan.name; p.doneProductCreated(plan.name, qty(plan.openingMilli, plan.unit)) }
            is Plan.UpdateProduct -> { lastProduct = plan.product.name; p.doneUpdate(plan.product.name) }
            is Plan.StockIn -> {
                val name = plan.product?.name ?: plan.newProductName.orEmpty()
                lastProduct = name
                val after = qty(r.stockAfterMilli ?: ((plan.product?.stockMilli ?: 0L) + plan.qtyMilli), plan.unit)
                val q = qty(plan.qtyMilli, plan.unit)
                when {
                    plan.asPurchase -> p.donePurchase(q, name, money(plan.totalPaise ?: 0L), after)
                    plan.product == null -> p.doneProductCreated(name, q)
                    else -> p.doneStockIn(q, name, after)
                }
            }
            is Plan.StockOut -> {
                lastProduct = plan.product.name
                p.doneStockOut(qty(plan.qtyMilli, plan.product.unit), plan.product.name, qty(r.stockAfterMilli ?: (plan.product.stockMilli - plan.qtyMilli), plan.product.unit))
            }
            is Plan.StockAdjust -> { lastProduct = plan.product.name; p.doneAdjust(plan.product.name, qty(plan.newQtyMilli, plan.product.unit)) }
            is Plan.Sale -> {
                lastProduct = plan.product.name
                p.doneSale(qty(plan.qtyMilli, plan.product.unit), plan.product.name, money(plan.totalPaise), qty(r.stockAfterMilli ?: (plan.product.stockMilli - plan.qtyMilli), plan.product.unit))
            }
            is Plan.CreateParty -> p.doneParty(plan.kind, plan.name)
            is Plan.Expense -> p.doneExpense(plan.category, money(plan.amountPaise))
            is Plan.Payment -> if (plan.party.kind == PartyKind.CUSTOMER) p.donePaymentIn(plan.party.name, money(plan.amountPaise)) else p.donePaymentOut(plan.party.name, money(plan.amountPaise))
            is Plan.UndoLast -> p.undone()
            is Plan.CreateSheet -> p.doneCreateSheet(plan.name)
            is Plan.AddColumn -> p.doneAddColumn(plan.sheet, plan.column)
            is Plan.AddRow -> p.doneAddRow(plan.sheet)
            is Plan.RememberAlias -> p.doneAlias(plan.phrase, plan.productName)
            is Plan.Business -> plan.doneText + (r.reference?.takeIf { it.isNotBlank() && it !in plan.doneText }?.let { " ($it)" } ?: "")
        }
        // "Undo that" always means the latest change; one that cannot be undone ends the chain.
        lastUndo = if (plan is Plan.UndoLast) null else r.undoToken?.let { it to text }
        recordAction(plan, r, text)
        return AssistantReply(text, ReplyKind.DONE, d.intent, undoToken = r.undoToken)
    }

    private suspend fun recordAction(plan: Plan, r: ExecResult, result: String) {
        fun q(milli: Long?, unit: String) = if (milli == null) "" else qty(milli, unit)
        val rec = when (plan) {
            is Plan.StockIn -> ActionRecord(commandText, "STOCK_IN", if (plan.asPurchase) "PURCHASE" else "STOCK_IN", plan.product?.name ?: plan.newProductName.orEmpty(), q(plan.product?.stockMilli ?: 0L, plan.unit), q(r.stockAfterMilli, plan.unit), true, result)
            is Plan.StockOut -> ActionRecord(commandText, "STOCK_OUT", plan.reason.name, plan.product.name, q(plan.product.stockMilli, plan.product.unit), q(r.stockAfterMilli, plan.product.unit), true, result)
            is Plan.StockAdjust -> ActionRecord(commandText, "STOCK_ADJUST", "ADJUST", plan.product.name, q(plan.product.stockMilli, plan.product.unit), q(plan.newQtyMilli, plan.product.unit), true, result)
            is Plan.Sale -> ActionRecord(commandText, "CREATE_SALE", "SALE", plan.product.name, q(plan.product.stockMilli, plan.product.unit), q(r.stockAfterMilli, plan.product.unit), true, result)
            is Plan.Payment -> ActionRecord(commandText, "RECORD_PAYMENT", "PAYMENT", plan.party.name, money(plan.party.balancePaise), money(plan.party.balancePaise - plan.amountPaise), true, result)
            else -> ActionRecord(commandText, plan::class.simpleName.orEmpty(), plan::class.simpleName.orEmpty(), "", "", "", true, result)
        }
        try { backend.recordAction(rec) } catch (e: CancellationException) { throw e } catch (e: Exception) { /* the log must never break the action */ }
    }

    private fun errorText(plan: Plan, error: String?): String = when (error) {
        "INSUFFICIENT_STOCK" -> when (plan) {
            is Plan.StockOut -> p.insufficient(plan.product.name, qty(plan.product.stockMilli, plan.product.unit), qty(plan.qtyMilli, plan.product.unit))
            is Plan.Sale -> p.insufficient(plan.product.name, qty(plan.product.stockMilli, plan.product.unit), qty(plan.qtyMilli, plan.product.unit))
            else -> p.saveFailed()
        }
        "DUPLICATE" -> when (plan) {
            is Plan.AddProduct -> p.productExists(plan.name)
            is Plan.CreateParty -> p.partyExists(plan.name)
            else -> p.saveFailed()
        }
        "NOT_FOUND" -> when (plan) {
            is Plan.StockOut -> p.productNotFound(plan.product.name)
            is Plan.Sale -> p.productNotFound(plan.product.name)
            is Plan.StockAdjust -> p.productNotFound(plan.product.name)
            is Plan.UpdateProduct -> p.productNotFound(plan.product.name)
            else -> p.saveFailed()
        }
        "READ_ONLY" -> p.readOnly()
        else -> p.saveFailed()
    }

    private suspend fun fail(d: Draft, message: String): AssistantReply {
        pending = null
        if (d.intent.isWrite) auditDraft(d, message, "REJECTED")
        return AssistantReply(message, ReplyKind.ERROR, d.intent)
    }

    private fun ask(d: Draft, field: String, question: String, choices: List<String> = emptyList()): AssistantReply {
        val reply = AssistantReply(question, ReplyKind.CLARIFY, d.intent, choices = choices)
        pending = Pending.Field(d, field, reply)
        return reply
    }

    private suspend fun auditDraft(d: Draft, result: String, status: String) {
        if (!d.intent.isWrite) return
        try {
            backend.audit(d.intent.name, d.json, result.take(300), status)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The audit trail must never break the user's action.
        }
    }

    // ------------------------------------------------------------------ read-only answers

    private suspend fun answer(d: Draft): AssistantReply {
        val e = d.e
        when (d.intent) {
            Intent.STOCK_QUERY -> {
                val name = e.productName ?: (if (e.usesContext) lastProduct else null)
                    ?: return ask(d, "productName", p.whichProduct(null))
                pending = null
                return when (val m = matchProduct(name)) {
                    is Match.Exact -> stockAnswer(m.item)
                    is Match.Suggest -> stockAnswer(m.item)
                    is Match.Multiple -> AssistantReply(
                        p.matchesHeader(name) + "\n" + m.items.joinToString("\n") { "• ${it.name}: ${qty(it.stockMilli, it.unit)}" },
                        ReplyKind.ANSWER, d.intent, spoken = p.matchesHeader(name)
                    )
                    Match.None -> AssistantReply(p.productNotFound(name), ReplyKind.ERROR, d.intent)
                }
            }
            Intent.LOW_STOCK_QUERY -> {
                val low = backend.products().filter { it.isLow }.sortedBy { it.name.lowercase() }
                if (low.isEmpty()) return AssistantReply(p.noLow(), ReplyKind.ANSWER, d.intent)
                val shown = low.take(15)
                val body = shown.joinToString("\n") { p.lowLine(it.name, qty(it.stockMilli, it.unit), qty(it.minMilli, it.unit)) }
                val more = if (low.size > shown.size) "\n" + p.andMore(low.size - shown.size) else ""
                return AssistantReply(p.lowHeader(low.size) + "\n" + body + more, ReplyKind.ANSWER, d.intent,
                    spoken = p.lowHeader(low.size) + " " + shown.take(5).joinToString(", ") { it.name })
            }
            Intent.SALES_QUERY, Intent.REPORT_SALES -> {
                val key = e.dateKey ?: DateKey.TODAY
                val w = DateRanges.range(key, zone(), now())
                val s = backend.sales(w.start, w.end)
                val label = DateRanges.label(key, tamil())
                if (s.count == 0) return AssistantReply(p.salesNone(period(label)), ReplyKind.ANSWER, d.intent)
                val head = p.salesSummary(label, s.count, money(s.totalPaise), s.unpaidPaise.takeIf { it > 0 }?.let { money(it) })
                return AssistantReply(head + summaryLines(s), ReplyKind.ANSWER, d.intent, spoken = head)
            }
            Intent.PURCHASE_QUERY, Intent.REPORT_PURCHASES -> {
                val key = e.dateKey ?: DateKey.TODAY
                val w = DateRanges.range(key, zone(), now())
                val s = backend.purchases(w.start, w.end)
                val label = DateRanges.label(key, tamil())
                if (s.count == 0) return AssistantReply(p.purchasesNone(period(label)), ReplyKind.ANSWER, d.intent)
                val head = p.purchasesSummary(label, s.count, money(s.totalPaise), s.unpaidPaise.takeIf { it > 0 }?.let { money(it) })
                return AssistantReply(head + summaryLines(s), ReplyKind.ANSWER, d.intent, spoken = head)
            }
            Intent.EXPENSE_QUERY, Intent.REPORT_EXPENSES -> {
                val key = e.dateKey ?: DateKey.TODAY
                val w = DateRanges.range(key, zone(), now())
                val s = backend.expenses(w.start, w.end)
                val label = DateRanges.label(key, tamil())
                if (s.count == 0) return AssistantReply(p.expensesNone(period(label)), ReplyKind.ANSWER, d.intent)
                val head = p.expensesSummary(label, s.count, money(s.totalPaise))
                return AssistantReply(head + summaryLines(s), ReplyKind.ANSWER, d.intent, spoken = head)
            }
            Intent.REPORT_STOCK -> {
                val s = backend.stockSummary()
                if (s.productCount == 0) return AssistantReply(p.noProducts(), ReplyKind.ANSWER, d.intent)
                val head = p.stockReport(s.productCount, money(s.valuePaise), s.lowCount, s.outCount)
                val all = backend.products().sortedBy { it.name.lowercase() }
                val shown = all.take(20)
                val more = if (all.size > shown.size) "\n" + p.andMore(all.size - shown.size) else ""
                return AssistantReply(head + "\n" + shown.joinToString("\n") { "• ${it.name}: ${qty(it.stockMilli, it.unit)}" } + more, ReplyKind.ANSWER, d.intent, spoken = head)
            }
            Intent.CUSTOMER_BALANCE, Intent.SUPPLIER_BALANCE -> return balanceAnswer(d)
            Intent.CUSTOMER_QUERY, Intent.SUPPLIER_QUERY -> return partyAnswer(d)
            else -> return AssistantReply(p.unknown(), ReplyKind.ANSWER, d.intent)
        }
    }

    private fun stockAnswer(item: ProductRef): AssistantReply {
        lastProduct = item.name
        return AssistantReply(p.stockOf(item.name, qty(item.stockMilli, item.unit), item.isLow), ReplyKind.ANSWER, Intent.STOCK_QUERY)
    }

    private fun summaryLines(s: TxnSummary): String {
        if (s.lines.isEmpty()) return ""
        val shown = s.lines.take(6)
        val body = shown.joinToString("\n") { l -> "• ${l.label}" + (if (l.detail.isNotBlank()) " · ${l.detail}" else "") + " — ${money(l.amountPaise)}" }
        val more = if (s.lines.size > shown.size) "\n" + p.andMore(s.lines.size - shown.size) else ""
        return "\n$body$more"
    }

    private suspend fun balanceAnswer(d: Draft): AssistantReply {
        val wantSupplier = d.intent == Intent.SUPPLIER_BALANCE
        val name = if (wantSupplier) d.e.supplierName else d.e.customerName
        val first = if (wantSupplier) PartyKind.SUPPLIER else PartyKind.CUSTOMER
        if (name.isNullOrBlank()) {
            val list = backend.parties(first).filter { it.balancePaise > 0 }.sortedByDescending { it.balancePaise }
            val total = list.sumOf { it.balancePaise }
            val head = if (wantSupplier) p.totalPayable(money(total), list.size) else p.totalReceivable(money(total), list.size)
            val body = list.take(8).joinToString("\n") { "• ${it.name} — ${money(it.balancePaise)}" }
            return AssistantReply(if (body.isEmpty()) head else "$head\n$body", ReplyKind.ANSWER, d.intent, spoken = head)
        }
        for (kind in listOf(first, if (wantSupplier) PartyKind.CUSTOMER else PartyKind.SUPPLIER)) {
            val found: List<PartyRef> = when (val m = NameMatcher.match(name, backend.parties(kind)) { it.name }) {
                is Match.Exact -> listOf(m.item)
                is Match.Suggest -> listOf(m.item)
                is Match.Multiple -> m.items
                Match.None -> emptyList()
            }
            if (found.isEmpty()) continue
            val text = found.joinToString("\n") { balanceLine(it) }
            return AssistantReply(text, ReplyKind.ANSWER, d.intent)
        }
        return AssistantReply(p.partyNotFound(name), ReplyKind.ERROR, d.intent)
    }

    private fun balanceLine(it: PartyRef): String = if (it.kind == PartyKind.CUSTOMER) when {
        it.balancePaise > 0 -> p.customerOwes(it.name, money(it.balancePaise))
        it.balancePaise < 0 -> p.customerAdvance(it.name, money(-it.balancePaise))
        else -> p.customerClear(it.name)
    } else when {
        it.balancePaise > 0 -> p.youOwe(it.name, money(it.balancePaise))
        it.balancePaise < 0 -> p.youPaidAdvance(it.name, money(-it.balancePaise))
        else -> p.youOweNothing(it.name)
    }

    private suspend fun partyAnswer(d: Draft): AssistantReply {
        val wantSupplier = d.intent == Intent.SUPPLIER_QUERY
        val name = if (wantSupplier) d.e.supplierName else d.e.customerName
        if (name.isNullOrBlank()) {
            val list = backend.parties(if (wantSupplier) PartyKind.SUPPLIER else PartyKind.CUSTOMER).sortedBy { it.name.lowercase() }
            if (list.isEmpty()) return AssistantReply(if (wantSupplier) p.noSuppliers() else p.noCustomers(), ReplyKind.ANSWER, d.intent)
            val head = if (wantSupplier) p.suppliersHeader(list.size) else p.customersHeader(list.size)
            val shown = list.take(12)
            val more = if (list.size > shown.size) "\n" + p.andMore(list.size - shown.size) else ""
            return AssistantReply(head + "\n" + shown.joinToString("\n") { "• ${it.name}" } + more, ReplyKind.ANSWER, d.intent, spoken = head)
        }
        val out = ArrayList<String>()
        val kinds = if (wantSupplier) listOf(PartyKind.SUPPLIER, PartyKind.CUSTOMER) else listOf(PartyKind.CUSTOMER, PartyKind.SUPPLIER)
        for (kind in kinds) {
            val found: List<PartyRef> = when (val m = NameMatcher.match(name, backend.parties(kind)) { it.name }) {
                is Match.Exact -> listOf(m.item)
                is Match.Suggest -> listOf(m.item)
                is Match.Multiple -> m.items
                Match.None -> emptyList()
            }
            found.forEach { out.add(p.partyDetail(it.kind, it.name, it.phone, money(it.balancePaise))) }
        }
        val products: List<ProductRef> = when (val m = NameMatcher.match(name, backend.products()) { it.name }) {
            is Match.Exact -> listOf(m.item)
            is Match.Suggest -> listOf(m.item)
            is Match.Multiple -> m.items
            Match.None -> emptyList()
        }
        products.forEach { out.add(p.productDetail(it.name, qty(it.stockMilli, it.unit), money(it.sellPaise))) }
        if (out.isEmpty()) return AssistantReply(p.partyNotFound(name), ReplyKind.ERROR, d.intent)
        return AssistantReply(out.take(10).joinToString("\n"), ReplyKind.ANSWER, d.intent)
    }

    // ------------------------------------------------------------------ small helpers

    private fun money(v: Long) = Money.format(v, symbol())
    private fun qty(milli: Long, unit: String) = Qty.format(milli) + " " + Units.label(unit, milli, tamil())
    private fun period(label: String) = if (tamil()) label else label.lowercase()
    private fun yesNo(): List<String> = if (tamil()) listOf("ஆம்", "இல்லை") else listOf("Yes", "No")

    private fun perUnit(totalPaise: Long, qtyMilli: Long): Long =
        if (qtyMilli <= 0) 0L
        else BigDecimal(totalPaise).multiply(BigDecimal(1000)).divide(BigDecimal(qtyMilli), 0, RoundingMode.HALF_UP).longValueExact()

    private fun describe(d: Draft): String {
        val e = d.e
        val parts = ArrayList<String>()
        parts.add(p.confirmTitle(d.intent))
        e.quantityMilli?.let { parts.add(Qty.format(it) + (e.unit?.let { u -> " " + Units.label(u, it, tamil()) } ?: "")) }
        e.productName?.let { parts.add(it) }
        e.customerName?.let { parts.add(it) }
        e.supplierName?.let { parts.add(it) }
        e.unitPricePaise?.let { parts.add(money(it)) }
        e.amountPaise?.let { parts.add(money(it)) }
        e.expenseCategory?.let { parts.add(it) }
        return parts.filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun cleanAnswer(text: String): String {
        val t = text.trim().trim('.', '?', '!', ',', ' ')
            .replace(Regex("^(it is|its|it's|the product is|product is|name is|the name is|called)\\s+", RegexOption.IGNORE_CASE), "")
        return NameMatcher.titleCase(t)
    }

    private fun plain(text: String) = text.trim().lowercase().replace(Regex("[.!?,'’]"), "").replace(Regex("\\s+"), " ").trim()

    private val yesWords = setOf(
        "yes", "y", "ya", "yeah", "yep", "yup", "ok", "okay", "k", "confirm", "confirmed", "sure", "do it", "correct",
        "right", "proceed", "go ahead", "save", "save it", "fine", "yes please", "yes confirm", "yes do it", "yes save",
        "thats right", "that is right", "aama", "aamam", "ama", "aam", "sari", "seri", "sariya", "ok pannunga",
        "ஆம்", "ஆமா", "ஆமாம்", "சரி", "சேமி", "சேமிக்கவும்",
    )
    private val noWords = setOf(
        "no", "n", "nope", "nah", "cancel", "cancel it", "stop", "dont", "do not", "wrong", "incorrect", "never mind",
        "nevermind", "leave it", "no cancel", "no dont", "not now", "venda", "vendam", "venam", "illa", "illai",
        "வேண்டாம்", "இல்லை", "ரத்து", "ரத்து செய்",
    )

    private fun isYes(text: String) = plain(text) in yesWords
    private fun isNo(text: String) = plain(text) in noWords

    /** Index of the option the user picked: by number, by position word, or by words that match only one option. */
    private fun pick(text: String, names: List<String>): Int? {
        val t = plain(text)
        t.toIntOrNull()?.let { return if (it in 1..names.size) it - 1 else null }
        when (t) {
            "first", "first one", "the first one", "1st" -> return 0
            "second", "second one", "the second one", "2nd" -> return if (names.size > 1) 1 else null
            "third", "third one", "the third one", "3rd" -> return if (names.size > 2) 2 else null
            "last", "last one", "the last one" -> return names.size - 1
        }
        val q = NameMatcher.tokens(text)
        if (q.isEmpty()) return null
        val qKey = q.joinToString(" ")
        names.indexOfFirst { NameMatcher.key(it) == qKey }.let { if (it >= 0) return it }
        val hits = names.indices.filter { NameMatcher.tokens(names[it]).containsAll(q) }
        return if (hits.size == 1) hits[0] else null
    }
}
