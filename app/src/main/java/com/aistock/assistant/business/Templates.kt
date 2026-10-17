package com.aistock.assistant.business

import com.aistock.assistant.business.Module.APPOINTMENTS
import com.aistock.assistant.business.Module.CUSTOMERS
import com.aistock.assistant.business.Module.EXPENSES
import com.aistock.assistant.business.Module.FOLLOWUPS
import com.aistock.assistant.business.Module.INVENTORY
import com.aistock.assistant.business.Module.ORDERS
import com.aistock.assistant.business.Module.PRODUCTION
import com.aistock.assistant.business.Module.PROJECTS
import com.aistock.assistant.business.Module.PURCHASES
import com.aistock.assistant.business.Module.SALES
import com.aistock.assistant.business.Module.SERVICE_TICKETS
import com.aistock.assistant.business.Module.STAFF
import com.aistock.assistant.business.Module.SUPPLIERS
import com.aistock.assistant.business.Module.TASKS

/** A starting point for one kind of business. Every part of it can be changed afterwards. */
data class Template(
    val type: String,
    val nameEn: String,
    val nameTa: String,
    val descriptionEn: String,
    val descriptionTa: String,
    val config: BusinessConfig,
    /** What the template does not do yet, said honestly on the setup screen. */
    val limitsEn: String = "",
)

object Templates {
    val TYPES = listOf("retail", "garment_manufacturing", "wholesale", "grocery", "restaurant", "service_repair", "salon", "construction", "agency", "custom")

    private val TASKS_FLOW = Workflow(listOf("To do", "In progress", "Waiting", "Done", "Cancelled"), listOf("Done", "Cancelled"))

    fun defaultWorkflow(m: Module): Workflow = when (m) {
        PRODUCTION -> Workflow(listOf("Open", "In progress", "On hold", "Completed", "Cancelled"), listOf("Completed", "Cancelled"),
            listOf("Cutting", "Stitching", "Finishing", "Quality check", "Packing", "Dispatched"))
        SERVICE_TICKETS -> Workflow(listOf("Open", "Waiting for customer", "Ready", "Delivered", "Cancelled"), listOf("Delivered", "Cancelled"),
            listOf("Received", "Diagnosing", "Waiting approval", "Waiting parts", "Repairing", "Testing"))
        APPOINTMENTS -> Workflow(listOf("Booked", "Confirmed", "Arrived", "Completed", "No-show", "Cancelled"), listOf("Completed", "No-show", "Cancelled"))
        PROJECTS -> Workflow(listOf("Planned", "In progress", "On hold", "Completed", "Cancelled"), listOf("Completed", "Cancelled"),
            listOf("Planning", "Foundation", "Structure", "Finishing", "Handover"))
        ORDERS -> Workflow(listOf("New", "Packed", "Dispatched", "Delivered", "Cancelled"), listOf("Delivered", "Cancelled"))
        else -> TASKS_FLOW
    }

    private fun cfg(type: String, modules: Set<Module>, units: List<String>, terms: Map<String, String> = emptyMap(),
                    flows: Map<Module, Workflow> = emptyMap(), fields: List<CustomField> = emptyList()) =
        BusinessConfig(type, modules, terms, units, modules.filter { it.isWork }.associateWith { flows[it] ?: defaultWorkflow(it) }, fields)

    private fun f(m: Module, key: String, label: String, type: FieldType = FieldType.TEXT, options: List<String> = emptyList()) = CustomField(m, key, label, type, options)

    val ALL: List<Template> = listOf(
        Template("retail", "Fancy store / retail shop", "ஃபேன்சி ஸ்டோர் / சில்லறை கடை",
            "Products, stock, sales, purchases, expenses, customers and follow-ups.", "பொருட்கள், ஸ்டாக், விற்பனை, கொள்முதல், செலவு, வாடிக்கையாளர், follow-up.",
            cfg("retail", setOf(INVENTORY, SALES, PURCHASES, EXPENSES, CUSTOMERS, SUPPLIERS, FOLLOWUPS, TASKS), listOf("PIECE", "PAIR", "BOX", "DOZEN", "PACKET"))),
        Template("garment_manufacturing", "Garments / textile manufacturing", "ஆடை / ஜவுளி உற்பத்தி",
            "Fabric and materials, production orders moving through cutting, stitching, finishing and checking, worker tasks, job work and dispatch.",
            "Fabric, production order (cutting, stitching, finishing, checking), வேலையாள் task, job work, dispatch.",
            cfg("garment_manufacturing", setOf(INVENTORY, PRODUCTION, PURCHASES, SALES, EXPENSES, CUSTOMERS, SUPPLIERS, TASKS, STAFF, FOLLOWUPS),
                listOf("METER", "KG", "ROLL", "PIECE", "DOZEN"),
                mapOf("production" to "Production order", "production_tab" to "Production", "customer" to "Buyer"),
                fields = listOf(f(PRODUCTION, "buyer_po", "Buyer PO"), f(PRODUCTION, "style_no", "Style no."), f(PRODUCTION, "size_ratio", "Size ratio"),
                    f(PRODUCTION, "fabric", "Fabric"), f(PRODUCTION, "job_worker", "Job worker"), f(PRODUCTION, "wastage_kg", "Wastage (kg)", FieldType.NUMBER))),
            limitsEn = "Material use per order is recorded as stock-out with a note; automatic bill-of-materials consumption is not built yet."),
        Template("wholesale", "Wholesale / distribution", "மொத்த விற்பனை / விநியோகம்",
            "Bulk stock in cartons, boxes and dozens, supplier purchases, customer orders and delivery status, balances.",
            "Carton, box, dozen ஸ்டாக், கொள்முதல், வாடிக்கையாளர் order, delivery நிலை, நிலுவை.",
            cfg("wholesale", setOf(INVENTORY, SALES, PURCHASES, EXPENSES, CUSTOMERS, SUPPLIERS, ORDERS, FOLLOWUPS, TASKS), listOf("CARTON", "BOX", "DOZEN", "PIECE", "KG"),
                mapOf("orders" to "Customer order", "orders_tab" to "Orders"),
                fields = listOf(f(ORDERS, "vehicle", "Vehicle / transport"), f(ORDERS, "lr_no", "LR / docket no."))),
            limitsEn = "Unit conversions (1 carton = 12 boxes) are entered as separate units; automatic conversion is not built yet."),
        Template("grocery", "Grocery / supermarket", "மளிகை / சூப்பர் மார்க்கெட்",
            "Products and stock, batches with expiry dates, purchases, sales, wastage and supplier records.",
            "பொருட்கள், ஸ்டாக், காலாவதி தேதி, கொள்முதல், விற்பனை, வீணானவை, சப்ளையர்.",
            cfg("grocery", setOf(INVENTORY, SALES, PURCHASES, EXPENSES, CUSTOMERS, SUPPLIERS, TASKS), listOf("KG", "GRAM", "LITRE", "PACKET", "PIECE"))),
        Template("restaurant", "Restaurant / food business", "உணவகம் / உணவு வணிகம்",
            "Ingredients stock, purchases, wastage, orders with kitchen status, expenses and staff tasks.",
            "சமையல் பொருள் ஸ்டாக், கொள்முதல், வீணானவை, order நிலை, செலவு, staff task.",
            cfg("restaurant", setOf(INVENTORY, PURCHASES, SALES, EXPENSES, SUPPLIERS, ORDERS, TASKS, STAFF), listOf("KG", "GRAM", "LITRE", "PIECE", "PLATE"),
                mapOf("orders" to "Order", "orders_tab" to "Orders", "inventory_tab" to "Ingredients"),
                flows = mapOf(ORDERS to Workflow(listOf("New", "Preparing", "Ready", "Served", "Cancelled"), listOf("Served", "Cancelled"))),
                fields = listOf(f(ORDERS, "table_no", "Table / token"))),
            limitsEn = "Recipe-based automatic ingredient use is not built yet; record ingredient use as stock-out."),
        Template("service_repair", "Service centre / repairs", "சர்வீஸ் சென்டர் / பழுதுபார்ப்பு",
            "Job cards with device and problem, estimate and approval, spare parts, technician, repair stage and delivery.",
            "Device, பிரச்சனை, estimate, ஒப்புதல், spare parts, technician, repair நிலை, delivery கொண்ட job card.",
            cfg("service_repair", setOf(SERVICE_TICKETS, INVENTORY, SALES, CUSTOMERS, SUPPLIERS, EXPENSES, FOLLOWUPS, TASKS, STAFF), listOf("PIECE", "SET"),
                mapOf("service_tickets" to "Job card", "service_tickets_tab" to "Jobs", "inventory_tab" to "Spares"),
                fields = listOf(f(SERVICE_TICKETS, "device", "Device"), f(SERVICE_TICKETS, "brand_model", "Brand / model"), f(SERVICE_TICKETS, "serial_no", "Serial / IMEI"),
                    f(SERVICE_TICKETS, "problem", "Problem"), f(SERVICE_TICKETS, "estimate", "Estimate (₹)", FieldType.NUMBER), f(SERVICE_TICKETS, "approved", "Customer approved", FieldType.BOOLEAN)))),
        Template("salon", "Salon / appointments", "சலூன் / முன்பதிவு",
            "Appointments, services, customer history, staff schedule, reminders and daily takings.",
            "முன்பதிவு, சேவைகள், வாடிக்கையாளர் வரலாறு, staff அட்டவணை, நினைவூட்டல், தினசரி வருமானம்.",
            cfg("salon", setOf(APPOINTMENTS, CUSTOMERS, SALES, EXPENSES, STAFF, FOLLOWUPS, INVENTORY), listOf("PIECE", "BOTTLE"),
                mapOf("appointments" to "Appointment", "appointments_tab" to "Bookings"),
                fields = listOf(f(APPOINTMENTS, "service", "Service"), f(APPOINTMENTS, "duration_min", "Minutes", FieldType.NUMBER)))),
        Template("construction", "Construction / projects", "கட்டுமானம் / திட்டங்கள்",
            "Projects and sites with stages, material requests and use, labour tasks, expenses, deadlines and supplier deliveries.",
            "திட்டம் / site நிலைகள், material, வேலையாள் task, செலவு, காலக்கெடு, சப்ளையர் delivery.",
            cfg("construction", setOf(PROJECTS, INVENTORY, PURCHASES, EXPENSES, SUPPLIERS, CUSTOMERS, TASKS, STAFF, FOLLOWUPS), listOf("BAG", "TON", "LOAD", "CFT", "PIECE", "KG"),
                mapOf("projects" to "Project", "projects_tab" to "Sites", "inventory_tab" to "Materials"),
                fields = listOf(f(PROJECTS, "site", "Site / location"), f(PROJECTS, "budget", "Budget (₹)", FieldType.NUMBER)))),
        Template("agency", "Agency / professional services", "ஏஜென்சி / தொழில்முறை சேவைகள்",
            "Enquiries, projects with milestones, staff tasks, follow-ups, deadlines and billing.",
            "விசாரணை, milestone கொண்ட project, staff task, follow-up, காலக்கெடு, பில்.",
            cfg("agency", setOf(PROJECTS, CUSTOMERS, TASKS, FOLLOWUPS, STAFF, SALES, EXPENSES), listOf("HOUR", "DAY", "ITEM"),
                mapOf("projects" to "Project", "projects_tab" to "Projects"),
                flows = mapOf(PROJECTS to Workflow(listOf("Enquiry", "Proposal sent", "In progress", "Review", "Delivered", "Paid", "Lost"), listOf("Paid", "Lost"),
                    listOf("Brief", "Draft", "Revisions", "Final"))),
                fields = listOf(f(PROJECTS, "fee", "Fee (₹)", FieldType.NUMBER)))),
        Template("custom", "Other business", "மற்ற வணிகம்",
            "Start with tasks, follow-ups and customers, then switch on what you need.",
            "Task, follow-up, வாடிக்கையாளர் உடன் தொடங்கி, தேவையானதைச் சேர்க்கவும்.",
            cfg("custom", setOf(TASKS, FOLLOWUPS, CUSTOMERS, SALES, EXPENSES), listOf("PIECE", "HOUR"))),
    )

    fun of(type: String?): Template = ALL.firstOrNull { it.type == type } ?: ALL.first()

    /** What an existing V1/V2 install gets until the owner chooses: exactly what it had before, plus tasks and follow-ups. */
    val DEFAULT: BusinessConfig get() = of("retail").config

    /** Picks the closest template from what the owner said ("naan Tiruppur-la garment manufacturing panren"). */
    fun guess(text: String): Template? {
        val t = text.lowercase()
        val words = listOf(
            "garment_manufacturing" to listOf("garment", "textile", "stitching", "tailoring", "knitting", "apparel", "export", "ஆடை", "தையல்", "ஜவுளி"),
            "service_repair" to listOf("repair", "service centre", "service center", "mobile service", "workshop", "பழுது"),
            "salon" to listOf("salon", "parlour", "parlor", "beauty", "spa", "barber", "சலூன்"),
            "construction" to listOf("construction", "builder", "contractor", "civil", "site", "கட்டுமான"),
            "restaurant" to listOf("restaurant", "hotel", "mess", "cafe", "bakery", "food", "catering", "உணவகம்"),
            "grocery" to listOf("grocery", "supermarket", "provision", "kirana", "மளிகை"),
            "wholesale" to listOf("wholesale", "distributor", "distribution", "dealer", "agency stock", "மொத்த"),
            "agency" to listOf("agency", "consult", "design studio", "marketing", "software", "freelance"),
            "retail" to listOf("fancy", "store", "shop", "retail", "boutique", "கடை"),
        )
        return words.firstOrNull { (_, ws) -> ws.any { it in t } }?.let { of(it.first) }
    }
}
