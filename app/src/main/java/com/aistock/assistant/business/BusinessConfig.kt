package com.aistock.assistant.business

/** Everything that can be switched on for a business. The same app serves every kind of business through these. */
enum class Module(val key: String, val isWork: Boolean = false) {
    INVENTORY("inventory"), SALES("sales"), PURCHASES("purchases"), EXPENSES("expenses"),
    CUSTOMERS("customers"), SUPPLIERS("suppliers"),
    ORDERS("orders", true), PRODUCTION("production", true), SERVICE_TICKETS("service_tickets", true),
    APPOINTMENTS("appointments", true), PROJECTS("projects", true), TASKS("tasks", true),
    FOLLOWUPS("followups"), STAFF("staff");

    companion object {
        fun of(key: String?): Module? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }
        val WORK = entries.filter { it.isWork }
    }
}

enum class FieldType(val key: String) { TEXT("text"), NUMBER("number"), DATE("date"), CHOICE("choice"), BOOLEAN("boolean");
    companion object { fun of(k: String?) = entries.firstOrNull { it.key == k } }
}

data class CustomField(val module: Module, val key: String, val label: String, val type: FieldType, val options: List<String> = emptyList())

/**
 * How work moves for one module. [statuses] in order; [done] are the statuses that close an item;
 * [stages] are optional process steps (cutting, stitching ...) that an open item moves through.
 */
data class Workflow(val statuses: List<String>, val done: List<String>, val stages: List<String> = emptyList()) {
    val openStatuses: List<String> get() = statuses.filter { s -> done.none { it.equals(s, true) } }
    fun isDone(status: String) = done.any { it.equals(status, true) }
    fun status(name: String?): String? = name?.let { n -> statuses.firstOrNull { it.equals(n.trim(), true) } ?: statuses.firstOrNull { it.lowercase().startsWith(n.trim().lowercase()) } }
    fun stage(name: String?): String? = name?.let { n -> stages.firstOrNull { it.equals(n.trim(), true) } ?: stages.firstOrNull { it.lowercase().startsWith(n.trim().lowercase()) } }
    fun nextStage(current: String?): String? {
        if (stages.isEmpty()) return null
        val i = stages.indexOfFirst { it.equals(current, true) }
        return if (i < 0) stages.first() else stages.getOrNull(i + 1)
    }
}

/**
 * The versioned description of one business: its type, the modules it uses, its own words for things,
 * its process steps and extra fields. It is data, validated before saving; nothing in it can run code or SQL.
 */
data class BusinessConfig(
    val businessType: String,
    val modules: Set<Module>,
    val terminology: Map<String, String> = emptyMap(),
    val units: List<String> = emptyList(),
    val workflows: Map<Module, Workflow> = emptyMap(),
    val customFields: List<CustomField> = emptyList(),
    val language: String = "en",
    val schemaVersion: Int = 1,
) {
    fun has(m: Module) = m in modules
    fun workflow(m: Module): Workflow = workflows[m] ?: Templates.defaultWorkflow(m)
    fun fields(m: Module) = customFields.filter { it.module == m }
    /** The owner's word for a thing, e.g. term("work_item", "Order") -> "Production order". */
    fun term(key: String, fallback: String) = terminology[key]?.takeIf { it.isNotBlank() } ?: fallback
    val workModules: List<Module> get() = Module.WORK.filter { it in modules }

    /** @return problems in plain words; empty when the configuration can be saved. */
    fun validate(): List<String> {
        val errors = ArrayList<String>()
        if (schemaVersion != 1) errors.add("schemaVersion must be 1")
        if (businessType !in Templates.TYPES) errors.add("unknown business type $businessType")
        if (modules.isEmpty()) errors.add("choose at least one module")
        if (terminology.size > 60 || terminology.any { (k, v) -> !KEY.matches(k) || v.isBlank() || v.length > 40 }) errors.add("bad terminology")
        if (units.size > 40 || units.any { it.isBlank() || it.length > 20 }) errors.add("bad units")
        for ((m, w) in workflows) {
            if (w.statuses.isEmpty() || w.statuses.size > 30) errors.add("${m.key}: 1 to 30 statuses")
            if (w.stages.size > 30) errors.add("${m.key}: at most 30 stages")
            for (list in listOf(w.statuses, w.stages)) {
                if (list.any { it.isBlank() || it.length > 40 }) errors.add("${m.key}: names must be 1-40 characters")
                if (list.map { it.lowercase() }.toSet().size != list.size) errors.add("${m.key}: repeated names")
            }
            if (w.done.isEmpty() || w.done.any { d -> w.statuses.none { it.equals(d, true) } }) errors.add("${m.key}: closing statuses must be among the statuses")
            if (w.openStatuses.isEmpty()) errors.add("${m.key}: needs at least one open status")
        }
        if (customFields.size > 80) errors.add("too many custom fields")
        for (f in customFields) {
            if (!KEY.matches(f.key) || f.label.isBlank() || f.label.length > 40) errors.add("bad custom field ${f.key}")
            if (f.type == FieldType.CHOICE && (f.options.isEmpty() || f.options.size > 30)) errors.add("choice field ${f.key} needs 1-30 options")
        }
        if (customFields.groupBy { it.module to it.key }.any { it.value.size > 1 }) errors.add("repeated custom field")
        if (language !in setOf("en", "ta", "tanglish")) errors.add("bad language")
        return errors
    }

    companion object {
        private val KEY = Regex("^[a-z][a-z0-9_]{0,39}$")
    }
}
