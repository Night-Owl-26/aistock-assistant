package com.aistock.assistant.conversation

import com.aistock.assistant.ai.AssistantBackend
import com.aistock.assistant.ai.AssistantEngine
import com.aistock.assistant.ai.ConfirmLine
import com.aistock.assistant.ai.Intent
import com.aistock.assistant.ai.PartyKind
import com.aistock.assistant.ai.PartyRef
import com.aistock.assistant.ai.Plan
import com.aistock.assistant.ai.ProductRef
import com.aistock.assistant.ai.ReplyAction
import com.aistock.assistant.ai.llm.ToolCall
import com.aistock.assistant.ai.llm.ToolParam
import com.aistock.assistant.ai.llm.ToolSpec
import com.aistock.assistant.business.BusinessAction
import com.aistock.assistant.business.BusinessBackend
import com.aistock.assistant.business.BusinessConfig
import com.aistock.assistant.business.CustomField
import com.aistock.assistant.business.FieldType
import com.aistock.assistant.business.FollowupRef
import com.aistock.assistant.business.Insights
import com.aistock.assistant.business.Module
import com.aistock.assistant.business.StaffRef
import com.aistock.assistant.business.Templates
import com.aistock.assistant.business.WorkRef
import com.aistock.assistant.business.Workflow
import com.aistock.assistant.utils.Match
import com.aistock.assistant.utils.Money
import com.aistock.assistant.utils.NameMatcher
import com.aistock.assistant.utils.Qty
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What a business tool did in this turn, for Stocky's turn bookkeeping. */
class ToolEffects {
    var proposed = false
    val actions = ArrayList<ReplyAction>()
}

/**
 * Stocky's tools for the business platform: setup, work items (production orders, job cards, appointments, projects,
 * orders, tasks), staff, follow-ups, reminders, calls and messages, expiry and the business briefing.
 * Reading tools answer from saved records only; every change is a proposal the owner must confirm.
 */
class BusinessTools(
    private val business: BusinessBackend,
    private val backend: AssistantBackend,
    private val engine: AssistantEngine,
    private val symbol: () -> String,
    private val zone: () -> ZoneId,
    private val now: () -> Instant,
) {
    private val names = SPECS.map { it.name }.toSet()
    fun handles(name: String) = name in names

    private fun a(call: ToolCall, key: String): String? = call.args[key]?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) }
    private fun money(p: Long) = Money.format(p, symbol())
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM")
    private val timeFmt = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")
    private fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone()).let { z -> if (z.toLocalTime() == LocalTime.of(18, 0) || z.toLocalTime() == LocalTime.MIDNIGHT) z.format(dayFmt) else z.format(timeFmt) }

    /**
     * "2026-10-18T18:00", "2026-10-18 18:00" or "2026-10-18". A date alone means [defaultTime] that day.
     * @return null when it cannot be read
     */
    fun parseWhen(text: String?, defaultTime: LocalTime = LocalTime.of(18, 0)): Long? {
        val t = text?.trim()?.replace(' ', 'T')?.removeSuffix("Z") ?: return null
        return try {
            LocalDateTime.parse(t.take(16)).atZone(zone()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            try { LocalDate.parse(t.take(10)).atTime(defaultTime).atZone(zone()).toInstant().toEpochMilli() } catch (x: Exception) { null }
        }
    }

    private fun module(text: String?, cfg: BusinessConfig): Module? {
        val m = Module.of(text) ?: Module.entries.firstOrNull { text != null && (cfg.term(it.key, "").equals(text, true) || cfg.term(it.key + "_tab", "").equals(text, true)) }
        return m?.takeIf { it.isWork }
    }

    private fun describe(w: WorkRef, cfg: BusinessConfig): String {
        val parts = ArrayList<String>()
        parts.add("${w.number} | ${w.title} | ${w.status}" + (w.stage?.let { " / stage $it" } ?: ""))
        w.customerName?.let { parts.add(cfg.term("customer", "customer").lowercase() + " $it") }
        w.productName?.let { parts.add("product $it") }
        w.quantityMilli?.let { parts.add("done ${Qty.format(w.doneMilli)} of ${Qty.format(it)}") }
        w.staffName?.let { parts.add("assigned to $it") }
        w.dueAt?.let { parts.add("due ${date(it)}" + if (!w.closed && it < now().toEpochMilli()) " (OVERDUE)" else "") }
        w.startAt?.let { parts.add("at ${date(it)}") }
        w.amountPaise?.let { parts.add(money(it)) }
        if (w.priority > 0) parts.add("HIGH PRIORITY")
        return parts.joinToString(" | ")
    }

    private suspend fun customer(name: String?): Pair<PartyRef?, String?> {
        if (name == null) return null to null
        return when (val m = NameMatcher.match(name, backend.parties(PartyKind.CUSTOMER)) { it.name }) {
            is Match.Exact -> m.item to null
            is Match.Suggest -> m.item to null
            is Match.Multiple -> throw Ask("Several customers match \"$name\": ${m.items.take(5).joinToString(", ") { it.name }}. Ask the owner which one.")
            Match.None -> null to name.trim()
        }
    }

    private suspend fun product(name: String?): ProductRef? {
        if (name == null) return null
        return when (val m = NameMatcher.match(name, backend.products()) { it.name }) {
            is Match.Exact -> m.item
            is Match.Suggest -> m.item
            is Match.Multiple -> throw Ask("Several products match \"$name\": ${m.items.take(5).joinToString(", ") { it.name }}. Ask which one.")
            Match.None -> throw Ask("No product called \"$name\". Ask the owner, or leave the product out.")
        }
    }

    private suspend fun staff(name: String?): StaffRef? {
        if (name == null) return null
        val all = business.staff()
        return when (val m = NameMatcher.match(name, all) { it.name }) {
            is Match.Exact -> m.item
            is Match.Suggest -> m.item
            is Match.Multiple -> throw Ask("Several staff match \"$name\": ${m.items.joinToString(", ") { it.name }}. Ask which one.")
            Match.None -> throw Ask("No staff member called \"$name\" is saved" + (if (all.isEmpty()) " (no staff saved yet; offer propose_add_staff)" else ": staff are ${all.joinToString(", ") { it.name }}") + ".")
        }
    }

    /** By number (PO-0007, "7") or by words of the title / customer. */
    private suspend fun findWork(ref: String, cfg: BusinessConfig): WorkRef {
        val all = business.work(null, openOnly = false, limit = 500)
        val digits = ref.filter { it.isDigit() }.trimStart('0')
        all.firstOrNull { it.number.equals(ref.trim(), true) }?.let { return it }
        if (digits.isNotEmpty() && ref.trim().length <= 10) all.filter { it.number.filter { c -> c.isDigit() }.trimStart('0') == digits }.let { if (it.size == 1) return it[0] }
        val open = all.filter { !it.closed }
        val words = NameMatcher.tokens(ref).toSet()
        val hits = open.filter { w -> val t = NameMatcher.tokens(w.title + " " + (w.customerName ?: "") + " " + (w.productName ?: "")).toSet(); words.isNotEmpty() && t.containsAll(words) }
            .ifEmpty { open.filter { w -> val t = NameMatcher.tokens(w.title + " " + (w.customerName ?: "")).toSet(); words.count { it in t } >= maxOf(1, words.size - 1) } }
        return when (hits.size) {
            1 -> hits[0]
            0 -> throw Ask("No open item matches \"$ref\". Use list_work to see them.")
            else -> throw Ask("Several items match \"$ref\": " + hits.take(6).joinToString("; ") { describe(it, cfg) } + ". Ask which one.")
        }
    }

    private suspend fun findFollowup(ref: String): FollowupRef {
        val pending = business.followups(pendingOnly = true, limit = 200)
        val words = NameMatcher.tokens(ref).toSet()
        val hits = pending.filter { f -> val t = NameMatcher.tokens(f.personName + " " + f.note + " " + f.kind).toSet(); words.isNotEmpty() && words.all { it in t } }
            .ifEmpty { pending.filter { NameMatcher.key(it.personName) == NameMatcher.key(ref) } }
        return when (hits.size) {
            1 -> hits[0]
            0 -> throw Ask("No pending follow-up matches \"$ref\".")
            else -> throw Ask("Several follow-ups match: " + hits.take(5).joinToString("; ") { "${it.personName} – ${it.kind} ${it.note} (${date(it.dueAt)})" } + ". Ask which one.")
        }
    }

    private class Ask(msg: String) : Exception(msg)

    private fun propose(fx: ToolEffects, action: BusinessAction, question: String, lines: List<ConfirmLine>, done: String, intent: Intent = Intent.UNKNOWN): String {
        engine.proposePlan(intent, Plan.Business(action, done), question, lines)
        fx.proposed = true
        return "PROPOSED (not saved yet): $question The app is showing it. Ask the owner to confirm."
    }

    suspend fun run(call: ToolCall, fx: ToolEffects): String = try {
        runInner(call, fx)
    } catch (e: Ask) {
        "NEED MORE FROM THE OWNER: " + e.message
    }

    private suspend fun runInner(call: ToolCall, fx: ToolEffects): String {
        val cfg = business.config()
        val nowMs = now().toEpochMilli()
        when (call.name) {
            "business_setup_info" -> {
                val sb = StringBuilder()
                sb.append(if (business.configured()) "CONFIGURED as ${cfg.businessType}" else "NOT CONFIGURED YET (using the shop default)").append('\n')
                sb.append("Modules on: ").append(cfg.modules.joinToString(", ") { it.key }).append('\n')
                for (m in cfg.workModules) {
                    val w = cfg.workflow(m)
                    sb.append("${m.key} (called \"${cfg.term(m.key, m.key)}\"): statuses ${w.statuses.joinToString("/")}").append(if (w.stages.isNotEmpty()) "; stages ${w.stages.joinToString(" > ")}" else "")
                    val f = cfg.fields(m); if (f.isNotEmpty()) sb.append("; fields ${f.joinToString(", ") { it.key + "=" + it.label }}")
                    sb.append('\n')
                }
                sb.append("TEMPLATES you can propose: ").append(Templates.ALL.joinToString("; ") { "${it.type} = ${it.nameEn}: ${it.descriptionEn}" + if (it.limitsEn.isNotBlank()) " (Not built yet: ${it.limitsEn})" else "" })
                return sb.toString()
            }

            "propose_business_setup" -> {
                val type = a(call, "business_type") ?: return "Say which business_type."
                // Changing the same kind of business keeps the owner's own words, steps and details.
                val base = (if (type == cfg.businessType && business.configured()) cfg else Templates.ALL.firstOrNull { it.type == type }?.config)
                    ?: return "Unknown business_type. Use one of: ${Templates.TYPES.joinToString()}."
                var modules = base.modules
                a(call, "modules")?.let { list ->
                    val ms = list.split(',', ';').mapNotNull { Module.of(it) }.toSet()
                    if (ms.isEmpty()) return "No known modules in \"$list\". Known: ${Module.entries.joinToString { it.key }}."
                    modules = ms
                }
                val flows = LinkedHashMap(base.workflows.filterKeys { it in modules })
                for (m in modules.filter { it.isWork && it !in flows }) flows[m] = Templates.defaultWorkflow(m)
                a(call, "stages")?.let { spec ->
                    // "production=Cutting|Stitching|Ironing|Packing; service_tickets=..."
                    for (part in spec.split(';')) {
                        val m = Module.of(part.substringBefore('=')) ?: continue
                        val st = part.substringAfter('=', "").split('|', ',').map { it.trim() }.filter { it.isNotEmpty() }
                        if (st.isNotEmpty()) flows[m] = (flows[m] ?: Templates.defaultWorkflow(m)).copy(stages = st)
                    }
                }
                val terms = LinkedHashMap(base.terminology)
                a(call, "names")?.let { spec -> for (part in spec.split(';')) { val k = part.substringBefore('=').trim(); val v = part.substringAfter('=', "").trim(); if (k.isNotEmpty() && v.isNotEmpty()) terms[k] = v.take(40) } }
                val fields = ArrayList(base.customFields.filter { it.module in modules })
                a(call, "extra_fields")?.let { spec ->
                    // "production:colour:Colour:text; service_tickets:warranty:Under warranty:boolean"
                    for (part in spec.split(';')) {
                        val p = part.split(':').map { it.trim() }
                        val m = Module.of(p.getOrNull(0)) ?: continue
                        val key = p.getOrNull(1)?.lowercase()?.replace(Regex("[^a-z0-9_]"), "_")?.trim('_') ?: continue
                        val label = p.getOrNull(2) ?: key
                        val type = FieldType.of(p.getOrNull(3)) ?: FieldType.TEXT
                        if (fields.none { it.module == m && it.key == key }) fields.add(CustomField(m, key, label, type, if (type == FieldType.CHOICE) p.drop(4).flatMap { it.split('|') } else emptyList()))
                    }
                }
                val config = BusinessConfig(type, modules, terms, base.units, flows, fields, cfg.language)
                val errors = config.validate()
                if (errors.isNotEmpty()) return "Cannot set up like that: ${errors.joinToString("; ")}."
                val lines = ArrayList<ConfirmLine>()
                lines.add(ConfirmLine("Business", Templates.of(type).nameEn))
                lines.add(ConfirmLine("Modules", modules.sortedBy { it.ordinal }.joinToString(", ") { config.term(it.key + "_tab", config.term(it.key, it.key.replace('_', ' '))) }))
                for ((m, w) in flows) if (w.stages.isNotEmpty()) lines.add(ConfirmLine(config.term(m.key, m.key) + " steps", w.stages.joinToString(" › ")))
                if (fields.isNotEmpty()) lines.add(ConfirmLine("Extra details", fields.joinToString(", ") { it.label }))
                return propose(fx, BusinessAction.SetupBusiness(config), "Set up the app for ${Templates.of(type).nameEn.lowercase()} like this?", lines, "Done. The app is now set up for ${Templates.of(type).nameEn.lowercase()}.")
            }

            "list_work" -> {
                val m = a(call, "module")?.let { module(it, cfg) ?: return "Unknown module \"$it\". This business uses: ${cfg.workModules.joinToString { it.key }}." }
                val status = a(call, "status")?.lowercase()
                var items = business.work(m, openOnly = status == null || status == "open" || status == "overdue", limit = 300)
                if (status == "overdue") items = items.filter { it.dueAt != null && it.dueAt < nowMs }
                else if (status != null && status != "open" && status != "all") items = items.filter { it.status.equals(status, true) || (status == "done" && it.closed) || it.stage.equals(status, true) }
                a(call, "staff")?.let { s -> val st = staff(s); items = items.filter { it.staffId == st?.id } }
                a(call, "customer")?.let { c -> items = items.filter { it.customerName != null && NameMatcher.key(it.customerName).contains(NameMatcher.key(c)) } }
                a(call, "query")?.let { q -> val ws = NameMatcher.tokens(q); items = items.filter { w -> val t = NameMatcher.tokens(w.title + " " + w.number + " " + (w.productName ?: "")); ws.all { it in t } } }
                val limit = a(call, "limit")?.toDoubleOrNull()?.toInt()?.coerceIn(1, 40) ?: 20
                if (items.isEmpty()) return "None found." + if (cfg.workModules.isEmpty()) " No work modules are switched on for this business." else ""
                return "COUNT: ${items.size}\n" + items.take(limit).joinToString("\n") { "- " + describe(it, cfg) } + if (items.size > limit) "\n(${items.size - limit} more)" else ""
            }

            "propose_new_work" -> {
                val m = module(a(call, "module"), cfg) ?: return "Say which module: ${cfg.workModules.joinToString { "${it.key} (${cfg.term(it.key, it.key)})" }}."
                if (!cfg.has(m)) return "The ${m.key} module is not switched on for this business. Offer to switch it on with propose_business_setup."
                val title = a(call, "title") ?: return "NEED MORE FROM THE OWNER: a short title."
                val (cust, newCust) = customer(a(call, "customer"))
                val prod = product(a(call, "product"))
                val st = staff(a(call, "staff"))
                val flow = cfg.workflow(m)
                val stage = a(call, "stage")?.let { flow.stage(it) ?: return "\"$it\" is not a step. Steps: ${flow.stages.joinToString()}." } ?: flow.stages.firstOrNull()
                val qty = a(call, "quantity")?.let { Qty.parse(it) ?: return "Quantity \"$it\" is not a number." }
                val amount = a(call, "amount")?.let { Money.parse(it) ?: return "Amount \"$it\" is not a number." }
                val due = a(call, "due")?.let { parseWhen(it) ?: return "Due date \"$it\" must be like 2026-10-20 or 2026-10-20T18:00." }
                val start = a(call, "start")?.let { parseWhen(it, LocalTime.of(10, 0)) ?: return "Start \"$it\" must be like 2026-10-20T11:30." }
                if (m == Module.APPOINTMENTS && start == null) return "NEED MORE FROM THE OWNER: the appointment date and time."
                val fields = parseFields(a(call, "fields"), cfg.fields(m)) ?: return "Unknown extra detail. This module's details are: ${cfg.fields(m).joinToString { it.key + " (" + it.label + ")" }}."
                val lines = ArrayList<ConfirmLine>()
                lines.add(ConfirmLine(cfg.term(m.key, m.key), title))
                (cust?.name ?: newCust)?.let { lines.add(ConfirmLine(cfg.term("customer", "Customer"), it + if (newCust != null) " (new)" else "")) }
                prod?.let { lines.add(ConfirmLine("Product", it.name)) }
                qty?.let { lines.add(ConfirmLine("Quantity", Qty.format(it) + (prod?.unit?.let { u -> " " + u.lowercase() } ?: ""))) }
                stage?.let { lines.add(ConfirmLine("Step", it)) }
                st?.let { lines.add(ConfirmLine("Assigned to", it.name)) }
                start?.let { lines.add(ConfirmLine("When", date(it))) }
                due?.let { lines.add(ConfirmLine("Due", date(it))) }
                amount?.let { lines.add(ConfirmLine("Amount", money(it))) }
                for ((k, v) in fields) lines.add(ConfirmLine(cfg.fields(m).first { it.key == k }.label, v))
                val high = a(call, "priority")?.lowercase() in setOf("high", "urgent", "1")
                if (high) lines.add(ConfirmLine("Priority", "High"))
                val action = BusinessAction.NewWork(m, title.take(120), cust, newCust, prod, st, flow.openStatuses.first(), stage, qty, amount, start, due, if (high) 1 else 0, fields, a(call, "notes").orEmpty().take(500), a(call, "request_id"))
                return propose(fx, action, "Create this ${cfg.term(m.key, m.key).lowercase()}?", lines, "Saved: ${cfg.term(m.key, m.key).lowercase()} \"${title.take(60)}\".")
            }

            "propose_update_work" -> {
                val item = findWork(a(call, "ref") ?: return "Say which item (number or name).", cfg)
                val flow: Workflow = cfg.workflow(item.module)
                val status = a(call, "status")?.let { flow.status(it) ?: return "\"$it\" is not a status here. Statuses: ${flow.statuses.joinToString()}." }
                val stage = a(call, "stage")?.let { s -> if (s.equals("next", true)) (flow.nextStage(item.stage) ?: return "${item.number} is already at the last step (${item.stage}).") else flow.stage(s) ?: return "\"$s\" is not a step. Steps: ${flow.stages.joinToString()}." }
                val add = a(call, "done_quantity")?.let { Qty.parse(it) ?: return "done_quantity must be a number." }
                if (add != null && add <= 0) return "done_quantity must be more than 0."
                val st = staff(a(call, "staff"))
                val due = a(call, "due")?.let { parseWhen(it) ?: return "Due date must be like 2026-10-20." }
                val note = a(call, "note")?.take(300)
                val fields = parseFields(a(call, "fields"), cfg.fields(item.module)) ?: return "Unknown extra detail. Details: ${cfg.fields(item.module).joinToString { it.key }}."
                if (status == null && stage == null && add == null && st == null && due == null && note == null && fields.isEmpty()) return "Nothing to change. Say what changed."
                if (item.closed && status == null) return "${item.number} is already ${item.status}. Change its status first if it should be reopened."
                val lines = ArrayList<ConfirmLine>()
                lines.add(ConfirmLine(cfg.term(item.module.key, item.module.key), "${item.number} ${item.title}"))
                status?.let { lines.add(ConfirmLine("Status", "${item.status} → $it")) }
                stage?.let { lines.add(ConfirmLine("Step", "${item.stage ?: "–"} → $it")) }
                add?.let { lines.add(ConfirmLine("Done", "${Qty.format(item.doneMilli)} + ${Qty.format(it)} = ${Qty.format(item.doneMilli + it)}" + (item.quantityMilli?.let { q -> " of ${Qty.format(q)}" } ?: ""))) }
                if (add != null && item.quantityMilli != null && item.doneMilli + add > item.quantityMilli) lines.add(ConfirmLine("Note", "More than the ordered ${Qty.format(item.quantityMilli)}"))
                st?.let { lines.add(ConfirmLine("Assigned to", it.name)) }
                due?.let { lines.add(ConfirmLine("Due", date(it))) }
                for ((k, v) in fields) lines.add(ConfirmLine(cfg.fields(item.module).first { it.key == k }.label, v))
                note?.let { lines.add(ConfirmLine("Note", it)) }
                return propose(fx, BusinessAction.UpdateWork(item, status, stage, add, st, due, note, fields), "Update ${item.number}?", lines, "Updated ${item.number}.")
            }

            "list_staff" -> {
                val s = business.staff()
                return if (s.isEmpty()) "No staff saved yet." else s.joinToString("\n") { "- ${it.name}" + (if (it.role.isNotBlank()) " (${it.role})" else "") + (if (it.phone.isNotBlank()) " ${it.phone}" else "") }
            }

            "propose_add_staff" -> {
                val name = a(call, "name") ?: return "NEED MORE FROM THE OWNER: the staff member's name."
                if (business.staff().any { NameMatcher.key(it.name) == NameMatcher.key(name) }) return "${name} is already saved as staff."
                val phone = a(call, "phone").orEmpty().filter { it.isDigit() || it == '+' }.take(16)
                val role = a(call, "role").orEmpty().take(40)
                return propose(fx, BusinessAction.AddStaff(name.take(60), phone, role), "Add $name as staff?", listOfNotNull(ConfirmLine("Name", name), role.takeIf { it.isNotEmpty() }?.let { ConfirmLine("Role", it) }, phone.takeIf { it.isNotEmpty() }?.let { ConfirmLine("Phone", it) }), "Added $name.")
            }

            "list_followups" -> {
                val which = a(call, "status")?.lowercase() ?: "pending"
                val list = business.followups(pendingOnly = which == "pending", limit = 100).let { l -> if (which == "done") l.filter { it.status == "DONE" } else l }
                if (list.isEmpty()) return "No ${which} follow-ups."
                return "COUNT: ${list.size}\n" + list.take(25).joinToString("\n") { f -> "- ${f.personName}: ${f.kind.lowercase()}${if (f.note.isNotBlank()) " – ${f.note}" else ""} | ${date(f.dueAt)}${if (f.status == "PENDING" && f.dueAt < nowMs) " (OVERDUE)" else ""} | ${f.status}${if (f.result.isNotBlank()) " | result: ${f.result}" else ""}" }
            }

            "propose_followup" -> {
                val who = a(call, "person") ?: return "NEED MORE FROM THE OWNER: who to follow up with."
                val kind = a(call, "kind")?.uppercase()?.takeIf { it in KINDS } ?: "CALL"
                val due = parseWhen(a(call, "due"), LocalTime.of(10, 0)) ?: return "NEED MORE FROM THE OWNER: when (date and time)."
                if (due < nowMs - 60_000) return "That time has already passed. Ask for a later time."
                val (cust, newName) = customer(who)
                val note = a(call, "note").orEmpty().take(200)
                val remind = a(call, "remind")?.lowercase() != "false"
                val lines = listOfNotNull(ConfirmLine("With", cust?.name ?: newName.orEmpty()), ConfirmLine("What", kind.lowercase() + if (note.isNotEmpty()) " – $note" else ""), ConfirmLine("When", date(due)), if (remind) ConfirmLine("Reminder", "Notification at that time") else null)
                return propose(fx, BusinessAction.NewFollowup(cust, cust?.name ?: newName.orEmpty(), cust?.phone.orEmpty(), kind, note, due, remind, null, a(call, "request_id")),
                    "Add this follow-up?", lines, "Follow-up saved for ${date(due)}.")
            }

            "propose_complete_followup" -> {
                val f = findFollowup(a(call, "ref") ?: return "Say which follow-up (person or topic).")
                val result = a(call, "result") ?: return "NEED MORE FROM THE OWNER: what happened."
                val next = a(call, "next_due")?.let { parseWhen(it, LocalTime.of(10, 0)) ?: return "next_due must be like 2026-10-20T10:00." }
                val lines = listOfNotNull(ConfirmLine("Follow-up", "${f.personName} – ${f.kind.lowercase()} ${f.note}".trim()), ConfirmLine("Result", result), next?.let { ConfirmLine("Next", date(it)) })
                return propose(fx, BusinessAction.CompleteFollowup(f, result.take(300), next, a(call, "next_note")?.take(200)), "Mark this follow-up done?", lines, "Follow-up with ${f.personName} marked done." + (next?.let { " Next one on ${date(it)}." } ?: ""))
            }

            "propose_reminder" -> {
                val text = a(call, "text") ?: return "NEED MORE FROM THE OWNER: what to remind about."
                val due = parseWhen(a(call, "when"), LocalTime.of(9, 0)) ?: return "NEED MORE FROM THE OWNER: when."
                if (due < nowMs - 60_000) return "That time has already passed. Ask for a later time."
                return propose(fx, BusinessAction.NewReminder(text.take(200), due), "Remind you at ${date(due)}?", listOf(ConfirmLine("Reminder", text), ConfirmLine("When", date(due))), "Reminder set for ${date(due)}.")
            }

            "customer_history" -> {
                val who = a(call, "customer") ?: return "Say which customer."
                val (cust, _) = customer(who)
                val sb = StringBuilder()
                if (cust != null) {
                    sb.append("${cust.name}${if (cust.phone.isNotBlank()) " (${cust.phone})" else ""}; balance owed to you ${money(cust.balancePaise)}\n")
                    sb.append("Recent activity:\n").append(backend.recentActivity(10, cust.id).joinToString("\n") { "- $it" }.ifBlank { "- none" }).append('\n')
                } else sb.append("\"$who\" is not a saved customer.\n")
                val fu = business.followupsFor(cust?.id, cust?.name ?: who)
                if (fu.isNotEmpty()) sb.append("Follow-ups:\n").append(fu.take(8).joinToString("\n") { "- ${date(it.dueAt)} ${it.kind.lowercase()} ${it.note} [${it.status}${if (it.result.isNotBlank()) ": " + it.result else ""}]" }).append('\n')
                val c = business.contactsFor(cust?.id, cust?.name ?: who)
                if (c.isNotEmpty()) sb.append("Contacts:\n").append(c.take(8).joinToString("\n") { "- ${date(it.at)} ${it.channel.lowercase()}: ${it.summary}" })
                return sb.toString().trim()
            }

            "draft_message" -> {
                val who = a(call, "customer") ?: return "Say who the message is for."
                val text = a(call, "message") ?: return "Write the message text in the message argument."
                val (cust, _) = customer(who)
                val phone = cust?.phone.orEmpty()
                val name = cust?.name ?: who
                if (phone.isBlank()) fx.actions.add(ReplyAction("SHARE", "Share message", text = text.take(1000), person = name, customerId = cust?.id))
                else {
                    fx.actions.add(ReplyAction("WHATSAPP", "Open in WhatsApp", phone, text.take(1000), name, cust?.id))
                    fx.actions.add(ReplyAction("SMS", "Open in SMS", phone, text.take(1000), name, cust?.id))
                }
                return "DRAFT READY (NOT SENT): the app shows the message with buttons to open WhatsApp or SMS; the owner presses send there. Never say it was sent." +
                    if (phone.isBlank()) " No phone number is saved for $name, so only sharing is possible." else ""
            }

            "call_customer" -> {
                val who = a(call, "customer") ?: return "Say who to call."
                val (cust, _) = customer(who)
                val phone = cust?.phone.orEmpty().ifBlank { business.staff().firstOrNull { NameMatcher.key(it.name) == NameMatcher.key(who) }?.phone.orEmpty() }
                if (phone.isBlank()) return "No phone number is saved for $who. Ask for the number or suggest adding it to the customer."
                fx.actions.add(ReplyAction("CALL", "Call ${cust?.name ?: who}", phone, person = cust?.name ?: who, customerId = cust?.id))
                return "CALL BUTTON SHOWN (not dialled): the owner taps it to open the phone's dialler. After the call, ask how it went and offer propose_call_result."
            }

            "propose_call_result" -> {
                val who = a(call, "customer") ?: return "Say who was called."
                val result = a(call, "result") ?: return "NEED MORE FROM THE OWNER: how the call went."
                val (cust, newName) = customer(who)
                val name = cust?.name ?: newName ?: who
                val pending = business.followups(pendingOnly = true, limit = 200).firstOrNull { (cust != null && it.customerId == cust.id) || NameMatcher.key(it.personName) == NameMatcher.key(name) }
                val next = a(call, "next_due")?.let { parseWhen(it, LocalTime.of(10, 0)) ?: return "next_due must be like 2026-10-20T10:00." }
                val lines = listOfNotNull(ConfirmLine("Call with", name), ConfirmLine("Result", result), pending?.let { ConfirmLine("Closes follow-up", "${it.kind.lowercase()} ${it.note}".trim()) }, next?.let { ConfirmLine("Next follow-up", date(it)) })
                return propose(fx, BusinessAction.CallResult(cust, name, result.take(300), pending, next, a(call, "next_note")?.take(200)), "Save this call?", lines, "Call with $name saved." + (next?.let { " Next follow-up ${date(it)}." } ?: ""))
            }

            "propose_expiry_batch" -> {
                val prod = product(a(call, "product") ?: return "Say which product.") ?: return "Say which product."
                val qty = a(call, "quantity")?.let { Qty.parse(it) } ?: return "NEED MORE FROM THE OWNER: how many in this batch."
                val exp = parseWhen(a(call, "expiry"), LocalTime.MIDNIGHT) ?: return "NEED MORE FROM THE OWNER: the expiry date."
                val batch = a(call, "batch").orEmpty().take(40)
                return propose(fx, BusinessAction.AddBatch(prod, batch, exp, qty), "Note this batch's expiry?", listOfNotNull(ConfirmLine("Product", prod.name), ConfirmLine("Quantity", Qty.format(qty) + " " + prod.unit.lowercase()), ConfirmLine("Expires", date(exp)), batch.takeIf { it.isNotEmpty() }?.let { ConfirmLine("Batch", it) }), "Expiry noted for ${prod.name}.")
            }

            "expiring_soon" -> {
                val days = a(call, "days")?.toDoubleOrNull()?.toInt()?.coerceIn(1, 365) ?: 7
                val list = business.expiring(nowMs + days * 86_400_000L)
                return if (list.isEmpty()) "Nothing recorded as expiring within $days days." else list.joinToString("\n") { "- ${it.productName} ${Qty.format(it.quantityMilli)} ${it.unit.lowercase()} expires ${date(it.expiryDate)}" + (if (it.batchNo.isNotBlank()) " (batch ${it.batchNo})" else "") + if (it.expiryDate < nowMs) " EXPIRED" else "" }
            }

            "business_briefing" -> {
                val days = when (a(call, "period")?.lowercase()) { "today" -> 1; "month" -> 30; else -> 7 }
                return Insights.forModel(Insights.build(business.insightsInput(nowMs, days), symbol()))
            }
        }
        return "Unknown tool."
    }

    private fun parseFields(spec: String?, defs: List<CustomField>): Map<String, String>? {
        if (spec.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in spec.split(';')) {
            if (part.isBlank()) continue
            val k = part.substringBefore('=').trim(); val v = part.substringAfter('=', "").trim()
            val def = defs.firstOrNull { it.key.equals(k, true) || it.label.equals(k, true) } ?: return null
            if (v.isEmpty()) continue
            when (def.type) {
                FieldType.NUMBER -> if (v.replace(",", "").toBigDecimalOrNull() == null) return null
                FieldType.CHOICE -> if (def.options.none { it.equals(v, true) }) return null
                else -> {}
            }
            out[def.key] = v.take(200)
        }
        return out
    }

    companion object {
        private val KINDS = setOf("CALL", "MESSAGE", "VISIT", "PAYMENT", "OTHER")
        private fun s(n: String, d: String, req: Boolean = false) = ToolParam(n, "string", d, req)
        private fun n(n: String, d: String, req: Boolean = false) = ToolParam(n, "number", d, req)
        private const val WHEN = "Local date-time as 2026-10-20T18:00 (or just 2026-10-20). Work out words like naalaikku, tomorrow evening, next Monday from NOW."

        val SPECS: List<ToolSpec> = listOf(
            ToolSpec("business_setup_info", "How this business is set up: type, modules, its words for things, process steps, extra details, and the templates available."),
            ToolSpec("propose_business_setup", "Propose setting up (or changing) the app for the owner's kind of business. Ask about their real process first instead of assuming.", listOf(
                s("business_type", "One of: ${Templates.TYPES.joinToString()}.", true),
                s("modules", "Optional comma list to switch on instead of the template's: ${Module.entries.joinToString { it.key }}."),
                s("stages", "Optional process steps the owner described, e.g. 'production=Cutting|Stitching|Ironing|Packing'."),
                s("names", "Optional words the owner uses, e.g. 'production=Lot;customer=Buyer'."),
                s("extra_fields", "Optional extra details to record, e.g. 'production:colour:Colour:text; service_tickets:warranty:Under warranty:boolean'."))),
            ToolSpec("list_work", "List production orders, job cards, appointments, projects, orders or tasks with status, step, assignee and due date.", listOf(
                s("module", "production, service_tickets, appointments, projects, orders or tasks. Empty for all."),
                s("status", "open (default), overdue, done, all, or an exact status or step."),
                s("staff", "Only items assigned to this person."), s("customer", "Only this customer's items."), s("query", "Words in the title or product."), n("limit", "How many, up to 40."))),
            ToolSpec("propose_new_work", "Propose creating a production order, job card, appointment, project, order or task.", listOf(
                s("module", "production, service_tickets, appointments, projects, orders or tasks.", true), s("title", "Short title in the owner's words.", true),
                s("customer", "Customer / buyer, if said."), s("product", "Product, if it is about one."), n("quantity", "Quantity ordered, if said."),
                n("amount", "Amount in rupees (estimate, fee, order value), if said."), s("due", WHEN), s("start", "For appointments: $WHEN"),
                s("staff", "Who it is assigned to, if said."), s("stage", "Starting step, if not the first."), s("priority", "high if urgent."),
                s("fields", "Extra details as key=value;key=value using this business's detail keys."), s("notes", "Notes."),
                s("request_id", "Leave empty."))),
            ToolSpec("propose_update_work", "Propose changing an item: move to a step (or 'next'), change status, record quantity done, assign, change due date, add a note.", listOf(
                s("ref", "Item number (PO-0007) or words from its title or customer.", true), s("status", "New status."), s("stage", "New step, or 'next'."),
                n("done_quantity", "Quantity finished now (added to what is done)."), s("staff", "Assign to."), s("due", WHEN), s("note", "Note."),
                s("fields", "Extra details key=value;key=value."))),
            ToolSpec("list_staff", "List the staff members."),
            ToolSpec("propose_add_staff", "Propose adding a staff member.", listOf(s("name", "Name.", true), s("role", "Role, e.g. tailor, technician."), s("phone", "Phone number."))),
            ToolSpec("list_followups", "List customer follow-ups.", listOf(ToolParam("status", "string", "pending (default), done or all.", options = listOf("pending", "done", "all")))),
            ToolSpec("propose_followup", "Propose a follow-up with a customer or anyone (call, message, visit, payment), with a reminder.", listOf(
                s("person", "Who.", true), ToolParam("kind", "string", "What to do.", options = listOf("CALL", "MESSAGE", "VISIT", "PAYMENT", "OTHER")),
                s("due", WHEN, true), s("note", "About what."), ToolParam("remind", "boolean", "Notify at that time (default true)."), s("request_id", "Leave empty."))),
            ToolSpec("propose_complete_followup", "Propose marking a pending follow-up done with its result, and optionally book the next one.", listOf(
                s("ref", "Person or topic of the follow-up.", true), s("result", "What happened.", true), s("next_due", WHEN), s("next_note", "Next follow-up about what."))),
            ToolSpec("propose_reminder", "Propose a reminder notification for the owner.", listOf(s("text", "What to remind.", true), s("when", WHEN, true))),
            ToolSpec("customer_history", "A customer's balance, recent purchases and payments, follow-ups and contacts.", listOf(s("customer", "Customer name.", true))),
            ToolSpec("draft_message", "Prepare a message to a customer. You write the text (in the owner's language). The app shows it with WhatsApp/SMS buttons; it is NOT sent until the owner sends it.", listOf(
                s("customer", "Who.", true), s("message", "The full message text.", true))),
            ToolSpec("call_customer", "Show a button that opens the phone dialler with the customer's number. It does not dial by itself.", listOf(s("customer", "Who.", true))),
            ToolSpec("propose_call_result", "Propose saving how a call went; closes that person's pending follow-up and can book the next.", listOf(
                s("customer", "Who was called.", true), s("result", "What happened.", true), s("next_due", WHEN), s("next_note", "Next follow-up about what."))),
            ToolSpec("propose_expiry_batch", "Propose noting a batch's expiry date (grocery, food, medicine).", listOf(
                s("product", "Product.", true), n("quantity", "How many in the batch.", true), s("expiry", "Expiry date 2026-12-31.", true), s("batch", "Batch number."))),
            ToolSpec("expiring_soon", "Batches that expire within some days.", listOf(n("days", "Days ahead, default 7."))),
            ToolSpec("business_briefing", "A checked summary with suggestions: sales compared with the previous period, estimated margin, fast and slow sellers, low stock with reorder amounts, price problems, expense jumps, missing entries, overdue work and follow-ups, expiry.", listOf(
                ToolParam("period", "string", "today, week (default) or month.", options = listOf("today", "week", "month")))),
        )
    }
}
